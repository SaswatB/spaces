use anyhow::{anyhow, Result};
use std::fs;
use std::process::Command;
use std::process::Stdio;
use std::time::{Duration, Instant};
use tracing::info;

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
        if mount_healthy(&_spec.local_path) {
            info!(target = %_spec.local_path, "Mount already present");
            return Ok(());
        }
        info!(target = %_spec.local_path, "Mount present but unhealthy, remounting");
        let _ = unmount(&_spec.local_path);
    }

    fs::create_dir_all(&_spec.local_path)?;

    let source = format!("{}:{}", _spec.host, _spec.export_path);
    let mut options = format!(
        "nolocks,vers=3,tcp,port={},mountport={},soft,timeo=10,retrans=2",
        _spec.port, _spec.port
    );
    if _spec.read_only {
        options.push_str(",ro");
    }

    info!(source = %source, target = %_spec.local_path, options = %options, "Running mount_nfs");
    let mut child = Command::new("mount_nfs")
        .arg("-o")
        .arg(options)
        .arg(source)
        .arg(&_spec.local_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if let Some(_status) = child.try_wait()? {
            break;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(anyhow!(
                "mount_nfs timed out for {} after 20s",
                _spec.local_path
            ));
        }
        std::thread::sleep(Duration::from_millis(100));
    }

    let output = child.wait_with_output()?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(anyhow!(
            "mount_nfs failed for {} (code {:?}): {}",
            _spec.local_path,
            output.status.code(),
            stderr.trim()
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    if !stdout.trim().is_empty() {
        info!(target = %_spec.local_path, stdout = %stdout.trim(), "mount_nfs output");
    }

    if !is_mounted(&_spec.local_path)? {
        return Err(anyhow!(
            "mount_nfs succeeded but {} is not mounted",
            _spec.local_path
        ));
    }

    Ok(())
}

fn mount_healthy(local_path: &str) -> bool {
    match fs::read_dir(local_path) {
        Ok(_) => true,
        Err(_) => false,
    }
}

pub fn unmount(_local_path: &str) -> Result<()> {
    if !is_mounted(_local_path)? {
        return Ok(());
    }

    info!(target = %_local_path, "Running umount");
    let mut command = Command::new("umount");
    command.arg(_local_path);
    let output = run_with_timeout(command, Duration::from_secs(10), "umount", _local_path)?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        info!(target = %_local_path, stderr = %stderr.trim(), "umount failed, attempting force unmount");
        let mut command = Command::new("umount");
        command.arg("-f").arg(_local_path);
        let output =
            run_with_timeout(command, Duration::from_secs(10), "umount -f", _local_path)?;
        if output.status.success() {
            return Ok(());
        }
        let stderr = String::from_utf8_lossy(&output.stderr);
        let mut command = Command::new("diskutil");
        command.arg("unmount").arg("force").arg(_local_path);
        let output = run_with_timeout(
            command,
            Duration::from_secs(10),
            "diskutil unmount force",
            _local_path,
        )?;
        if output.status.success() {
            return Ok(());
        }
        let stderr_diskutil = String::from_utf8_lossy(&output.stderr);
        return Err(anyhow!(
            "umount failed for {}: {} | diskutil: {}",
            _local_path,
            stderr.trim(),
            stderr_diskutil.trim()
        ));
    }

    Ok(())
}

fn run_with_timeout(
    mut command: Command,
    timeout: Duration,
    label: &str,
    target: &str,
) -> Result<std::process::Output> {
    let mut child = command
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(_status) = child.try_wait()? {
            break;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(anyhow!(
                "{} timed out for {} after {}s",
                label,
                target,
                timeout.as_secs()
            ));
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    Ok(child.wait_with_output()?)
}

pub fn is_mounted(local_path: &str) -> Result<bool> {
    let output = Command::new("mount").output()?;
    if !output.status.success() {
        return Err(anyhow!("mount command failed"));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let canonical_target = std::fs::canonicalize(local_path)
        .ok()
        .and_then(|path| path.to_str().map(|value| value.to_string()))
        .unwrap_or_else(|| local_path.to_string());
    let needle_with_space = format!(" on {} ", canonical_target);
    let needle_with_paren = format!(" on {} (", canonical_target);
    for line in stdout.lines() {
        if line.contains(&needle_with_space) || line.contains(&needle_with_paren) {
            return Ok(true);
        }
        if let Some(mount_point) = line.split(" on ").nth(1).and_then(|rest| rest.split(" (").next()) {
            let canonical_mount = std::fs::canonicalize(mount_point)
                .ok()
                .and_then(|path| path.to_str().map(|value| value.to_string()));
            if let Some(canonical_mount) = canonical_mount {
                if canonical_mount == canonical_target {
                    return Ok(true);
                }
            }
        }
    }
    Ok(false)
}
