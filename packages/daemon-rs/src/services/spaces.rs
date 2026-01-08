use anyhow::{anyhow, Result};
use chrono::Utc;
use std::fs;
use std::path::Path;
use uuid::Uuid;

use crate::config::Config;
use crate::db::Database;
use crate::models::{
    Entrypoint, Layer, LayerWithStatus, MountStatus, UserMount, UserMountWithStatus,
};
use crate::nfs::{ensure_mount, is_mounted, MountSpec, NfsExport, NfsOp, NfsOpKind, NfsRegistry};
use crate::replication::{reconcile_trees, replay_change};
use crate::state::AppState;

#[derive(Clone)]
pub struct SpacesService {
    config: Config,
    db: Database,
    nfs_registry: std::sync::Arc<NfsRegistry>,
    replication: std::sync::Arc<crate::replication::ReplicationEngine>,
}

impl SpacesService {
    pub fn new(state: &AppState) -> Self {
        Self {
            config: state.config.clone(),
            db: state.db.clone(),
            nfs_registry: state.nfs_registry.clone(),
            replication: state.replication.clone(),
        }
    }

    pub fn list_entrypoints(&self) -> Result<Vec<Entrypoint>> {
        self.db.list_entrypoints()
    }

    pub fn get_entrypoint(&self, id: Uuid) -> Result<Option<Entrypoint>> {
        self.db.get_entrypoint(id)
    }

    pub fn create_entrypoint(&self, name: String, path: String) -> Result<Entrypoint> {
        let entry_path = Path::new(&path);
        if !entry_path.exists() {
            return Err(anyhow!("Entrypoint path does not exist: {}", path));
        }

        let now = Utc::now();
        let entrypoint = Entrypoint {
            id: Uuid::new_v4(),
            name,
            path,
            created_at: now,
            updated_at: now,
        };
        self.db.insert_entrypoint(&entrypoint)?;
        Ok(entrypoint)
    }

    pub fn delete_entrypoint(&self, id: Uuid) -> Result<()> {
        let layers = self.db.list_layers(Some(id))?;
        if !layers.is_empty() {
            return Err(anyhow!("Cannot delete entrypoint: layers depend on it"));
        }
        let mounts = self.db.list_user_mounts(Some(id))?;
        if !mounts.is_empty() {
            return Err(anyhow!("Cannot delete entrypoint: user mounts depend on it"));
        }
        self.db.delete_entrypoint(id)
    }

    pub fn list_layers(&self, entrypoint_id: Option<Uuid>) -> Result<Vec<Layer>> {
        self.db.list_layers(entrypoint_id)
    }

    pub fn list_layers_with_status(
        &self,
        entrypoint_id: Option<Uuid>,
    ) -> Result<Vec<LayerWithStatus>> {
        let layers = self.db.list_layers(entrypoint_id)?;
        let mut result = Vec::with_capacity(layers.len());
        for layer in layers {
            let status = self.mount_status(&layer.mount_path);
            result.push(LayerWithStatus {
                layer,
                mount_status: status,
            });
        }
        Ok(result)
    }

    pub fn get_layer(&self, id: Uuid) -> Result<Option<Layer>> {
        self.db.get_layer(id)
    }

    pub fn create_layer(
        &self,
        name: String,
        entrypoint_id: Uuid,
        parent_id: Option<Uuid>,
        mount_path: Option<String>,
    ) -> Result<Layer> {
        let entrypoint = self
            .db
            .get_entrypoint(entrypoint_id)?
            .ok_or_else(|| anyhow!("Entrypoint not found"))?;

        if let Some(parent_id) = parent_id {
            let parent = self
                .db
                .get_layer(parent_id)?
                .ok_or_else(|| anyhow!("Parent layer not found"))?;
            if parent.entrypoint_id != entrypoint.id {
                return Err(anyhow!("Parent layer must belong to the same entrypoint"));
            }
        }

        let now = Utc::now();
        let id = Uuid::new_v4();
        let upper_dir = format!("{}/layers/{}/upper", self.config.data_dir, id);
        let work_dir = format!("{}/layers/{}/work", self.config.data_dir, id);
        let default_mount_path =
            format!("{}/mounts/layers/{}", self.config.data_dir, id);
        let mount_path = mount_path.unwrap_or(default_mount_path);

        fs::create_dir_all(&upper_dir)?;
        fs::create_dir_all(&work_dir)?;
        fs::create_dir_all(&mount_path)?;

        let layer = Layer {
            id,
            name,
            entrypoint_id,
            parent_id,
            upper_dir,
            work_dir,
            mount_path,
            created_at: now,
            updated_at: now,
        };
        self.db.insert_layer(&layer)?;
        self.mount_layer(&layer)?;
        Ok(layer)
    }

