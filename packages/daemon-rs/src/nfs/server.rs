use anyhow::Result;
use async_trait::async_trait;
use nfsserve::fs_util;
use nfsserve::nfs::{
    fattr3, fileid3, filename3, nfsstat3, nfstime3, nfspath3, sattr3, specdata3,
};
use nfsserve::tcp::{NFSTcp, NFSTcpListener};
use nfsserve::vfs::{DirEntry, NFSFileSystem, ReadDirResult, VFSCapabilities};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;
use tokio::fs::{self, OpenOptions};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};

use crate::nfs::{handle_op, NfsOp, NfsOpKind};
use crate::overlay::{OverlayEngine, OverlayView};
use crate::state::AppState;

#[derive(Clone, Debug, PartialEq, Eq)]
enum MountKind {
    User,
    Layer,
}

#[derive(Clone, Debug)]
enum NodeRef {
    Root,
    ExportRoot,
    MountsDir,
    LayersDir,
    MountRoot { mount_id: String, kind: MountKind },
    MountPath {
        mount_id: String,
        kind: MountKind,
        relative: PathBuf,
    },
}

pub struct SpacesNfs {
    state: AppState,
    overlay: OverlayEngine,
    export_root_name: String,
    id_map: Mutex<HashMap<fileid3, NodeRef>>,
    path_map: Mutex<HashMap<String, fileid3>>,
}

impl SpacesNfs {
    pub fn new(state: AppState) -> Self {
        let export_root_name = state
            .config
            .nfs_export_root
            .trim_matches('/')
            .to_string();
        let export_root_name = if export_root_name.is_empty() {
            "spaces".to_string()
        } else {
            export_root_name
        };

        let mut id_map = HashMap::new();
        let mut path_map = HashMap::new();
        let root_id = 1;
        let root_path = "/".to_string();
        id_map.insert(root_id, NodeRef::Root);
        path_map.insert(root_path, root_id);

        Self {
            state,
            overlay: OverlayEngine::new(),
            export_root_name,
            id_map: Mutex::new(id_map),
            path_map: Mutex::new(path_map),
        }
    }

    pub async fn serve(self, bind_addr: &str) -> Result<()> {
        let listener = NFSTcpListener::bind(bind_addr, self).await?;
        listener.handle_forever().await?;
        Ok(())
    }

    fn assign_id(&self, path: &str, node: NodeRef) -> fileid3 {
        let mut path_map = self.path_map.lock().expect("path map lock poisoned");
        if let Some(id) = path_map.get(path) {
            return *id;
        }
        let mut id_map = self.id_map.lock().expect("id map lock poisoned");
        let mut id = fnv_hash(path);
        if id == 1 {
            id = 2;
        }
        while id_map.contains_key(&id) {
            id = id.wrapping_add(1);
            if id == 1 {
                id = id.wrapping_add(1);
            }
        }
        id_map.insert(id, node);
        path_map.insert(path.to_string(), id);
        id
    }

    fn node_for_id(&self, id: fileid3) -> Option<NodeRef> {
        let id_map = self.id_map.lock().expect("id map lock poisoned");
        id_map.get(&id).cloned()
    }

    fn export_root_path(&self) -> String {
        format!("/{}", self.export_root_name)
    }

    fn mount_dir_path(&self, kind: &MountKind) -> String {
        match kind {
            MountKind::User => format!("{}/mounts", self.export_root_path()),
            MountKind::Layer => format!("{}/layers", self.export_root_path()),
        }
    }

    fn mount_root_path(&self, kind: &MountKind, mount_id: &str) -> String {
        format!("{}/{}", self.mount_dir_path(kind), mount_id)
    }

