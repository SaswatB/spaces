use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use utoipa::{IntoParams, ToSchema};

#[allow(unused_imports)]
use crate::models::{Entrypoint, Layer, LayerDiffEntry, MountStatus, SyncState, UserMount};
use crate::services::SpacesService;
use crate::state::AppState;

#[derive(Serialize, ToSchema)]
pub struct HealthResponse {
    status: &'static str,
}

pub async fn health() -> impl IntoResponse {
    Json(HealthResponse { status: "ok" })
}

#[derive(Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct StatusResponse {
    entrypoint_count: usize,
    layer_count: usize,
    user_mount_count: usize,
    mounted_layers: usize,
    mounted_user_mounts: usize,
}

#[derive(Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LayerResponse {
    id: String,
    name: String,
    entrypoint_id: String,
    parent_id: Option<String>,
    upper_dir: String,
    work_dir: String,
    mount_path: String,
    mount_status: MountStatus,
    created_at: chrono::DateTime<chrono::Utc>,
    updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UserMountResponse {
    id: String,
    name: String,
    entrypoint_id: String,
    attached_layer_id: Option<String>,
    upper_dir: String,
    work_dir: String,
    mount_path: String,
    mount_status: MountStatus,
    #[schema(value_type = Option<SyncState>)]
    sync_state: Option<SyncState>,
    created_at: chrono::DateTime<chrono::Utc>,
    updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Deserialize, ToSchema, IntoParams)]
