use anyhow::Result;
use tracing_subscriber::{fmt, EnvFilter};
use tracing::{info, warn};

pub mod api;
pub mod config;
pub mod db;
pub mod models;
pub mod nfs;
pub mod overlay;
pub mod replication;
pub mod services;
pub mod state;

pub async fn run() -> Result<()> {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    fmt().with_env_filter(filter).init();

    let config = config::Config::from_env()?;
    let db = db::Database::new(&config.db_path)?;
    db.initialize()?;

    let state = state::AppState::new(config, db);

    let remount_state = state.clone();
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        for attempt in 1..=5 {
            info!(attempt, "Remounting state on startup");
            let service = services::SpacesService::new(&remount_state);
            let result = tokio::task::spawn_blocking(move || service.remount_all()).await;
            match result {
                Ok(Ok(())) => {
                    info!("Startup remount completed");
                    break;
                }
                Ok(Err(err)) => {
                    warn!(?err, "Startup remount failed");
                }
                Err(err) => {
                    warn!(?err, "Startup remount task failed");
                }
            }
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        }
    });

    let api_handle = api::serve(state.clone()).await?;
    let nfs_handle = nfs::serve(state.clone()).await?;

    tokio::select! {
        _ = api_handle => {},
        _ = nfs_handle => {},
        _ = tokio::signal::ctrl_c() => {
            tracing::info!("Shutdown signal received");
        }
    }

    tracing::info!("Shutting down, unmounting NFS mounts");
    let shutdown_state = state.clone();
    let _ = tokio::task::spawn_blocking(move || {
        let service = services::SpacesService::new(&shutdown_state);
        let _ = service.unmount_all();
    })
    .await;

    std::process::exit(0);
}
