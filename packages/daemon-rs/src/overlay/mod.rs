use std::collections::{BTreeSet, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

#[cfg(unix)]
use std::ffi::CString;
#[cfg(unix)]
use std::os::unix::ffi::OsStrExt;
#[cfg(unix)]
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use tracing::debug;

mod whiteout;

pub use whiteout::{is_whiteout_marker, marker_name, OPAQUE_MARKER};

#[derive(Debug, Clone)]
pub struct OverlayView {
    pub entrypoint: PathBuf,
    pub layers: Vec<PathBuf>,
}

#[derive(Debug, Clone)]
pub struct ResolvedPath {
    pub source: PathBuf,
}

#[derive(Debug)]
pub struct OverlayEngine;

impl OverlayEngine {
    pub fn new() -> Self {
        Self
    }

    pub fn resolve_path(&self, view: &OverlayView, relative_path: &str) -> Option<ResolvedPath> {
        let relative = Path::new(relative_path);
        for layer in &view.layers {
            if self.is_hidden_in_layer(layer, relative) {
                return None;
            }
            let candidate = layer.join(relative);
            if candidate.exists() {
                return Some(ResolvedPath { source: candidate });
            }
        }
        let lower = view.entrypoint.join(relative);
        if lower.exists() {
            Some(ResolvedPath { source: lower })
        } else {
            None
        }
    }

    pub fn list_dir(&self, view: &OverlayView, relative_path: &str) -> Vec<String> {
        let relative = Path::new(relative_path);
        let mut entries = BTreeSet::new();
        let mut hidden = HashSet::new();

        for layer in &view.layers {
            let dir = layer.join(relative);
            if !dir.exists() {
                continue;
            }

            let mut opaque_here = false;
            if let Ok(read_dir) = fs::read_dir(&dir) {
                for entry in read_dir.flatten() {
                    let name = entry.file_name();
                    let name = name.to_string_lossy().to_string();
                    if name == OPAQUE_MARKER {
                        opaque_here = true;
                        continue;
                    }
                    if let Some(wh_name) = is_whiteout_marker(&name) {
                        hidden.insert(wh_name);
                        continue;
                    }
                    if !hidden.contains(&name) {
                        entries.insert(name);
                    }
                }
            }

            if opaque_here {
                return entries.into_iter().collect();
            }
        }

        let lower_dir = view.entrypoint.join(relative);
        if let Ok(read_dir) = fs::read_dir(&lower_dir) {
            for entry in read_dir.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if !hidden.contains(&name) {
                    entries.insert(name);
                }
            }
        }

        entries.into_iter().collect()
    }

    pub fn copy_up_if_needed(&self, view: &OverlayView, relative_path: &str) -> std::io::Result<()> {
        let relative = Path::new(relative_path);
        let Some(top_upper) = view.layers.first() else {
            return Ok(());
        };

        let upper_path = top_upper.join(relative);
        if upper_path.exists() {
            return Ok(());
        }

        let lower_path = view.entrypoint.join(relative);
        if !lower_path.exists() {
            return Ok(());
        }

        if lower_path.is_dir() {
            self.ensure_parent_dirs(view, relative)?;
            fs::create_dir_all(&upper_path)?;
            debug!(
                upper = %upper_path.display(),
                lower = %lower_path.display(),
                "Copy-up directory"
            );
            self.apply_metadata(&upper_path, &lower_path)?;
            self.mark_opaque(view, &relative.to_string_lossy())?;
        } else {
            self.ensure_parent_dirs(view, relative)?;
            fs::copy(&lower_path, &upper_path)?;
            debug!(
                upper = %upper_path.display(),
                lower = %lower_path.display(),
                "Copy-up file"
            );
            self.apply_metadata(&upper_path, &lower_path)?;
        }

        Ok(())
    }

    pub fn ensure_parent_dirs(
        &self,
        view: &OverlayView,
        relative_path: &Path,
    ) -> std::io::Result<()> {
        let Some(top_upper) = view.layers.first() else {
            return Ok(());
        };
        let Some(parent) = relative_path.parent() else {
            return Ok(());
        };
        if parent.components().count() == 0 {
            return Ok(());
        }

        let mut current = PathBuf::new();
        for component in parent.components() {
            current.push(component);
            let upper_dir = top_upper.join(&current);
            if upper_dir.exists() {
                continue;
            }
            let meta_source = self.lower_source_path(view, &current);
            fs::create_dir_all(&upper_dir)?;
            if let Some(source) = meta_source {
                if source.is_dir() {
                    self.apply_metadata(&upper_dir, &source)?;
                }
            }
        }

        Ok(())
    }

    pub fn apply_lower_metadata(
        &self,
        view: &OverlayView,
        relative: &Path,
        target: &Path,
    ) -> std::io::Result<()> {
        if let Some(source) = self.lower_source_path(view, relative) {
            debug!(
                target = %target.display(),
                source = %source.display(),
                "Applying lower metadata"
            );
            self.apply_metadata(target, &source)?;
        } else {
            debug!(
                target = %target.display(),
                relative = %relative.display(),
                "No lower metadata source found"
            );
        }
        Ok(())
    }

    pub fn apply_entrypoint_owner(
        &self,
        view: &OverlayView,
        target: &Path,
    ) -> std::io::Result<()> {
        self.apply_owner_from_path(&view.entrypoint, target)
    }

    pub fn mark_whiteout(&self, view: &OverlayView, relative_path: &str) -> std::io::Result<()> {
        let relative = Path::new(relative_path);
        let Some(top_upper) = view.layers.first() else {
            return Ok(());
        };
        let parent = relative.parent().unwrap_or_else(|| Path::new(""));
        let name = relative.file_name().and_then(|name| name.to_str());
        let Some(name) = name else {
            return Ok(());
        };
        let marker = top_upper.join(parent).join(marker_name(name));
        if let Some(parent_dir) = marker.parent() {
            fs::create_dir_all(parent_dir)?;
        }
        if !marker.exists() {
            fs::write(&marker, b"")?;
            self.apply_owner_from_path(&view.entrypoint, &marker)?;
        }
        Ok(())
    }

    pub fn mark_opaque(&self, view: &OverlayView, relative_path: &str) -> std::io::Result<()> {
        let relative = Path::new(relative_path);
        let Some(top_upper) = view.layers.first() else {
            return Ok(());
        };
        let marker = top_upper.join(relative).join(OPAQUE_MARKER);
        if let Some(parent_dir) = marker.parent() {
            fs::create_dir_all(parent_dir)?;
        }
        if !marker.exists() {
            fs::write(&marker, b"")?;
            self.apply_owner_from_path(&view.entrypoint, &marker)?;
        }
        Ok(())
    }

    fn is_hidden_in_layer(&self, layer: &Path, relative: &Path) -> bool {
        if relative.components().count() == 0 {
            return false;
        }

        let mut current = PathBuf::new();
        for component in relative.components() {
            current.push(component);
            let dir = layer.join(&current);
            let opaque_marker = dir.join(OPAQUE_MARKER);
            if opaque_marker.exists() {
                return true;
            }
        }

        if let Some(parent) = relative.parent() {
            if let Some(name) = relative.file_name().and_then(|name| name.to_str()) {
                let marker = layer.join(parent).join(marker_name(name));
                if marker.exists() {
                    return true;
                }
            }
        }

        false
    }

    fn lower_source_path(&self, view: &OverlayView, relative: &Path) -> Option<PathBuf> {
        for layer in view.layers.iter().skip(1) {
            let candidate = layer.join(relative);
            if candidate.exists() {
                return Some(candidate);
            }
        }
        let entry = view.entrypoint.join(relative);
        if entry.exists() {
            Some(entry)
        } else {
            None
        }
    }

    #[cfg(unix)]
    fn apply_metadata(&self, target: &Path, source: &Path) -> std::io::Result<()> {
        let meta = fs::metadata(source)?;
        debug!(
            target = %target.display(),
            source = %source.display(),
            uid = meta.uid(),
            gid = meta.gid(),
            mode = meta.mode(),
            "Applying metadata"
        );
        fs::set_permissions(target, fs::Permissions::from_mode(meta.mode()))?;
        if let Ok(c_path) = CString::new(target.as_os_str().as_bytes()) {
            unsafe {
                libc::chown(c_path.as_ptr(), meta.uid(), meta.gid());
            }
        }
        Ok(())
    }

    #[cfg(unix)]
    fn apply_owner_from_path(&self, source: &Path, target: &Path) -> std::io::Result<()> {
        let meta = fs::metadata(source)?;
        debug!(
            target = %target.display(),
            source = %source.display(),
            uid = meta.uid(),
            gid = meta.gid(),
            "Applying entrypoint owner"
        );
        if let Ok(c_path) = CString::new(target.as_os_str().as_bytes()) {
            unsafe {
                libc::chown(c_path.as_ptr(), meta.uid(), meta.gid());
            }
        }
        Ok(())
    }

    #[cfg(not(unix))]
    fn apply_owner_from_path(&self, _source: &Path, _target: &Path) -> std::io::Result<()> {
        Ok(())
    }

    #[cfg(not(unix))]
    fn apply_metadata(&self, _target: &Path, _source: &Path) -> std::io::Result<()> {
        Ok(())
    }
}
