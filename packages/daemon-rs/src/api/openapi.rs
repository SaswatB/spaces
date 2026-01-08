use utoipa::OpenApi;

use crate::api::routes;
use crate::models::{
    Entrypoint, FileChange, FileChangeType, Layer, LayerDiffEntry, LayerDiffType, MountStatus,
    SyncState, SyncStatus, UserMount,
};
use crate::api::routes::{
    AttachLayerRequest, CreateEntrypointRequest, CreateLayerRequest, CreateUserMountRequest,
    HealthResponse, LayerResponse, ListQuery, StatusResponse, UserMountResponse,
};

#[derive(OpenApi)]
#[openapi(
    paths(
        routes::system_status,
        routes::system_remount,
        routes::list_entrypoints,
        routes::get_entrypoint,
        routes::create_entrypoint,
        routes::delete_entrypoint,
        routes::list_layers,
        routes::get_layer,
        routes::create_layer,
        routes::delete_layer,
        routes::mount_layer,
        routes::unmount_layer,
        routes::layer_diff,
        routes::list_user_mounts,
        routes::get_user_mount,
        routes::create_user_mount,
        routes::delete_user_mount,
        routes::mount_user_mount,
        routes::unmount_user_mount,
        routes::attach_layer
    ),
    components(schemas(
        Entrypoint,
        Layer,
        UserMount,
        MountStatus,
        SyncStatus,
        SyncState,
        FileChange,
        FileChangeType,
        LayerDiffEntry,
        LayerDiffType,
        HealthResponse,
        StatusResponse,
        LayerResponse,
        UserMountResponse,
        ListQuery,
        CreateEntrypointRequest,
        CreateLayerRequest,
        CreateUserMountRequest,
        AttachLayerRequest
    )),
    tags(
        (name = "system"),
        (name = "entrypoints"),
        (name = "layers"),
        (name = "user-mounts")
    )
)]
pub struct ApiDoc;

pub fn openapi() -> utoipa::openapi::OpenApi {
    ApiDoc::openapi()
}