#[serde(rename_all = "camelCase")]
pub struct ListQuery {
    #[serde(rename = "entrypointId")]
    entrypoint_id: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateEntrypointRequest {
    name: Option<String>,
    path: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateLayerRequest {
    name: Option<String>,
    #[serde(rename = "entrypointId")]
    entrypoint_id: String,
    #[serde(rename = "parentId")]
    parent_id: Option<String>,
    #[serde(rename = "mountPath")]
    mount_path: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateUserMountRequest {
    name: String,
    #[serde(rename = "entrypointId")]
    entrypoint_id: String,
    #[serde(rename = "mountPath")]
    mount_path: String,
    #[serde(rename = "attachedLayerId")]
    attached_layer_id: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AttachLayerRequest {
    #[serde(rename = "userMountId")]
    user_mount_id: String,
    #[serde(rename = "layerId")]
    layer_id: Option<String>,
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/system/status", get(system_status))
        .route("/system/remount", post(system_remount))
        .route("/entrypoints", get(list_entrypoints).post(create_entrypoint))
        .route(
            "/entrypoints/:id",
            get(get_entrypoint).delete(delete_entrypoint),
        )
        .route("/layers", get(list_layers).post(create_layer))
        .route("/layers/:id", get(get_layer).delete(delete_layer))
        .route("/layers/:id/mount", post(mount_layer))
        .route("/layers/:id/unmount", post(unmount_layer))
        .route("/layers/:id/diff", get(layer_diff))
        .route("/user-mounts", get(list_user_mounts).post(create_user_mount))
        .route(
            "/user-mounts/:id",
            get(get_user_mount).delete(delete_user_mount),
        )
        .route("/user-mounts/:id/mount", post(mount_user_mount))
        .route("/user-mounts/:id/unmount", post(unmount_user_mount))
        .route("/user-mounts/attach", post(attach_layer))
        .with_state(state)
}

#[utoipa::path(
    get,
    path = "/system/status",
    responses(
        (status = 200, body = StatusResponse)
    ),
    tag = "system"
)]
pub async fn system_status(State(state): State<AppState>) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match (
        service.list_entrypoints(),
        service.list_layers_with_status(None),
        service.list_user_mounts_with_status(None),
    ) {
        (Ok(entrypoints), Ok(layers), Ok(user_mounts)) => {
            let response = StatusResponse {
                entrypoint_count: entrypoints.len(),
                layer_count: layers.len(),
                user_mount_count: user_mounts.len(),
                mounted_layers: layers
                    .iter()
                    .filter(|layer| matches!(layer.mount_status, MountStatus::Mounted))
                    .count(),
                mounted_user_mounts: user_mounts
                    .iter()
                    .filter(|mount| matches!(mount.mount_status, MountStatus::Mounted))
                    .count(),
            };
            (StatusCode::OK, Json(response)).into_response()
        }
        _ => error_response("Failed to compute status"),
    }
}

#[utoipa::path(
    post,
    path = "/system/remount",
    responses(
        (status = 200)
    ),
    tag = "system"
)]
pub async fn system_remount(State(state): State<AppState>) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.remount_all()
    })
    .await
    {
        Ok(Ok(())) => StatusCode::OK.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/entrypoints",
    responses(
        (status = 200, body = [Entrypoint])
    ),
    tag = "entrypoints"
)]
pub async fn list_entrypoints(State(state): State<AppState>) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match service.list_entrypoints() {
        Ok(entrypoints) => Json(entrypoints).into_response(),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/entrypoints/{id}",
    params(
        ("id" = String, Path, description = "Entrypoint ID")
    ),
    responses(
        (status = 200, body = Entrypoint),
        (status = 404)
    ),
    tag = "entrypoints"
)]
pub async fn get_entrypoint(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match service.get_entrypoint(&id) {
        Ok(Some(entrypoint)) => Json(entrypoint).into_response(),
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/entrypoints",
    request_body = CreateEntrypointRequest,
    responses(
        (status = 201, body = Entrypoint)
    ),
    tag = "entrypoints"
)]
pub async fn create_entrypoint(
    State(state): State<AppState>,
    Json(payload): Json<CreateEntrypointRequest>,
) -> impl IntoResponse {
    let state = state.clone();
    let name = payload.name;
    let path = payload.path;
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.create_entrypoint(name, path)
    })
    .await
    {
        Ok(Ok(entrypoint)) => (StatusCode::CREATED, Json(entrypoint)).into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    delete,
    path = "/entrypoints/{id}",
    params(
        ("id" = String, Path, description = "Entrypoint ID")
    ),
    responses(
        (status = 204),
        (status = 400, body = serde_json::Value)
    ),
    tag = "entrypoints"
)]
pub async fn delete_entrypoint(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.delete_entrypoint(&id)
    })
    .await
    {
        Ok(Ok(())) => StatusCode::NO_CONTENT.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/layers",
    params(ListQuery),
    responses(
        (status = 200, body = [LayerResponse])
    ),
    tag = "layers"
)]
pub async fn list_layers(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match service.list_layers_with_status(query.entrypoint_id.as_deref()) {
        Ok(layers) => {
            let response = layers
                .into_iter()
                .map(|layer| layer_response(layer.layer, layer.mount_status))
                .collect::<Vec<_>>();
            Json(response).into_response()
        }
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/layers/{id}",
    params(
        ("id" = String, Path, description = "Layer ID")
    ),
    responses(
        (status = 200, body = LayerResponse),
        (status = 404)
    ),
    tag = "layers"
)]
pub async fn get_layer(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match service.get_layer(&id) {
        Ok(Some(layer)) => {
            let status = service
                .list_layers_with_status(None)
                .ok()
                .and_then(|layers| {
                    layers
                        .into_iter()
                        .find(|item| item.layer.id == layer.id)
                        .map(|item| item.mount_status)
                })
                .unwrap_or(MountStatus::Error);
            Json(layer_response(layer, status)).into_response()
        }
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/layers",
    request_body = CreateLayerRequest,
    responses(
        (status = 201, body = LayerResponse)
    ),
    tag = "layers"
)]
pub async fn create_layer(
    State(state): State<AppState>,
    Json(payload): Json<CreateLayerRequest>,
) -> impl IntoResponse {
    let state = state.clone();
    let name = payload.name;
    let entrypoint_id = payload.entrypoint_id;
    let parent_id = payload.parent_id;
    let mount_path = payload.mount_path;
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.create_layer(name, entrypoint_id, parent_id, mount_path)
    })
    .await
    {
        Ok(Ok(layer)) => {
            (StatusCode::CREATED, Json(layer_response(layer, MountStatus::Unmounted))).into_response()
        }
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    delete,
    path = "/layers/{id}",
    params(
        ("id" = String, Path, description = "Layer ID")
    ),
    responses(
        (status = 204)
    ),
    tag = "layers"
)]
pub async fn delete_layer(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.delete_layer(&id)
    })
    .await
    {
        Ok(Ok(())) => StatusCode::NO_CONTENT.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/layers/{id}/mount",
    params(
        ("id" = String, Path, description = "Layer ID")
    ),
    responses(
        (status = 200)
    ),
    tag = "layers"
)]
pub async fn mount_layer(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || -> anyhow::Result<Option<()>> {
        let service = SpacesService::new(&state);
        match service.get_layer(&id)? {
            Some(layer) => {
                service.mount_layer(&layer)?;
                Ok(Some(()))
            }
            None => Ok(None),
        }
    })
    .await
    {
        Ok(Ok(Some(()))) => StatusCode::OK.into_response(),
        Ok(Ok(None)) => StatusCode::NOT_FOUND.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/layers/{id}/unmount",
    params(
        ("id" = String, Path, description = "Layer ID")
    ),
    responses(
        (status = 200)
    ),
    tag = "layers"
)]
pub async fn unmount_layer(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || -> anyhow::Result<Option<()>> {
        let service = SpacesService::new(&state);
        match service.get_layer(&id)? {
            Some(layer) => {
                service.unmount_layer(&layer)?;
                Ok(Some(()))
            }
            None => Ok(None),
        }
    })
    .await
    {
        Ok(Ok(Some(()))) => StatusCode::OK.into_response(),
        Ok(Ok(None)) => StatusCode::NOT_FOUND.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/layers/{id}/diff",
    params(
        ("id" = String, Path, description = "Layer ID")
    ),
    responses(
        (status = 200, body = [LayerDiffEntry])
    ),
    tag = "layers"
)]
pub async fn layer_diff(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.layer_diff(&id)
    })
    .await
    {
        Ok(Ok(entries)) => Json(entries).into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/user-mounts",
    params(ListQuery),
    responses(
        (status = 200, body = [UserMountResponse])
    ),
    tag = "user-mounts"
)]
pub async fn list_user_mounts(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match service.list_user_mounts_with_status(query.entrypoint_id.as_deref()) {
        Ok(mounts) => {
            let response = mounts
                .into_iter()
                .map(|mount| user_mount_response(mount.user_mount, mount.mount_status, None))
                .collect::<Vec<_>>();
            Json(response).into_response()
        }
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    get,
    path = "/user-mounts/{id}",
    params(
        ("id" = String, Path, description = "User mount ID")
    ),
    responses(
        (status = 200, body = UserMountResponse),
        (status = 404)
    ),
    tag = "user-mounts"
)]
pub async fn get_user_mount(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let service = SpacesService::new(&state);
    match service.get_user_mount(&id) {
        Ok(Some(user_mount)) => {
            let status = service
                .list_user_mounts_with_status(None)
                .ok()
                .and_then(|mounts| {
                    mounts
                        .into_iter()
                        .find(|item| item.user_mount.id == user_mount.id)
                        .map(|item| item.mount_status)
                })
                .unwrap_or(MountStatus::Error);
            Json(user_mount_response(user_mount, status, None)).into_response()
        }
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/user-mounts",
    request_body = CreateUserMountRequest,
    responses(
        (status = 201, body = UserMountResponse)
    ),
    tag = "user-mounts"
)]
pub async fn create_user_mount(
    State(state): State<AppState>,
    Json(payload): Json<CreateUserMountRequest>,
) -> impl IntoResponse {
    let state = state.clone();
    let name = payload.name;
    let entrypoint_id = payload.entrypoint_id;
    let mount_path = payload.mount_path;
    let attached_layer_id = payload.attached_layer_id;
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.create_user_mount(name, entrypoint_id, mount_path, attached_layer_id)
    })
    .await
    {
        Ok(Ok(user_mount)) => (
            StatusCode::CREATED,
            Json(user_mount_response(
                user_mount,
                MountStatus::Unmounted,
                None,
            )),
        )
            .into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    delete,
    path = "/user-mounts/{id}",
    params(
        ("id" = String, Path, description = "User mount ID")
    ),
    responses(
        (status = 204)
    ),
    tag = "user-mounts"
)]
pub async fn delete_user_mount(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.delete_user_mount(&id)
    })
    .await
    {
        Ok(Ok(())) => StatusCode::NO_CONTENT.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/user-mounts/{id}/mount",
    params(
        ("id" = String, Path, description = "User mount ID")
    ),
    responses(
        (status = 200)
    ),
    tag = "user-mounts"
)]
pub async fn mount_user_mount(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || -> anyhow::Result<Option<()>> {
        let service = SpacesService::new(&state);
        match service.get_user_mount(&id)? {
            Some(user_mount) => {
                service.mount_user_mount(&user_mount)?;
                Ok(Some(()))
            }
            None => Ok(None),
        }
    })
    .await
    {
        Ok(Ok(Some(()))) => StatusCode::OK.into_response(),
        Ok(Ok(None)) => StatusCode::NOT_FOUND.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/user-mounts/{id}/unmount",
    params(
        ("id" = String, Path, description = "User mount ID")
    ),
    responses(
        (status = 200)
    ),
    tag = "user-mounts"
)]
pub async fn unmount_user_mount(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let state = state.clone();
    match tokio::task::spawn_blocking(move || -> anyhow::Result<Option<()>> {
        let service = SpacesService::new(&state);
        match service.get_user_mount(&id)? {
            Some(user_mount) => {
                service.unmount_user_mount(&user_mount)?;
                Ok(Some(()))
            }
            None => Ok(None),
        }
    })
    .await
    {
        Ok(Ok(Some(()))) => StatusCode::OK.into_response(),
        Ok(Ok(None)) => StatusCode::NOT_FOUND.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

#[utoipa::path(
    post,
    path = "/user-mounts/attach",
    request_body = AttachLayerRequest,
    responses(
        (status = 200)
    ),
    tag = "user-mounts"
)]
pub async fn attach_layer(
    State(state): State<AppState>,
    Json(payload): Json<AttachLayerRequest>,
) -> impl IntoResponse {
    let state = state.clone();
    let user_mount_id = payload.user_mount_id;
    let layer_id = payload.layer_id;
    match tokio::task::spawn_blocking(move || {
        let service = SpacesService::new(&state);
        service.attach_layer(user_mount_id, layer_id)
    })
    .await
    {
        Ok(Ok(())) => StatusCode::OK.into_response(),
        Ok(Err(err)) => error_response(&err.to_string()),
        Err(err) => error_response(&err.to_string()),
    }
}

fn layer_response(layer: Layer, status: MountStatus) -> LayerResponse {
    LayerResponse {
        id: layer.id,
        name: layer.name,
        entrypoint_id: layer.entrypoint_id,
        parent_id: layer.parent_id,
        upper_dir: layer.upper_dir,
        work_dir: layer.work_dir,
        mount_path: layer.mount_path,
        mount_status: status,
        created_at: layer.created_at,
        updated_at: layer.updated_at,
    }
}

fn user_mount_response(
    mount: UserMount,
    status: MountStatus,
    sync_state: Option<crate::models::SyncState>,
) -> UserMountResponse {
    UserMountResponse {
        id: mount.id,
        name: mount.name,
        entrypoint_id: mount.entrypoint_id,
        attached_layer_id: mount.attached_layer_id,
        upper_dir: mount.upper_dir,
        work_dir: mount.work_dir,
        mount_path: mount.mount_path,
        mount_status: status,
        sync_state,
        created_at: mount.created_at,
        updated_at: mount.updated_at,
    }
}

fn error_response(message: &str) -> axum::response::Response {
    let body = serde_json::json!({ "error": message });
    (StatusCode::BAD_REQUEST, Json(body)).into_response()
}
