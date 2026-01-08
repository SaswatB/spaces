use anyhow::Result;
use axum::{routing::get, Router};
use tokio::task::JoinHandle;

use crate::state::AppState;

mod routes;
pub mod openapi;

pub async fn serve(state: AppState) -> Result<JoinHandle<()>> {
    let openapi = std::sync::Arc::new(openapi::openapi());
    let app = Router::new()
        .merge(routes::router(state.clone()))
        .route("/health", get(routes::health))
        .route(
            "/openapi.json",
            get({
                let openapi = openapi.clone();
                move || async move { axum::Json((*openapi).clone()) }
            }),
        );

    let addr = format!("{}:{}", state.config.api_host, state.config.api_port);
    let listener = tokio::net::TcpListener::bind(&addr).await?;

    let handle = tokio::spawn(async move {
        tracing::info!("API server listening on {}", addr);
        axum::serve(listener, app).await.ok();
    });

    Ok(handle)
}
