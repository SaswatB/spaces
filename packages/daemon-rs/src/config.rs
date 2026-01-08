use anyhow::{Context, Result};
use std::env;

#[derive(Clone, Debug)]
pub struct Config {
    pub data_dir: String,
    pub db_path: String,
    pub api_host: String,
    pub api_port: u16,
    pub nfs_host: String,
    pub nfs_port: u16,
    pub nfs_export_root: String,
    pub auth_token: Option<String>,
}

impl Config {
    pub fn from_env() -> Result<Self> {
        let data_dir = env::var("SPACES_DATA_DIR").unwrap_or_else(|_| "/var/lib/spaces".to_string());
        let db_path = env::var("SPACES_DB_PATH")
            .unwrap_or_else(|_| format!("{}/spaces.db", data_dir));

        let api_host = env::var("SPACES_API_HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
        let api_port = env::var("SPACES_API_PORT")
            .unwrap_or_else(|_| "3100".to_string())
            .parse::<u16>()
            .context("SPACES_API_PORT must be a valid u16")?;

        let nfs_host = env::var("SPACES_NFS_HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
        let nfs_port = env::var("SPACES_NFS_PORT")
            .unwrap_or_else(|_| "11111".to_string())
            .parse::<u16>()
            .context("SPACES_NFS_PORT must be a valid u16")?;
        let nfs_export_root =
            env::var("SPACES_NFS_EXPORT_ROOT").unwrap_or_else(|_| "/spaces".to_string());

        let auth_token = env::var("SPACES_AUTH_TOKEN").ok();

        Ok(Self {
            data_dir,
            db_path,
            api_host,
            api_port,
            nfs_host,
            nfs_port,
            nfs_export_root,
            auth_token,
        })
    }
}