    pub fn delete_layer(&self, id: Uuid) -> Result<()> {
        if self.db.count_child_layers(id)? > 0 {
            return Err(anyhow!("Cannot delete layer: child layers depend on it"));
        }
        if self.db.count_attached_user_mounts(id)? > 0 {
            return Err(anyhow!("Cannot delete layer: user mounts are attached"));
        }
        if let Some(layer) = self.db.get_layer(id)? {
            let _ = self.unmount_layer(&layer);
        }
        self.db.delete_layer(id)
    }

    pub fn mount_layer(&self, layer: &Layer) -> Result<()> {
        let export_path = self.layer_export_path(layer.id);
        self.nfs_registry.register(NfsExport {
            mount_id: layer.id.to_string(),
            export_path: export_path.clone(),
            local_path: layer.mount_path.clone(),
        });
        let spec = MountSpec {
            host: self.config.nfs_host.clone(),
            port: self.config.nfs_port,
            export_path,
            local_path: layer.mount_path.clone(),
            read_only: false,
        };
        ensure_mount(&spec)
    }

    pub fn unmount_layer(&self, layer: &Layer) -> Result<()> {
        crate::nfs::unmount(&layer.mount_path)
    }

    pub fn list_user_mounts(&self, entrypoint_id: Option<Uuid>) -> Result<Vec<UserMount>> {
        self.db.list_user_mounts(entrypoint_id)
    }

    pub fn list_user_mounts_with_status(
        &self,
        entrypoint_id: Option<Uuid>,
    ) -> Result<Vec<UserMountWithStatus>> {
        let mounts = self.db.list_user_mounts(entrypoint_id)?;
        let mut result = Vec::with_capacity(mounts.len());
        for user_mount in mounts {
            let status = self.mount_status(&user_mount.mount_path);
            result.push(UserMountWithStatus {
                user_mount,
                mount_status: status,
                sync_state: None,
            });
        }
        Ok(result)
    }

    pub fn get_user_mount(&self, id: Uuid) -> Result<Option<UserMount>> {
        self.db.get_user_mount(id)
    }

    pub fn create_user_mount(
        &self,
        name: String,
        entrypoint_id: Uuid,
        mount_path: String,
        attached_layer_id: Option<Uuid>,
    ) -> Result<UserMount> {
        let entrypoint = self
            .db
            .get_entrypoint(entrypoint_id)?
            .ok_or_else(|| anyhow!("Entrypoint not found"))?;

        if let Some(layer_id) = attached_layer_id {
            let layer = self
                .db
                .get_layer(layer_id)?
                .ok_or_else(|| anyhow!("Layer not found"))?;
            if layer.entrypoint_id != entrypoint.id {
                return Err(anyhow!("Attached layer must belong to the same entrypoint"));
            }
        }

        let now = Utc::now();
        let id = Uuid::new_v4();
        let upper_dir = format!("{}/usermounts/{}/upper", self.config.data_dir, id);
        let work_dir = format!("{}/usermounts/{}/work", self.config.data_dir, id);

        fs::create_dir_all(&upper_dir)?;
        fs::create_dir_all(&work_dir)?;
        fs::create_dir_all(&mount_path)?;

        let user_mount = UserMount {
            id,
            name,
            entrypoint_id,
            attached_layer_id,
            upper_dir,
            work_dir,
            mount_path,
            created_at: now,
            updated_at: now,
        };
        self.db.insert_user_mount(&user_mount)?;
        self.mount_user_mount(&user_mount)?;
        if let Some(layer_id) = user_mount.attached_layer_id {
            if let Some(layer) = self.db.get_layer(layer_id)? {
                self.mount_layer(&layer)?;
                reconcile_trees(Path::new(&layer.mount_path), Path::new(&user_mount.mount_path))?;
            }
        }
        Ok(user_mount)
    }