    fn node_path(&self, node: &NodeRef) -> String {
        match node {
            NodeRef::Root => "/".to_string(),
            NodeRef::ExportRoot => self.export_root_path(),
            NodeRef::MountsDir => self.mount_dir_path(&MountKind::User),
            NodeRef::LayersDir => self.mount_dir_path(&MountKind::Layer),
            NodeRef::MountRoot { mount_id, kind } => self.mount_root_path(kind, mount_id),
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let base = self.mount_root_path(kind, mount_id);
                if relative.as_os_str().is_empty() {
                    base
                } else {
                    format!("{}/{}", base, relative.to_string_lossy())
                }
            }
        }
    }

    fn child_path(node_path: &str, child: &str) -> String {
        if node_path.ends_with('/') {
            format!("{}{}", node_path, child)
        } else {
            format!("{}/{}", node_path, child)
        }
    }

    fn resolve_mount_view(&self, kind: &MountKind, mount_id: &str) -> Result<MountView, nfsstat3> {
        let id = uuid::Uuid::parse_str(mount_id).map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
        match kind {
            MountKind::Layer => {
                let layer = self
                    .state
                    .db
                    .get_layer(id)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?
                    .ok_or(nfsstat3::NFS3ERR_NOENT)?;
                let view = self.build_view_for_layer(layer.id)?;
                Ok(MountView {
                    view,
                    writable: true,
                })
            }
            MountKind::User => {
                let user_mount = self
                    .state
                    .db
                    .get_user_mount(id)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?
                    .ok_or(nfsstat3::NFS3ERR_NOENT)?;
                if let Some(layer_id) = user_mount.attached_layer_id {
                    let view = self.build_view_for_layer(layer_id)?;
                    Ok(MountView {
                        view,
                        writable: true,
                    })
                } else {
                    let entrypoint = self
                        .state
                        .db
                    .get_entrypoint(user_mount.entrypoint_id)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?
                    .ok_or(nfsstat3::NFS3ERR_NOENT)?;
                    Ok(MountView {
                        view: OverlayView {
                            entrypoint: PathBuf::from(entrypoint.path),
                            layers: Vec::new(),
                        },
                        writable: false,
                    })
                }
            }
        }
    }

    fn build_view_for_layer(&self, layer_id: uuid::Uuid) -> Result<OverlayView, nfsstat3> {
        let layer = self
            .state
            .db
            .get_layer(layer_id)
            .map_err(|_| nfsstat3::NFS3ERR_IO)?
            .ok_or(nfsstat3::NFS3ERR_NOENT)?;
        let entrypoint = self
            .state
            .db
            .get_entrypoint(layer.entrypoint_id)
            .map_err(|_| nfsstat3::NFS3ERR_IO)?
            .ok_or(nfsstat3::NFS3ERR_NOENT)?;

        let mut layers = Vec::new();
        let mut current = Some(layer);
        while let Some(layer) = current {
            layers.push(PathBuf::from(layer.upper_dir.clone()));
            current = if let Some(parent_id) = layer.parent_id {
                self.state
                    .db
                    .get_layer(parent_id)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?
            } else {
                None
            };
        }

        Ok(OverlayView {
            entrypoint: PathBuf::from(entrypoint.path),
            layers,
        })
    }

    fn ensure_writeable(&self, view: &MountView) -> Result<(), nfsstat3> {
        if view.writable {
            Ok(())
        } else {
            Err(nfsstat3::NFS3ERR_ROFS)
        }
    }

    fn top_layer_path<'a>(view: &'a OverlayView) -> Option<&'a PathBuf> {
        view.layers.first()
    }

    fn mount_op(&self, kind: &MountKind, mount_id: &str, relative: &Path) -> NfsOp {
        NfsOp {
            mount_id: mount_id.to_string(),
            relative_path: relative.to_string_lossy().to_string(),
            kind: match kind {
                MountKind::User => NfsOpKind::Write,
                MountKind::Layer => NfsOpKind::Write,
            },
            source_uid: 0,
            source_gid: 0,
            timestamp: chrono::Utc::now(),
        }
    }

    fn emit_op(&self, kind: MountKind, mount_id: &str, relative: &Path, op_kind: NfsOpKind) {
        let mut op = self.mount_op(&kind, mount_id, relative);
        op.kind = op_kind;
        let _ = handle_op(&self.state, op);
    }

    fn synthetic_dir_attr(fid: fileid3) -> fattr3 {
        let now = SystemTime::now();
        let (seconds, nseconds) = match now.duration_since(SystemTime::UNIX_EPOCH) {
            Ok(duration) => (duration.as_secs() as u32, duration.subsec_nanos()),
            Err(_) => (0, 0),
        };
        fattr3 {
            ftype: nfsserve::nfs::ftype3::NF3DIR,
            mode: 0o755,
            nlink: 2,
            uid: 0,
            gid: 0,
            size: 0,
            used: 0,
            rdev: specdata3::default(),
            fsid: 0,
            fileid: fid,
            atime: nfstime3 {
                seconds,
                nseconds,
            },
            mtime: nfstime3 {
                seconds,
                nseconds,
            },
            ctime: nfstime3 {
                seconds,
                nseconds,
            },
        }
    }
}

