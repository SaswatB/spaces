use anyhow::Result;
use tracing_subscriber::{fmt, EnvFilter};

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
    fmt().with_env_filter(EnvFilter::from_default_env()).init();

    let config = config::Config::from_env()?;
    let db = db::Database::new(&config.db_path)?;
    db.initialize()?;

    let state = state::AppState::new(config, db);

    let api_handle = api::serve(state.clone()).await?;
    let nfs_handle = nfs::serve(state.clone()).await?;

    tokio::select! {
        _ = api_handle => {},
        _ = nfs_handle => {},
        _ = tokio::signal::ctrl_c() => {
            tracing::info!("Shutdown signal received");
        }
    }

    Ok(())
}