    pub fn delete_user_mount(&self, id: Uuid) -> Result<()> {
        if let Some(user_mount) = self.db.get_user_mount(id)? {
            let _ = self.unmount_user_mount(&user_mount);
        }
        self.db.delete_user_mount(id)
    }

    pub fn attach_layer(&self, user_mount_id: Uuid, layer_id: Option<Uuid>) -> Result<()> {
        let user_mount = self
            .db
            .get_user_mount(user_mount_id)?
            .ok_or_else(|| anyhow!("User mount not found"))?;

        if let Some(layer_id) = layer_id {
            let layer = self
                .db
                .get_layer(layer_id)?
                .ok_or_else(|| anyhow!("Layer not found"))?;
            if layer.entrypoint_id != user_mount.entrypoint_id {
                return Err(anyhow!("Layer must belong to the same entrypoint"));
            }
        }

        self.db.update_user_mount_layer(user_mount_id, layer_id)?;
        if let Some(layer_id) = layer_id {
            if let (Some(layer), Some(user_mount)) = (
                self.db.get_layer(layer_id)?,
                self.db.get_user_mount(user_mount_id)?,
            ) {
                self.mount_layer(&layer)?;
                reconcile_trees(Path::new(&layer.mount_path), Path::new(&user_mount.mount_path))?;
            }
        }
        Ok(())
    }

    pub fn mount_user_mount(&self, user_mount: &UserMount) -> Result<()> {
        let export_path = self.user_mount_export_path(user_mount.id);
        self.nfs_registry.register(NfsExport {
            mount_id: user_mount.id.to_string(),
            export_path: export_path.clone(),
            local_path: user_mount.mount_path.clone(),
        });
        let spec = MountSpec {
            host: self.config.nfs_host.clone(),
            port: self.config.nfs_port,
            export_path,
            local_path: user_mount.mount_path.clone(),
            read_only: false,
        };
        ensure_mount(&spec)
    }

    pub fn unmount_user_mount(&self, user_mount: &UserMount) -> Result<()> {
        crate::nfs::unmount(&user_mount.mount_path)
    }

    pub fn remount_all(&self) -> Result<()> {
        let layers = self.db.list_layers(None)?;
        let sorted_layers = self.sort_layers_by_dependency(layers);
        for layer in sorted_layers {
            let _ = self.mount_layer(&layer);
        }

        let user_mounts = self.db.list_user_mounts(None)?;
        for user_mount in user_mounts {
            let _ = self.mount_user_mount(&user_mount);
        }
        Ok(())
    }

    pub fn handle_nfs_op(&self, op: NfsOp) -> Result<()> {
        let source_root = self.mount_path_for_id(&op.mount_id)?;
        let Some(layer_id) = self.resolve_layer_id(&op.mount_id)? else {
            return Ok(());
        };

        let change = self.map_op_to_change(&op);
        let suppression_key = format!(
            "{}:{}:{:?}",
            op.mount_id,
            op.relative_path,
            op.kind
        );
        if self.replication.is_suppressed(&suppression_key) {
            return Ok(());
        }
        let target_paths = self.mount_paths_for_layer(layer_id)?;
        for target_root in target_paths {
            if target_root == source_root {
                continue;
            }
            let target_mount_id = self.mount_id_for_path(&target_root)?;
            let target_key = format!(
                "{}:{}:{:?}",
                target_mount_id,
                op.relative_path,
                op.kind
            );
            self.replication.suppress(target_key);
            replay_change(&change, Path::new(&source_root), Path::new(&target_root))?;
        }

        Ok(())
    }

    fn mount_status(&self, mount_path: &str) -> MountStatus {
        match is_mounted(mount_path) {
            Ok(true) => MountStatus::Mounted,
            Ok(false) => MountStatus::Unmounted,
            Err(_) => MountStatus::Error,
        }
    }

