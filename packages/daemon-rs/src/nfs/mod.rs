use anyhow::Result;
use std::collections::HashMap;
use std::sync::Mutex;
use tokio::task::JoinHandle;

use crate::state::AppState;
use crate::services::SpacesService;

mod mounts;
mod events;
mod server;

pub use mounts::{ensure_mount, is_mounted, unmount, MountSpec};
pub use events::{NfsOp, NfsOpKind};
pub use server::SpacesNfs;

#[derive(Debug, Default)]
pub struct NfsRegistry {
    exports: Mutex<HashMap<String, NfsExport>>,
}

#[derive(Clone, Debug)]
pub struct NfsExport {
    pub mount_id: String,
    pub export_path: String,
    pub local_path: String,
}

impl NfsRegistry {
    pub fn new() -> Self {
        Self {
            exports: Mutex::new(HashMap::new()),
        }
    }

    pub fn register(&self, export: NfsExport) {
        let mut exports = self.exports.lock().expect("nfs registry lock poisoned");
        exports.insert(export.mount_id.clone(), export);
    }

    pub fn get(&self, mount_id: &str) -> Option<NfsExport> {
        let exports = self.exports.lock().expect("nfs registry lock poisoned");
        exports.get(mount_id).cloned()
    }

    pub fn list(&self) -> Vec<NfsExport> {
        let exports = self.exports.lock().expect("nfs registry lock poisoned");
        exports.values().cloned().collect()
    }
}

pub async fn serve(_state: AppState) -> Result<JoinHandle<()>> {
    let state = _state.clone();
    let addr = format!("{}:{}", _state.config.nfs_host, _state.config.nfs_port);
    let thread_handle = std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build();
        let Ok(runtime) = runtime else {
            tracing::error!("Failed to create NFS runtime");
            return;
        };
        runtime.block_on(async move {
            tracing::info!("NFS server listening on {}", addr);
            let server = SpacesNfs::new(state.clone());
            if let Err(err) = server.serve(&addr).await {
                tracing::error!(?err, "NFS server stopped");
            }
        });
    });

    let join_handle = tokio::task::spawn_blocking(move || {
        let _ = thread_handle.join();
    });

    Ok(join_handle)
}

pub fn handle_op(state: &AppState, op: NfsOp) -> Result<()> {
    let service = SpacesService::new(state);
    service.handle_nfs_op(op)
}