#[async_trait]
impl NFSFileSystem for SpacesNfs {
    fn capabilities(&self) -> VFSCapabilities {
        VFSCapabilities::ReadWrite
    }

    fn root_dir(&self) -> fileid3 {
        1
    }

    async fn lookup(&self, dirid: fileid3, filename: &filename3) -> Result<fileid3, nfsstat3> {
        let filename = String::from_utf8_lossy(filename.as_ref()).to_string();
        let node = self.node_for_id(dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;

        let (child_node, child_path) = match node {
            NodeRef::Root => {
                if filename == self.export_root_name {
                    (NodeRef::ExportRoot, self.export_root_path())
                } else {
                    return Err(nfsstat3::NFS3ERR_NOENT);
                }
            }
            NodeRef::ExportRoot => match filename.as_str() {
                "mounts" => (NodeRef::MountsDir, self.mount_dir_path(&MountKind::User)),
                "layers" => (NodeRef::LayersDir, self.mount_dir_path(&MountKind::Layer)),
                _ => return Err(nfsstat3::NFS3ERR_NOENT),
            },
            NodeRef::MountsDir => {
                let mount_id = filename.clone();
                let id = uuid::Uuid::parse_str(&mount_id).map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
                if self
                    .state
                    .db
                    .get_user_mount(id)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?
                    .is_none()
                {
                    return Err(nfsstat3::NFS3ERR_NOENT);
                }
                let node = NodeRef::MountRoot {
                    mount_id: mount_id.clone(),
                    kind: MountKind::User,
                };
                let path = self.mount_root_path(&MountKind::User, &mount_id);
                (node, path)
            }
            NodeRef::LayersDir => {
                let mount_id = filename.clone();
                let id = uuid::Uuid::parse_str(&mount_id).map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
                if self
                    .state
                    .db
                    .get_layer(id)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?
                    .is_none()
                {
                    return Err(nfsstat3::NFS3ERR_NOENT);
                }
                let node = NodeRef::MountRoot {
                    mount_id: mount_id.clone(),
                    kind: MountKind::Layer,
                };
                let path = self.mount_root_path(&MountKind::Layer, &mount_id);
                (node, path)
            }
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                let relative = PathBuf::from(&filename);
                let entries = self.overlay.list_dir(&view.view, "");
                if !entries.iter().any(|name| name == &filename) {
                    return Err(nfsstat3::NFS3ERR_NOENT);
                }
                let node = NodeRef::MountPath {
                    mount_id: mount_id.clone(),
                    kind: kind.clone(),
                    relative: relative.clone(),
                };
                let path = self.mount_root_path(&kind, &mount_id);
                let path = Self::child_path(&path, &filename);
                (node, path)
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                let mut next = relative.clone();
                next.push(&filename);
                let entries = self.overlay.list_dir(&view.view, &relative.to_string_lossy());
                if !entries.iter().any(|name| name == &filename) {
                    return Err(nfsstat3::NFS3ERR_NOENT);
                }
                let node = NodeRef::MountPath {
                    mount_id: mount_id.clone(),
                    kind: kind.clone(),
                    relative: next.clone(),
                };
                let path = self.node_path(&NodeRef::MountPath {
                    mount_id: mount_id.clone(),
                    kind: kind.clone(),
                    relative: relative.clone(),
                });
                let path = Self::child_path(&path, &filename);
                (node, path)
            }
        };

        Ok(self.assign_id(&child_path, child_node))
    }