    fn map_op_to_change(&self, op: &NfsOp) -> crate::models::FileChange {
        use crate::models::FileChangeType;
        let change_type = match op.kind {
            NfsOpKind::Create | NfsOpKind::Write | NfsOpKind::Mkdir | NfsOpKind::Setattr => {
                FileChangeType::Modify
            }
            NfsOpKind::Rename => FileChangeType::Modify,
            NfsOpKind::Remove | NfsOpKind::Rmdir => FileChangeType::Delete,
        };

        crate::models::FileChange {
            change_type,
            relative_path: op.relative_path.clone(),
            timestamp: op.timestamp,
            source_uid: op.source_uid,
            source_gid: op.source_gid,
        }
    }

    fn resolve_layer_id(&self, mount_id: &str) -> Result<Option<Uuid>> {
        if let Ok(id) = Uuid::parse_str(mount_id) {
            if let Some(user_mount) = self.db.get_user_mount(id)? {
                return Ok(user_mount.attached_layer_id);
            }
            if self.db.get_layer(id)?.is_some() {
                return Ok(Some(id));
            }
        }
        Ok(None)
    }

    fn mount_path_for_id(&self, mount_id: &str) -> Result<String> {
        if let Some(export) = self.nfs_registry.get(mount_id) {
            return Ok(export.local_path);
        }
        if let Ok(id) = Uuid::parse_str(mount_id) {
            if let Some(user_mount) = self.db.get_user_mount(id)? {
                return Ok(user_mount.mount_path);
            }
            if let Some(layer) = self.db.get_layer(id)? {
                return Ok(layer.mount_path);
            }
        }
        Err(anyhow!("Unknown mount id: {}", mount_id))
    }

    fn mount_paths_for_layer(&self, layer_id: Uuid) -> Result<Vec<String>> {
        let mut paths = Vec::new();
        if let Some(layer) = self.db.get_layer(layer_id)? {
            paths.push(layer.mount_path);
        }
        let user_mounts = self.db.list_user_mounts_by_layer(layer_id)?;
        for mount in user_mounts {
            paths.push(mount.mount_path);
        }
        Ok(paths)
    }

    fn mount_id_for_path(&self, mount_path: &str) -> Result<String> {
        let exports = self.nfs_registry.list();
        for export in exports {
            if export.local_path == mount_path {
                return Ok(export.mount_id);
            }
        }
        if let Some(layer) = self
            .db
            .list_layers(None)?
            .into_iter()
            .find(|layer| layer.mount_path == mount_path)
        {
            return Ok(layer.id.to_string());
        }
        if let Some(mount) = self
            .db
            .list_user_mounts(None)?
            .into_iter()
            .find(|mount| mount.mount_path == mount_path)
        {
            return Ok(mount.id.to_string());
        }
        Err(anyhow!("Unknown mount path: {}", mount_path))
    }

    fn export_path(&self, suffix: &str) -> String {
        let root = self.config.nfs_export_root.trim_end_matches('/');
        if root.is_empty() {
            format!("/{}", suffix)
        } else {
            format!("{}/{}", root, suffix)
        }
    }

    fn layer_export_path(&self, id: Uuid) -> String {
        self.export_path(&format!("layers/{}", id))
    }

    fn user_mount_export_path(&self, id: Uuid) -> String {
        self.export_path(&format!("mounts/{}", id))
    }

    fn sort_layers_by_dependency(&self, layers: Vec<Layer>) -> Vec<Layer> {
        let mut result = Vec::new();
        let mut remaining: std::collections::HashSet<Uuid> =
            layers.iter().map(|layer| layer.id).collect();
        let layer_map: std::collections::HashMap<Uuid, Layer> =
            layers.into_iter().map(|layer| (layer.id, layer)).collect();

        while !remaining.is_empty() {
            let mut progressed = false;
            let ids: Vec<Uuid> = remaining.iter().copied().collect();
            for id in ids {
                let layer = layer_map.get(&id).expect("layer not found");
                if layer.parent_id.is_none() || !remaining.contains(&layer.parent_id.unwrap()) {
                    result.push(layer.clone());
                    remaining.remove(&id);
                    progressed = true;
                }
            }
            if !progressed {
                break;
            }
        }

        result
    }
}
