use anyhow::Result;
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::models::FileChange;

#[derive(Clone)]
pub struct ReplicationEngine {
    inner: Arc<Mutex<ReplicationState>>,
}

#[derive(Debug)]
struct ReplicationState {
    suppressed: HashMap<String, Instant>,
    ttl: Duration,
}

impl ReplicationEngine {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(ReplicationState {
                suppressed: HashMap::new(),
                ttl: Duration::from_secs(5),
            })),
        }
    }

    pub fn suppress(&self, key: String) {
        let mut inner = self.inner.lock().expect("replication lock poisoned");
        inner.cleanup();
        inner.suppressed.insert(key, Instant::now());
    }

    pub fn is_suppressed(&self, key: &str) -> bool {
        let mut inner = self.inner.lock().expect("replication lock poisoned");
        inner.cleanup();
        inner.suppressed.contains_key(key)
    }
}

impl ReplicationState {
    fn cleanup(&mut self) {
        let now = Instant::now();
        self.suppressed
            .retain(|_, timestamp| now.duration_since(*timestamp) < self.ttl);
    }
}

pub fn replay_change(change: &FileChange, source_root: &Path, target_root: &Path) -> Result<()> {
    let source_path = source_root.join(&change.relative_path);
    let target_path = target_root.join(&change.relative_path);

    match change.change_type {
        crate::models::FileChangeType::Add | crate::models::FileChangeType::Modify => {
            copy_path(&source_path, &target_path)?;
        }
        crate::models::FileChangeType::Delete => {
            delete_path(&target_path)?;
        }
    }

    Ok(())
}

pub fn reconcile_trees(source_root: &Path, target_root: &Path) -> Result<()> {
    if !source_root.exists() {
        return Ok(());
    }
    copy_directory_contents(source_root, target_root, source_root)?;
    delete_removed_entries(source_root, target_root)?;
    Ok(())
}

fn copy_path(source: &Path, target: &Path) -> Result<()> {
    if source.is_dir() {
        fs::create_dir_all(target)?;
        return Ok(());
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    if source.exists() {
        fs::copy(source, target)?;
    }
    Ok(())
}

fn delete_path(target: &Path) -> Result<()> {
    if !target.exists() {
        return Ok(());
    }
    if target.is_dir() {
        fs::remove_dir_all(target)?;
    } else {
        fs::remove_file(target)?;
    }
    Ok(())
}

fn copy_directory_contents(source: &Path, target: &Path, root: &Path) -> Result<()> {
    if !source.exists() {
        return Ok(());
    }
    let entries = fs::read_dir(source)?;
    for entry in entries.flatten() {
        let source_path = entry.path();
        let relative = source_path.strip_prefix(root).unwrap_or(&source_path);
        let target_path = target.join(relative);
        if source_path.is_dir() {
            fs::create_dir_all(&target_path)?;
            copy_directory_contents(&source_path, target, root)?;
        } else {
            if let Some(parent) = target_path.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(&source_path, &target_path)?;
        }
    }
    Ok(())
}

fn delete_removed_entries(source_root: &Path, target_root: &Path) -> Result<()> {
    if !target_root.exists() {
        return Ok(());
    }
    let entries = fs::read_dir(target_root)?;
    for entry in entries.flatten() {
        let target_path = entry.path();
        let relative = target_path.strip_prefix(target_root).unwrap_or(&target_path);
        let source_path = source_root.join(relative);
        if !source_path.exists() {
            delete_path(&target_path)?;
        } else if target_path.is_dir() {
            delete_removed_entries(&source_path, &target_path)?;
        }
    }
    Ok(())
}
