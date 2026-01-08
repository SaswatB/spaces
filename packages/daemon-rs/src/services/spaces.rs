use anyhow::{anyhow, Result};
use chrono::Utc;
use std::fs;
use std::path::{Path, PathBuf};

use crate::config::Config;
use crate::db::Database;
use crate::models::{
    Entrypoint, Layer, LayerDiffEntry, LayerDiffType, LayerWithStatus, MountStatus, UserMount,
    UserMountWithStatus,
};
use crate::nfs::{ensure_mount, is_mounted, MountSpec, NfsExport, NfsOp, NfsOpKind, NfsRegistry};
use crate::overlay::{is_whiteout_marker, OPAQUE_MARKER};
use crate::replication::{reconcile_trees, replay_change};
use crate::state::AppState;
use tracing::info;
use uuid::Uuid;

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

    pub fn get_entrypoint(&self, id: &str) -> Result<Option<Entrypoint>> {
        self.db.get_entrypoint(id)
    }

    pub fn create_entrypoint(&self, name: Option<String>, path: String) -> Result<Entrypoint> {
        let entry_path = Path::new(&path);
        if !entry_path.exists() {
            return Err(anyhow!("Entrypoint path does not exist: {}", path));
        }

        let now = Utc::now();
        let inferred_name = Self::infer_entrypoint_name(entry_path)?;
        let name = name
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
            .unwrap_or(inferred_name);
        let entrypoint = Entrypoint {
            id: self.generate_entrypoint_id()?,
            name,
            path,
            created_at: now,
            updated_at: now,
        };
        self.db.insert_entrypoint(&entrypoint)?;
        Ok(entrypoint)
    }

    pub fn delete_entrypoint(&self, id: &str) -> Result<()> {
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

    pub fn list_layers(&self, entrypoint_id: Option<&str>) -> Result<Vec<Layer>> {
        self.db.list_layers(entrypoint_id)
    }

    pub fn list_layers_with_status(
        &self,
        entrypoint_id: Option<&str>,
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

    pub fn get_layer(&self, id: &str) -> Result<Option<Layer>> {
        self.db.get_layer(id)
    }

    pub fn layer_diff(&self, id: &str) -> Result<Vec<LayerDiffEntry>> {
        let layer = self
            .db
            .get_layer(id)?
            .ok_or_else(|| anyhow!("Layer not found"))?;
        let entrypoint = self
            .db
            .get_entrypoint(&layer.entrypoint_id)?
            .ok_or_else(|| anyhow!("Entrypoint not found"))?;

        let upper_root = PathBuf::from(&layer.upper_dir);
        let entry_root = PathBuf::from(&entrypoint.path);
        let mut entries = Vec::new();
        if upper_root.exists() {
            self.collect_layer_diff(&upper_root, &upper_root, &entry_root, &mut entries)?;
        }
        entries.sort_by(|a, b| a.path.cmp(&b.path));
        Ok(entries)
    }

    pub fn create_layer(
        &self,
        name: Option<String>,
        entrypoint_id: String,
        parent_id: Option<String>,
        mount_path: Option<String>,
    ) -> Result<Layer> {
        info!(layer_name = ?name, %entrypoint_id, "Creating layer");
        let entrypoint = self
            .db
            .get_entrypoint(&entrypoint_id)?
            .ok_or_else(|| anyhow!("Entrypoint not found"))?;

        if let Some(parent_id) = parent_id.as_deref() {
            let parent = self
                .db
                .get_layer(parent_id)?
                .ok_or_else(|| anyhow!("Parent layer not found"))?;
            if parent.entrypoint_id != entrypoint.id {
                return Err(anyhow!("Parent layer must belong to the same entrypoint"));
            }
        }

        let now = Utc::now();
        let id = self.generate_layer_id()?;
        let layer_name = self.generate_layer_name(&entrypoint, name)?;
        let upper_dir = format!("{}/layers/{}/upper", self.config.data_dir, id);
        let work_dir = format!("{}/layers/{}/work", self.config.data_dir, id);
        let default_mount_path = format!(
            "{}/mounts/layers/{}/{}",
            self.config.data_dir,
            entrypoint.id,
            Self::sanitize_mount_component(&layer_name),
        );
        let mount_path = mount_path.unwrap_or(default_mount_path);

        fs::create_dir_all(&upper_dir)?;
        fs::create_dir_all(&work_dir)?;
        fs::create_dir_all(&mount_path)?;

        let layer = Layer {
            id,
            name: layer_name,
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

    pub fn delete_layer(&self, id: &str) -> Result<()> {
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
        info!(layer_id = %layer.id, mount_path = %layer.mount_path, "Mounting layer");
        let export_path = self.layer_export_path(&layer.id);
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
        info!(layer_id = %layer.id, mount_path = %layer.mount_path, "Unmounting layer");
        crate::nfs::unmount(&layer.mount_path)
    }

    pub fn list_user_mounts(&self, entrypoint_id: Option<&str>) -> Result<Vec<UserMount>> {
        self.db.list_user_mounts(entrypoint_id)
    }

    pub fn list_user_mounts_with_status(
        &self,
        entrypoint_id: Option<&str>,
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

    pub fn get_user_mount(&self, id: &str) -> Result<Option<UserMount>> {
        self.db.get_user_mount(id)
    }

    pub fn create_user_mount(
        &self,
        name: String,
        entrypoint_id: String,
        mount_path: String,
        attached_layer_id: Option<String>,
    ) -> Result<UserMount> {
        info!(mount_name = %name, %entrypoint_id, %mount_path, "Creating user mount");
        let entrypoint = self
            .db
            .get_entrypoint(&entrypoint_id)?
            .ok_or_else(|| anyhow!("Entrypoint not found"))?;

        if let Some(layer_id) = attached_layer_id.as_deref() {
            let layer = self
                .db
                .get_layer(layer_id)?
                .ok_or_else(|| anyhow!("Layer not found"))?;
            if layer.entrypoint_id != entrypoint.id {
                return Err(anyhow!("Attached layer must belong to the same entrypoint"));
            }
        }

        let now = Utc::now();
        let id = self.generate_user_mount_id()?;
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
        if let Some(layer_id) = user_mount.attached_layer_id.as_ref() {
            if let Some(layer) = self.db.get_layer(layer_id)? {
                self.mount_layer(&layer)?;
                reconcile_trees(Path::new(&layer.mount_path), Path::new(&user_mount.mount_path))?;
            }
        }
        Ok(user_mount)
    }

    pub fn delete_user_mount(&self, id: &str) -> Result<()> {
        if let Some(user_mount) = self.db.get_user_mount(id)? {
            let _ = self.unmount_user_mount(&user_mount);
        }
        self.db.delete_user_mount(id)
    }

    pub fn attach_layer(&self, user_mount_id: String, layer_id: Option<String>) -> Result<()> {
        let user_mount = self
            .db
            .get_user_mount(&user_mount_id)?
            .ok_or_else(|| anyhow!("User mount not found"))?;

        if let Some(layer_id) = layer_id.as_deref() {
            let layer = self
                .db
                .get_layer(layer_id)?
                .ok_or_else(|| anyhow!("Layer not found"))?;
            if layer.entrypoint_id != user_mount.entrypoint_id {
                return Err(anyhow!("Layer must belong to the same entrypoint"));
            }
        }

        self.db
            .update_user_mount_layer(&user_mount_id, layer_id.as_deref())?;
        if let Some(layer_id) = layer_id.as_deref() {
            if let (Some(layer), Some(user_mount)) = (
                self.db.get_layer(layer_id)?,
                self.db.get_user_mount(&user_mount_id)?,
            ) {
                self.mount_layer(&layer)?;
                reconcile_trees(Path::new(&layer.mount_path), Path::new(&user_mount.mount_path))?;
            }
        }
        Ok(())
    }

    pub fn mount_user_mount(&self, user_mount: &UserMount) -> Result<()> {
        info!(mount_id = %user_mount.id, mount_path = %user_mount.mount_path, "Mounting user mount");
        let export_path = self.user_mount_export_path(&user_mount.id);
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
        info!(mount_id = %user_mount.id, mount_path = %user_mount.mount_path, "Unmounting user mount");
        crate::nfs::unmount(&user_mount.mount_path)
    }

    pub fn remount_all(&self) -> Result<()> {
        info!("Remounting all layers and user mounts");
        let layers = self.db.list_layers(None)?;
        let sorted_layers = self.sort_layers_by_dependency(layers);
        for layer in sorted_layers {
            if let Err(err) = self.mount_layer(&layer) {
                info!(layer_id = %layer.id, error = %err, "Failed to mount layer during remount");
                continue;
            }
            match is_mounted(&layer.mount_path) {
                Ok(true) => info!(layer_id = %layer.id, "Layer mounted"),
                Ok(false) => info!(layer_id = %layer.id, "Layer mount missing after mount attempt"),
                Err(err) => info!(layer_id = %layer.id, error = %err, "Layer mount check failed"),
            }
        }

        let user_mounts = self.db.list_user_mounts(None)?;
        for user_mount in user_mounts {
            if let Err(err) = self.mount_user_mount(&user_mount) {
                info!(mount_id = %user_mount.id, error = %err, "Failed to mount user mount during remount");
                continue;
            }
            match is_mounted(&user_mount.mount_path) {
                Ok(true) => info!(mount_id = %user_mount.id, "User mount mounted"),
                Ok(false) => info!(mount_id = %user_mount.id, "User mount missing after mount attempt"),
                Err(err) => info!(mount_id = %user_mount.id, error = %err, "User mount check failed"),
            }
        }
        Ok(())
    }

    pub fn unmount_all(&self) -> Result<()> {
        info!("Unmounting all layers and user mounts");
        let user_mounts = self.db.list_user_mounts(None)?;
        for user_mount in user_mounts {
            if let Err(err) = self.unmount_user_mount(&user_mount) {
                info!(mount_id = %user_mount.id, error = %err, "Failed to unmount user mount");
            }
        }

        let layers = self.db.list_layers(None)?;
        for layer in layers {
            if let Err(err) = self.unmount_layer(&layer) {
                info!(layer_id = %layer.id, error = %err, "Failed to unmount layer");
            }
        }
        Ok(())
    }

    pub fn handle_nfs_op(&self, op: NfsOp) -> Result<()> {
        info!(mount_id = %op.mount_id, path = %op.relative_path, kind = ?op.kind, "Replicating NFS op");
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
        let target_paths = self.mount_paths_for_layer(&layer_id)?;
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

    fn resolve_layer_id(&self, mount_id: &str) -> Result<Option<String>> {
        if let Some(user_mount) = self.db.get_user_mount(mount_id)? {
            return Ok(user_mount.attached_layer_id);
        }
        if self.db.get_layer(mount_id)?.is_some() {
            return Ok(Some(mount_id.to_string()));
        }
        Ok(None)
    }

    fn mount_path_for_id(&self, mount_id: &str) -> Result<String> {
        if let Some(export) = self.nfs_registry.get(mount_id) {
            return Ok(export.local_path);
        }
        if let Some(user_mount) = self.db.get_user_mount(mount_id)? {
            return Ok(user_mount.mount_path);
        }
        if let Some(layer) = self.db.get_layer(mount_id)? {
            return Ok(layer.mount_path);
        }
        Err(anyhow!("Unknown mount id: {}", mount_id))
    }

    fn mount_paths_for_layer(&self, layer_id: &str) -> Result<Vec<String>> {
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

    fn layer_export_path(&self, id: &str) -> String {
        self.export_path(&format!("layers/{}", id))
    }

    fn user_mount_export_path(&self, id: &str) -> String {
        self.export_path(&format!("mounts/{}", id))
    }

    fn sort_layers_by_dependency(&self, layers: Vec<Layer>) -> Vec<Layer> {
        let mut result = Vec::new();
        let mut remaining: std::collections::HashSet<String> =
            layers.iter().map(|layer| layer.id.clone()).collect();
        let layer_map: std::collections::HashMap<String, Layer> =
            layers.into_iter().map(|layer| (layer.id.clone(), layer)).collect();

        while !remaining.is_empty() {
            let mut progressed = false;
            let ids: Vec<String> = remaining.iter().cloned().collect();
            for id in ids {
                let layer = layer_map.get(&id).expect("layer not found");
                if layer.parent_id.is_none()
                    || !remaining.contains(layer.parent_id.as_ref().expect("parent missing"))
                {
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

    fn collect_layer_diff(
        &self,
        upper_root: &Path,
        current: &Path,
        entry_root: &Path,
        entries: &mut Vec<LayerDiffEntry>,
    ) -> Result<()> {
        let read_dir = fs::read_dir(current)?;
        for entry in read_dir.flatten() {
            let path = entry.path();
            let rel = path.strip_prefix(upper_root).unwrap_or(&path);
            let name = entry.file_name();
            let name = name.to_string_lossy().to_string();
            if name == OPAQUE_MARKER {
                continue;
            }
            if let Some(wh) = is_whiteout_marker(&name) {
                let delete_path = match rel.parent() {
                    Some(parent) if !parent.as_os_str().is_empty() => parent.join(wh),
                    _ => PathBuf::from(wh),
                };
                entries.push(LayerDiffEntry {
                    path: delete_path.to_string_lossy().to_string(),
                    change_type: LayerDiffType::Delete,
                });
                continue;
            }

            let entrypoint_path = entry_root.join(rel);
            let change_type = if entrypoint_path.exists() {
                LayerDiffType::Modify
            } else {
                LayerDiffType::Add
            };
            entries.push(LayerDiffEntry {
                path: rel.to_string_lossy().to_string(),
                change_type,
            });

            if path.is_dir() {
                self.collect_layer_diff(upper_root, &path, entry_root, entries)?;
            }
        }
        Ok(())
    }

    fn infer_entrypoint_name(path: &Path) -> Result<String> {
        if let Some(name) = path.file_name().and_then(|value| value.to_str()) {
            return Ok(name.to_string());
        }
        Err(anyhow!("Unable to infer entrypoint name from path: {}", path.display()))
    }

    fn sanitize_mount_component(name: &str) -> String {
        name.replace('/', "_").replace('\\', "_")
    }

    fn generate_layer_name(&self, entrypoint: &Entrypoint, name: Option<String>) -> Result<String> {
        if let Some(value) = name {
            let trimmed = value.trim();
            if !trimmed.is_empty() {
                return Ok(trimmed.to_string());
            }
        }

        let existing = self
            .db
            .list_layers(Some(&entrypoint.id))?
            .into_iter()
            .map(|layer| layer.name)
            .collect::<std::collections::HashSet<_>>();

        let mut index = existing.len() + 1;
        loop {
            let candidate = format!("{}:{}", entrypoint.name, index);
            if !existing.contains(&candidate) {
                return Ok(candidate);
            }
            index += 1;
        }
    }

    fn generate_entrypoint_id(&self) -> Result<String> {
        self.generate_prefixed_id("ep", |id| Ok(self.db.get_entrypoint(id)?.is_some()))
    }

    fn generate_layer_id(&self) -> Result<String> {
        self.generate_prefixed_id("lyr", |id| Ok(self.db.get_layer(id)?.is_some()))
    }

    fn generate_user_mount_id(&self) -> Result<String> {
        self.generate_prefixed_id("mnt", |id| Ok(self.db.get_user_mount(id)?.is_some()))
    }

    fn generate_prefixed_id<F>(&self, prefix: &str, mut exists: F) -> Result<String>
    where
        F: FnMut(&str) -> Result<bool>,
    {
        let modulo = 10_u128.pow(10);
        loop {
            let raw = Uuid::new_v4().as_u128() % modulo;
            let id = format!("{}_{}", prefix, format!("{:010}", raw));
            if !exists(&id)? {
                return Ok(id);
            }
        }
    }
}
