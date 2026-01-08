use anyhow::{anyhow, Result};
use std::fs;
use std::process::Command;

#[derive(Clone, Debug)]
pub struct MountSpec {
    pub host: String,
    pub port: u16,
    pub export_path: String,
    pub local_path: String,
    pub read_only: bool,
}

pub fn ensure_mount(_spec: &MountSpec) -> Result<()> {
    if is_mounted(&_spec.local_path)? {
        return Ok(());
    }

    fs::create_dir_all(&_spec.local_path)?;

    let source = format!("{}:{}", _spec.host, _spec.export_path);
    let mut options = format!("vers=3,port={}", _spec.port);
    if _spec.read_only {
        options.push_str(",ro");
    }

    let status = Command::new("mount_nfs")
        .arg("-o")
        .arg(options)
        .arg(source)
        .arg(&_spec.local_path)
        .status()?;

    if !status.success() {
        return Err(anyhow!("mount_nfs failed for {}", _spec.local_path));
    }

    Ok(())
}

pub fn unmount(_local_path: &str) -> Result<()> {
    if !is_mounted(_local_path)? {
        return Ok(());
    }

    let status = Command::new("umount")
        .arg(_local_path)
        .status()?;

    if !status.success() {
        return Err(anyhow!("umount failed for {}", _local_path));
    }

    Ok(())
}

pub fn is_mounted(local_path: &str) -> Result<bool> {
    let output = Command::new("mount").output()?;
    if !output.status.success() {
        return Err(anyhow!("mount command failed"));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let needle_with_space = format!(" on {} ", local_path);
    let needle_with_paren = format!(" on {} (", local_path);
    for line in stdout.lines() {
        if line.contains(&needle_with_space) || line.contains(&needle_with_paren) {
            return Ok(true);
        }
    }
    Ok(false)
}