    async fn getattr(&self, id: fileid3) -> Result<fattr3, nfsstat3> {
        let node = self.node_for_id(id).ok_or(nfsstat3::NFS3ERR_STALE)?;
        match node {
            NodeRef::Root | NodeRef::ExportRoot | NodeRef::MountsDir | NodeRef::LayersDir => {
                Ok(Self::synthetic_dir_attr(id))
            }
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                let dir_path = view.view.entrypoint.clone();
                let meta = fs::metadata(&dir_path)
                    .await
                    .map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
                Ok(fs_util::metadata_to_fattr3(id, &meta))
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                if relative.as_os_str().is_empty() {
                    let dir_path = view.view.entrypoint.clone();
                    let meta = fs::metadata(&dir_path)
                        .await
                        .map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
                    return Ok(fs_util::metadata_to_fattr3(id, &meta));
                }
                if let Some(resolved) = self.overlay.resolve_path(&view.view, &relative.to_string_lossy()) {
                    let meta = fs::metadata(&resolved.source)
                        .await
                        .map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
                    Ok(fs_util::metadata_to_fattr3(id, &meta))
                } else {
                    Err(nfsstat3::NFS3ERR_NOENT)
                }
            }
        }
    }

    async fn setattr(&self, id: fileid3, setattr: sattr3) -> Result<fattr3, nfsstat3> {
        let node = self.node_for_id(id).ok_or(nfsstat3::NFS3ERR_STALE)?;
        match node {
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                self.ensure_writeable(&view)?;
                let relative_str = relative.to_string_lossy().to_string();
                self.overlay
                    .copy_up_if_needed(&view.view, &relative_str)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?;
                let target = Self::top_layer_path(&view.view)
                    .ok_or(nfsstat3::NFS3ERR_ROFS)?
                    .join(&relative);
                fs_util::path_setattr(&target, &setattr).await?;
                let meta = fs::metadata(&target)
                    .await
                    .map_err(|_| nfsstat3::NFS3ERR_NOENT)?;
                self.emit_op(kind, &mount_id, &relative, NfsOpKind::Setattr);
                Ok(fs_util::metadata_to_fattr3(id, &meta))
            }
            _ => Err(nfsstat3::NFS3ERR_INVAL),
        }
    }

    async fn read(
        &self,
        id: fileid3,
        offset: u64,
        count: u32,
    ) -> Result<(Vec<u8>, bool), nfsstat3> {
        let node = self.node_for_id(id).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let (view, relative) = match node {
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };

        let relative_str = relative.to_string_lossy().to_string();
        let resolved = self
            .overlay
            .resolve_path(&view.view, &relative_str)
            .ok_or(nfsstat3::NFS3ERR_NOENT)?;
        let mut file = fs::File::open(&resolved.source)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        file.seek(std::io::SeekFrom::Start(offset))
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let mut buf = vec![0u8; count as usize];
        let read = file.read(&mut buf).await.map_err(|_| nfsstat3::NFS3ERR_IO)?;
        buf.truncate(read);
        let eof = read < count as usize;
        Ok((buf, eof))
    }

    async fn write(
        &self,
        id: fileid3,
        offset: u64,
        data: &[u8],
    ) -> Result<fattr3, nfsstat3> {
        let node = self.node_for_id(id).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let (view, relative, mount_id, kind) = match node {
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative, mount_id, kind)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };
        self.ensure_writeable(&view)?;
        let relative_str = relative.to_string_lossy().to_string();
        self.overlay
            .copy_up_if_needed(&view.view, &relative_str)
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;

        let target = Self::top_layer_path(&view.view)
            .ok_or(nfsstat3::NFS3ERR_ROFS)?
            .join(&relative);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .await
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        }
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .read(true)
            .open(&target)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        file.seek(std::io::SeekFrom::Start(offset))
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        file.write_all(data)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let meta = file.metadata().await.map_err(|_| nfsstat3::NFS3ERR_IO)?;
        self.emit_op(kind, &mount_id, &relative, NfsOpKind::Write);
        Ok(fs_util::metadata_to_fattr3(id, &meta))
    }

    async fn create(
        &self,
        dirid: fileid3,
        filename: &filename3,
        _attr: sattr3,
    ) -> Result<(fileid3, fattr3), nfsstat3> {
        let node = self.node_for_id(dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let filename = String::from_utf8_lossy(filename.as_ref()).to_string();
        let (view, mut relative, mount_id, kind) = match node {
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, PathBuf::new(), mount_id, kind)
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative, mount_id, kind)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };
        self.ensure_writeable(&view)?;

        relative.push(&filename);
        let target = Self::top_layer_path(&view.view)
            .ok_or(nfsstat3::NFS3ERR_ROFS)?
            .join(&relative);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .await
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        }
        let _ = OpenOptions::new()
            .create(true)
            .write(true)
            .open(&target)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let meta = fs::metadata(&target)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let node = NodeRef::MountPath {
            mount_id: mount_id.clone(),
            kind: kind.clone(),
            relative: relative.clone(),
        };
        let path = self.node_path(&node);
        let id = self.assign_id(&path, node);
        self.emit_op(kind, &mount_id, &relative, NfsOpKind::Create);
        Ok((id, fs_util::metadata_to_fattr3(id, &meta)))
    }

    async fn create_exclusive(
        &self,
        dirid: fileid3,
        filename: &filename3,
    ) -> Result<fileid3, nfsstat3> {
        let (id, _) = self
            .create(dirid, filename, sattr3::default())
            .await?;
        Ok(id)
    }

    async fn mkdir(
        &self,
        dirid: fileid3,
        dirname: &filename3,
    ) -> Result<(fileid3, fattr3), nfsstat3> {
        let node = self.node_for_id(dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let dirname = String::from_utf8_lossy(dirname.as_ref()).to_string();
        let (view, mut relative, mount_id, kind) = match node {
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, PathBuf::new(), mount_id, kind)
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative, mount_id, kind)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };
        self.ensure_writeable(&view)?;

        relative.push(&dirname);
        let target = Self::top_layer_path(&view.view)
            .ok_or(nfsstat3::NFS3ERR_ROFS)?
            .join(&relative);
        fs::create_dir_all(&target)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let meta = fs::metadata(&target)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let node = NodeRef::MountPath {
            mount_id: mount_id.clone(),
            kind: kind.clone(),
            relative: relative.clone(),
        };
        let path = self.node_path(&node);
        let id = self.assign_id(&path, node);
        self.emit_op(kind, &mount_id, &relative, NfsOpKind::Mkdir);
        Ok((id, fs_util::metadata_to_fattr3(id, &meta)))
    }

    async fn remove(&self, dirid: fileid3, filename: &filename3) -> Result<(), nfsstat3> {
        let node = self.node_for_id(dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let filename = String::from_utf8_lossy(filename.as_ref()).to_string();
        let (view, mut relative, mount_id, kind) = match node {
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, PathBuf::new(), mount_id, kind)
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative, mount_id, kind)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };
        self.ensure_writeable(&view)?;
        relative.push(&filename);
        let relative_str = relative.to_string_lossy().to_string();

        let target = Self::top_layer_path(&view.view)
            .ok_or(nfsstat3::NFS3ERR_ROFS)?
            .join(&relative);
        if target.exists() {
            if target.is_dir() {
                fs::remove_dir_all(&target)
                    .await
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?;
            } else {
                fs::remove_file(&target)
                    .await
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?;
            }
        } else {
            self.overlay
                .mark_whiteout(&view.view, &relative_str)
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        }
        self.emit_op(kind, &mount_id, &relative, NfsOpKind::Remove);
        Ok(())
    }

    async fn rename(
        &self,
        from_dirid: fileid3,
        from_filename: &filename3,
        to_dirid: fileid3,
        to_filename: &filename3,
    ) -> Result<(), nfsstat3> {
        let from_node = self.node_for_id(from_dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let to_node = self.node_for_id(to_dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let from_filename = String::from_utf8_lossy(from_filename.as_ref()).to_string();
        let to_filename = String::from_utf8_lossy(to_filename.as_ref()).to_string();

        let (view, mut from_relative, mount_id, kind) = match from_node {
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, PathBuf::new(), mount_id, kind)
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative, mount_id, kind)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };

        let mut to_relative = match to_node {
            NodeRef::MountRoot {
                mount_id: target_mount_id,
                kind: target_kind,
            } => {
                if target_mount_id != mount_id || target_kind != kind {
                    return Err(nfsstat3::NFS3ERR_XDEV);
                }
                PathBuf::new()
            }
            NodeRef::MountPath {
                mount_id: target_mount_id,
                kind: target_kind,
                relative,
            } => {
                if target_mount_id != mount_id || target_kind != kind {
                    return Err(nfsstat3::NFS3ERR_XDEV);
                }
                relative
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };

        self.ensure_writeable(&view)?;
        from_relative.push(&from_filename);
        to_relative.push(&to_filename);

        let relative_str = from_relative.to_string_lossy().to_string();
        self.overlay
            .copy_up_if_needed(&view.view, &relative_str)
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;

        let top = Self::top_layer_path(&view.view).ok_or(nfsstat3::NFS3ERR_ROFS)?;
        let from_target = top.join(&from_relative);
        let to_target = top.join(&to_relative);
        if let Some(parent) = to_target.parent() {
            fs::create_dir_all(parent)
                .await
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        }
        if from_target.exists() {
            fs::rename(&from_target, &to_target)
                .await
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        } else {
            if let Some(resolved) = self
                .overlay
                .resolve_path(&view.view, &relative_str)
            {
                if resolved.source.is_file() {
                    fs::copy(&resolved.source, &to_target)
                        .await
                        .map_err(|_| nfsstat3::NFS3ERR_IO)?;
                } else {
                    fs::create_dir_all(&to_target)
                        .await
                        .map_err(|_| nfsstat3::NFS3ERR_IO)?;
                }
                self.overlay
                    .mark_whiteout(&view.view, &relative_str)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?;
            }
        }
        self.emit_op(kind, &mount_id, &from_relative, NfsOpKind::Rename);
        Ok(())
    }

    async fn readdir(
        &self,
        dirid: fileid3,
        start_after: fileid3,
        max_entries: usize,
    ) -> Result<ReadDirResult, nfsstat3> {
        let node = self.node_for_id(dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let mut entries = Vec::new();
        let (names, base_path, node_base) = match node {
            NodeRef::Root => {
                let name = self.export_root_name.clone();
                let node = NodeRef::ExportRoot;
                let path = self.node_path(&node);
                (vec![name], path, node)
            }
            NodeRef::ExportRoot => (
                vec!["layers".to_string(), "mounts".to_string()],
                self.node_path(&NodeRef::ExportRoot),
                NodeRef::ExportRoot,
            ),
            NodeRef::MountsDir => {
                let mounts = self
                    .state
                    .db
                    .list_user_mounts(None)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?;
                let names = mounts.iter().map(|m| m.id.to_string()).collect::<Vec<_>>();
                (names, self.node_path(&NodeRef::MountsDir), NodeRef::MountsDir)
            }
            NodeRef::LayersDir => {
                let layers = self
                    .state
                    .db
                    .list_layers(None)
                    .map_err(|_| nfsstat3::NFS3ERR_IO)?;
                let names = layers.iter().map(|l| l.id.to_string()).collect::<Vec<_>>();
                (names, self.node_path(&NodeRef::LayersDir), NodeRef::LayersDir)
            }
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                let names = self.overlay.list_dir(&view.view, "");
                let base = self.mount_root_path(&kind, &mount_id);
                (names, base, NodeRef::MountRoot { mount_id, kind })
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                let names = self.overlay.list_dir(&view.view, &relative.to_string_lossy());
                let base_node = NodeRef::MountPath {
                    mount_id,
                    kind,
                    relative,
                };
                let base = self.node_path(&base_node);
                (names, base, base_node)
            }
        };

        let mut resolved_entries = Vec::new();
        for name in names {
            let child_path = Self::child_path(&base_path, &name);
            let child_node = match &node_base {
                NodeRef::Root => NodeRef::ExportRoot,
                NodeRef::ExportRoot => {
                    if name == "mounts" {
                        NodeRef::MountsDir
                    } else {
                        NodeRef::LayersDir
                    }
                }
                NodeRef::MountsDir => NodeRef::MountRoot {
                    mount_id: name.clone(),
                    kind: MountKind::User,
                },
                NodeRef::LayersDir => NodeRef::MountRoot {
                    mount_id: name.clone(),
                    kind: MountKind::Layer,
                },
                NodeRef::MountRoot { mount_id, kind } => NodeRef::MountPath {
                    mount_id: mount_id.clone(),
                    kind: kind.clone(),
                    relative: PathBuf::from(&name),
                },
                NodeRef::MountPath {
                    mount_id,
                    kind,
                    relative,
                } => {
                    let mut next = relative.clone();
                    next.push(&name);
                    NodeRef::MountPath {
                        mount_id: mount_id.clone(),
                        kind: kind.clone(),
                        relative: next,
                    }
                }
            };
            let child_id = self.assign_id(&child_path, child_node);
            resolved_entries.push((child_id, name, child_path));
        }
        resolved_entries.sort_by(|a, b| a.1.cmp(&b.1));

        let mut start_index = 0;
        if start_after != 0 {
            if let Some(pos) = resolved_entries
                .iter()
                .position(|(id, _, _)| *id == start_after)
            {
                start_index = pos + 1;
            }
        }

        for (id, name, _) in resolved_entries
            .into_iter()
            .skip(start_index)
            .take(max_entries)
        {
            entries.push(DirEntry {
                fileid: id,
                name: name.into_bytes().into(),
                attr: Self::synthetic_dir_attr(id),
            });
        }

        Ok(ReadDirResult {
            entries,
            end: true,
        })
    }

    async fn symlink(
        &self,
        dirid: fileid3,
        linkname: &filename3,
        symlink: &nfspath3,
        _attr: &sattr3,
    ) -> Result<(fileid3, fattr3), nfsstat3> {
        let node = self.node_for_id(dirid).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let linkname = String::from_utf8_lossy(linkname.as_ref()).to_string();
        let target = String::from_utf8_lossy(symlink.as_ref()).to_string();
        let (view, mut relative, mount_id, kind) = match node {
            NodeRef::MountRoot { mount_id, kind } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, PathBuf::new(), mount_id, kind)
            }
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative, mount_id, kind)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };
        self.ensure_writeable(&view)?;
        relative.push(&linkname);
        let top = Self::top_layer_path(&view.view).ok_or(nfsstat3::NFS3ERR_ROFS)?;
        let link_path = top.join(&relative);
        if let Some(parent) = link_path.parent() {
            fs::create_dir_all(parent)
                .await
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        }
        #[cfg(unix)]
        {
            let link_path = link_path.clone();
            tokio::task::spawn_blocking(move || std::os::unix::fs::symlink(&target, &link_path))
                .await
                .map_err(|_| nfsstat3::NFS3ERR_IO)?
                .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        }
        let meta = fs::symlink_metadata(&link_path)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        let node = NodeRef::MountPath {
            mount_id: mount_id.clone(),
            kind: kind.clone(),
            relative: relative.clone(),
        };
        let path = self.node_path(&node);
        let id = self.assign_id(&path, node);
        self.emit_op(kind, &mount_id, &relative, NfsOpKind::Create);
        Ok((id, fs_util::metadata_to_fattr3(id, &meta)))
    }

    async fn readlink(&self, id: fileid3) -> Result<nfspath3, nfsstat3> {
        let node = self.node_for_id(id).ok_or(nfsstat3::NFS3ERR_STALE)?;
        let (view, relative) = match node {
            NodeRef::MountPath {
                mount_id,
                kind,
                relative,
            } => {
                let view = self.resolve_mount_view(&kind, &mount_id)?;
                (view, relative)
            }
            _ => return Err(nfsstat3::NFS3ERR_INVAL),
        };
        let relative_str = relative.to_string_lossy().to_string();
        let resolved = self
            .overlay
            .resolve_path(&view.view, &relative_str)
            .ok_or(nfsstat3::NFS3ERR_NOENT)?;
        let target = fs::read_link(&resolved.source)
            .await
            .map_err(|_| nfsstat3::NFS3ERR_IO)?;
        Ok(target.to_string_lossy().as_bytes().to_vec().into())
    }
}

fn fnv_hash(value: &str) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for byte in value.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

#[derive(Clone)]
struct MountView {
    view: OverlayView,
    writable: bool,
}
