use std::collections::{BTreeSet, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

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
            fs::create_dir_all(&upper_path)?;
            self.mark_opaque(view, &relative.to_string_lossy())?;
        } else {
            if let Some(parent) = upper_path.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(lower_path, upper_path)?;
        }

        Ok(())
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
            fs::write(marker, b"")?;
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
            fs::write(marker, b"")?;
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
}
