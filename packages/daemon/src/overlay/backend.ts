import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import type { MountStatus } from '../types.js';

const execFileAsync = promisify(execFile);

/**
 * Abstract interface for overlay filesystem operations.
 * Allows swapping implementations (Linux overlayfs, macOS FUSE, etc.)
 */
export interface OverlayBackend {
  /**
   * Mount an overlay filesystem
   * @param lowerDirs - Array of lower directories (read-only layers), in order from bottom to top
   * @param upperDir - Directory where changes are written
   * @param workDir - Work directory required by overlayfs
   * @param targetPath - Where to mount the overlay
   */
  mount(
    lowerDirs: string[],
    upperDir: string,
    workDir: string,
    targetPath: string
  ): Promise<void>;

  /**
   * Unmount an overlay filesystem
   * @param targetPath - The mount point to unmount
   */
  unmount(targetPath: string): Promise<void>;

  /**
   * Check if a path is currently mounted
   * @param targetPath - The path to check
   */
  isMounted(targetPath: string): Promise<boolean>;

  /**
   * Get mount status for a path
   * @param targetPath - The path to check
   */
  getMountStatus(targetPath: string): Promise<MountStatus>;

  /**
   * Ensure directories exist for overlay operation
   * @param upperDir - The upper directory
   * @param workDir - The work directory
   * @param targetPath - The mount target
   */
  ensureDirectories(
    upperDir: string,
    workDir: string,
    targetPath: string
  ): Promise<void>;
}

/**
 * Linux overlayfs implementation
 */
export class LinuxOverlayBackend implements OverlayBackend {
  async mount(
    lowerDirs: string[],
    upperDir: string,
    workDir: string,
    targetPath: string
  ): Promise<void> {
    // Ensure all directories exist
    await this.ensureDirectories(upperDir, workDir, targetPath);

    // Validate lower dirs exist
    for (const dir of lowerDirs) {
      if (!existsSync(dir)) {
        throw new Error(`Lower directory does not exist: ${dir}`);
      }
    }

    // Check if already mounted
    if (await this.isMounted(targetPath)) {
      throw new Error(`Target path is already mounted: ${targetPath}`);
    }

    // Build the mount command
    // Lower dirs are specified from bottom to top, colon-separated
    const lowerDir = lowerDirs.join(':');
    const options = `lowerdir=${lowerDir},upperdir=${upperDir},workdir=${workDir}`;

    try {
      await execFileAsync('mount', [
        '-t',
        'overlay',
        'overlay',
        '-o',
        options,
        targetPath,
      ]);
    } catch (error) {
      const err = error as { stderr?: string; message: string };
      throw new Error(`Failed to mount overlay: ${err.stderr || err.message}`);
    }
  }

  async unmount(targetPath: string): Promise<void> {
    if (!(await this.isMounted(targetPath))) {
      // Already unmounted, nothing to do
      return;
    }

    try {
      await execFileAsync('umount', [targetPath]);
    } catch (error) {
      const err = error as { stderr?: string; message: string };
      // Try lazy unmount if regular unmount fails (e.g., busy)
      if (err.stderr?.includes('busy') || err.message.includes('busy')) {
        try {
          await execFileAsync('umount', ['-l', targetPath]);
        } catch (lazyError) {
          const lazyErr = lazyError as { stderr?: string; message: string };
          throw new Error(`Failed to unmount (even lazily): ${lazyErr.stderr || lazyErr.message}`);
        }
      } else {
        throw new Error(`Failed to unmount: ${err.stderr || err.message}`);
      }
    }
  }

  async isMounted(targetPath: string): Promise<boolean> {
    try {
      const { stdout } = await execFileAsync('mount', []);
      // Normalize path for comparison (remove trailing slash)
      const normalizedTarget = targetPath.replace(/\/$/, '');
      return stdout.split('\n').some((line) => {
        const match = line.match(/on (.+?) type/);
        if (match?.[1]) {
          const mountPoint = match[1].replace(/\/$/, '');
          return mountPoint === normalizedTarget;
        }
        return false;
      });
    } catch {
      return false;
    }
  }

  async getMountStatus(targetPath: string): Promise<MountStatus> {
    try {
      const mounted = await this.isMounted(targetPath);
      return mounted ? 'mounted' : 'unmounted';
    } catch {
      return 'error';
    }
  }

  async ensureDirectories(
    upperDir: string,
    workDir: string,
    targetPath: string
  ): Promise<void> {
    for (const dir of [upperDir, workDir, targetPath]) {
      if (!existsSync(dir)) {
        mkdirSync(dir, { recursive: true });
      }
    }
  }
}

/**
 * Check if overlayfs is available on the system
 */
export async function isOverlayfsAvailable(): Promise<boolean> {
  try {
    // Check if overlay is in /proc/filesystems
    const filesystems = readFileSync('/proc/filesystems', 'utf-8');
    return filesystems.includes('overlay');
  } catch {
    return false;
  }
}

/**
 * Factory function to create the appropriate backend for the current platform
 */
export function createOverlayBackend(): OverlayBackend {
  const platform = process.platform;

  if (platform === 'linux') {
    return new LinuxOverlayBackend();
  }

  throw new Error(
    `Unsupported platform: ${platform}. Currently only Linux is supported.`
  );
}
