use std::sync::{Arc, Mutex};

use crate::config::Config;
use crate::db::Database;
use crate::nfs::NfsRegistry;
use crate::overlay::OverlayEngine;
use crate::replication::ReplicationEngine;

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub db: Database,
    pub overlay: Arc<Mutex<OverlayEngine>>,
    pub replication: Arc<ReplicationEngine>,
    pub nfs_registry: Arc<NfsRegistry>,
}

impl AppState {
    pub fn new(config: Config, db: Database) -> Self {
        let overlay = Arc::new(Mutex::new(OverlayEngine::new()));
        let replication = Arc::new(ReplicationEngine::new());
        let nfs_registry = Arc::new(NfsRegistry::new());

        Self {
            config,
            db,
            overlay,
            replication,
            nfs_registry,
        }
    }
}
