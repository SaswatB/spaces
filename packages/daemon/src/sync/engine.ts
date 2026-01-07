import { EventEmitter } from 'node:events';
import {
  copyFile,
  mkdir,
  rm,
  readdir,
  stat,
  constants,
  access,
} from 'node:fs/promises';
import { join, dirname, relative, basename } from 'node:path';
import { existsSync, mkdirSync } from 'node:fs';
import { exec } from 'node:child_process';
import { promisify } from 'node:util';
import { FileWatcher, isWhiteout } from './watcher.js';
import type { FileChange, SyncState, SyncStatus } from '../types.js';

const execAsync = promisify(exec);

/**
 * Events emitted by the sync engine
 */
export interface SyncEngineEvents {
  syncStart: () => void;
  syncComplete: () => void;
  syncError: (error: Error) => void;
  stateChange: (state: SyncState) => void;
  conflict: (relativePath: string) => void;
}

/**
 * Configuration for the sync engine
 */
export interface SyncEngineConfig {
  /** Patterns to ignore during sync */
  ignorePatterns: string[];
  /** Debounce interval in milliseconds */
  debounceMs: number;
}

/**
 * Bidirectional sync engine for user mounts and layers.
 *
 * This engine maintains synchronization between a user mount's upper directory
 * and the attached layer's upper directory. Changes flow both ways:
 * - User edits in mount → sync to layer
 * - External layer changes → sync to mount
 */
export class SyncEngine extends EventEmitter {
  private userMountId: string;
  private userMountUpperDir: string;
  private layerUpperDir: string | null = null;
  private config: SyncEngineConfig;

  private mountWatcher: FileWatcher | null = null;
  private layerWatcher: FileWatcher | null = null;

  private state: SyncState;
  private syncInProgress = false;
  private pendingSync = false;

  // Track which side initiated each change to prevent echo
  private recentMountChanges: Set<string> = new Set();
  private recentLayerChanges: Set<string> = new Set();
  private echoSuppressionMs = 1000;

  constructor(
    userMountId: string,
    userMountUpperDir: string,
    config: SyncEngineConfig
  ) {
    super();
    this.userMountId = userMountId;
    this.userMountUpperDir = userMountUpperDir;
    this.config = config;

    this.state = {
      userMountId,
      status: 'idle',
      lastSyncedAt: null,
      pendingChanges: [],
      error: null,
    };
  }

  /**
   * Attach to a layer and start syncing
   */
  async attach(layerUpperDir: string): Promise<void> {
    // Stop any existing sync
    await this.detach();

    this.layerUpperDir = layerUpperDir;

    // Perform initial sync: layer → mount
    await this.performInitialSync();

    // Start watching both directories
    await this.startWatchers();

    this.updateState({ status: 'idle', error: null });
  }

  /**
   * Detach from the current layer
   */
  async detach(): Promise<void> {
    await this.stopWatchers();

    // Flush any pending changes to the layer before detaching
    if (this.layerUpperDir && this.state.pendingChanges.length > 0) {
      await this.flushToLayer();
    }

    this.layerUpperDir = null;
    this.updateState({
      status: 'idle',
      pendingChanges: [],
      error: null,
    });
  }

  /**
   * Switch to a different layer
   */
  async switchLayer(newLayerUpperDir: string): Promise<void> {
    const oldLayerUpperDir = this.layerUpperDir;

    // 1. Flush pending changes to old layer
    if (oldLayerUpperDir) {
      await this.flushToLayer();
    }

    // 2. Stop watchers
    await this.stopWatchers();

    // 3. Compute diff between old and new layer
    this.updateState({ status: 'syncing' });
    this.emit('syncStart');

    try {
      // 4. Apply the diff to the mount's upper dir
      await this.applyLayerDiff(oldLayerUpperDir, newLayerUpperDir);

      // 5. Update layer reference
      this.layerUpperDir = newLayerUpperDir;

      // 6. Restart watchers
      await this.startWatchers();

      this.updateState({
        status: 'idle',
        lastSyncedAt: new Date(),
        error: null,
      });
      this.emit('syncComplete');
    } catch (error) {
      const err = error as Error;
      this.updateState({
        status: 'error',
        error: err.message,
      });
      this.emit('syncError', err);
      throw err;
    }
  }

  /**
   * Get current sync state
   */
  getState(): SyncState {
    return { ...this.state };
  }

  /**
   * Force a full sync from layer to mount
   */
  async forceSync(): Promise<void> {
    if (!this.layerUpperDir) {
      return;
    }

    await this.performInitialSync();
  }

  // ===========================================================================
  // Private methods
  // ===========================================================================

  private async startWatchers(): Promise<void> {
    // Watch mount's upper dir for user changes
    this.mountWatcher = new FileWatcher(this.userMountUpperDir, {
      ignorePatterns: this.config.ignorePatterns,
      debounceMs: this.config.debounceMs,
    });

    this.mountWatcher.on('change', (change) => this.handleMountChange(change));
    this.mountWatcher.on('error', (error) => this.emit('syncError', error));
    await this.mountWatcher.start();

    // Watch layer's upper dir for external changes
    if (this.layerUpperDir) {
      this.layerWatcher = new FileWatcher(this.layerUpperDir, {
        ignorePatterns: this.config.ignorePatterns,
        debounceMs: this.config.debounceMs,
      });

      this.layerWatcher.on('change', (change) =>
        this.handleLayerChange(change)
      );
      this.layerWatcher.on('error', (error) => this.emit('syncError', error));
      await this.layerWatcher.start();
    }
  }

  private async stopWatchers(): Promise<void> {
    if (this.mountWatcher) {
      this.mountWatcher.flushPending();
      await this.mountWatcher.stop();
      this.mountWatcher = null;
    }

    if (this.layerWatcher) {
      this.layerWatcher.flushPending();
      await this.layerWatcher.stop();
      this.layerWatcher = null;
    }
  }

  /**
   * Handle a change from the mount's upper dir → sync to layer
   */
  private async handleMountChange(change: FileChange): Promise<void> {
    if (!this.layerUpperDir) {
      return;
    }

    // Check if this is an echo from a layer → mount sync
    if (this.recentLayerChanges.has(change.relativePath)) {
      this.recentLayerChanges.delete(change.relativePath);
      return;
    }

    // Mark this as a mount-initiated change to suppress echo
    this.recentMountChanges.add(change.relativePath);
    setTimeout(() => {
      this.recentMountChanges.delete(change.relativePath);
    }, this.echoSuppressionMs);

    // Queue the change
    this.state.pendingChanges.push(change);
    this.updateState({});

    // Sync to layer
    await this.syncChangeToLayer(change);
  }

  /**
   * Handle a change from the layer's upper dir → sync to mount
   */
  private async handleLayerChange(change: FileChange): Promise<void> {
    // Check if this is an echo from a mount → layer sync
    if (this.recentMountChanges.has(change.relativePath)) {
      this.recentMountChanges.delete(change.relativePath);
      return;
    }

    // Mark this as a layer-initiated change to suppress echo
    this.recentLayerChanges.add(change.relativePath);
    setTimeout(() => {
      this.recentLayerChanges.delete(change.relativePath);
    }, this.echoSuppressionMs);

    // Sync to mount
    await this.syncChangeToMount(change);
  }

  /**
   * Sync a single change from mount to layer
   */
  private async syncChangeToLayer(change: FileChange): Promise<void> {
    if (!this.layerUpperDir) {
      return;
    }

    const sourcePath = join(this.userMountUpperDir, change.relativePath);
    const targetPath = join(this.layerUpperDir, change.relativePath);

    try {
      switch (change.type) {
        case 'add':
        case 'modify':
          await this.copyPath(sourcePath, targetPath);
          break;

        case 'delete':
          await this.deletePath(targetPath);
          break;
      }

      // Remove from pending changes
      this.state.pendingChanges = this.state.pendingChanges.filter(
        (c) => c.relativePath !== change.relativePath
      );
      this.updateState({ lastSyncedAt: new Date() });
    } catch (error) {
      const err = error as Error;
      this.emit('syncError', err);
    }
  }

  /**
   * Sync a single change from layer to mount
   */
  private async syncChangeToMount(change: FileChange): Promise<void> {
    if (!this.layerUpperDir) {
      return;
    }

    const sourcePath = join(this.layerUpperDir, change.relativePath);
    const targetPath = join(this.userMountUpperDir, change.relativePath);

    try {
      switch (change.type) {
        case 'add':
        case 'modify':
          await this.copyPath(sourcePath, targetPath);
          break;

        case 'delete':
          await this.deletePath(targetPath);
          break;
      }
    } catch (error) {
      const err = error as Error;
      this.emit('syncError', err);
    }
  }

  /**
   * Perform initial sync: copy layer state to mount
   */
  private async performInitialSync(): Promise<void> {
    if (!this.layerUpperDir) {
      return;
    }

    this.updateState({ status: 'syncing' });
    this.emit('syncStart');

    try {
      // Clear mount's upper dir and copy from layer
      await this.clearDirectory(this.userMountUpperDir);
      await this.copyDirectoryContents(
        this.layerUpperDir,
        this.userMountUpperDir
      );

      this.updateState({
        status: 'idle',
        lastSyncedAt: new Date(),
        pendingChanges: [],
        error: null,
      });
      this.emit('syncComplete');
    } catch (error) {
      const err = error as Error;
      this.updateState({
        status: 'error',
        error: err.message,
      });
      this.emit('syncError', err);
      throw err;
    }
  }

  /**
   * Apply the diff between two layer upper dirs to the mount
   */
  private async applyLayerDiff(
    oldLayerUpperDir: string | null,
    newLayerUpperDir: string
  ): Promise<void> {
    // Get files in old and new layers
    const oldFiles = oldLayerUpperDir
      ? await this.listFilesRecursive(oldLayerUpperDir)
      : new Set<string>();
    const newFiles = await this.listFilesRecursive(newLayerUpperDir);

    // Files to add/update: in new but not in old, or different content
    for (const relativePath of newFiles) {
      if (this.shouldIgnore(relativePath)) {
        continue;
      }
      const sourcePath = join(newLayerUpperDir, relativePath);
      const targetPath = join(this.userMountUpperDir, relativePath);

      // Check if it's a whiteout in the new layer
      if (await isWhiteout(sourcePath)) {
        await this.deletePath(targetPath);
        continue;
      }

      // Copy the file
      await this.copyPath(sourcePath, targetPath);
    }

    // Files to delete: in old but not in new
    for (const relativePath of oldFiles) {
      if (this.shouldIgnore(relativePath)) {
        continue;
      }
      if (!newFiles.has(relativePath)) {
        const targetPath = join(this.userMountUpperDir, relativePath);
        await this.deletePath(targetPath);
      }
    }
  }

  /**
   * Flush all pending changes to the layer
   */
  private async flushToLayer(): Promise<void> {
    if (!this.layerUpperDir) {
      return;
    }

    for (const change of this.state.pendingChanges) {
      await this.syncChangeToLayer(change);
    }

    this.state.pendingChanges = [];
    this.updateState({});
  }

  /**
   * List all files in a directory recursively
   */
  private async listFilesRecursive(dir: string): Promise<Set<string>> {
    const files = new Set<string>();

    if (!existsSync(dir)) {
      return files;
    }

    const walk = async (currentDir: string): Promise<void> => {
      const entries = await readdir(currentDir, { withFileTypes: true });

      for (const entry of entries) {
        const fullPath = join(currentDir, entry.name);
        const relativePath = relative(dir, fullPath);

        if (relativePath !== '' && relativePath !== '.' && this.shouldIgnore(relativePath)) {
          if (entry.isDirectory()) {
            continue;
          }
        }

        if (entry.isDirectory()) {
          if (relativePath !== '' && relativePath !== '.') {
            files.add(relativePath);
          }
          await walk(fullPath);
        } else {
          files.add(relativePath);
        }
      }
    };

    await walk(dir);
    return files;
  }

  /**
   * Copy a file or directory
   */
  private async copyPath(source: string, target: string): Promise<void> {
    try {
      const stats = await stat(source);

      if (stats.isDirectory()) {
        if (!existsSync(target)) {
          await mkdir(target, { recursive: true });
        }
      } else {
        // Ensure parent directory exists
        const targetDir = dirname(target);
        if (!existsSync(targetDir)) {
          await mkdir(targetDir, { recursive: true });
        }

        await copyFile(source, target);
      }
    } catch (error) {
      // Source might have been deleted between detection and copy
      const err = error as NodeJS.ErrnoException;
      if (err.code !== 'ENOENT') {
        throw error;
      }
    }
  }

  /**
   * Delete a file or directory
   */
  private async deletePath(target: string): Promise<void> {
    try {
      await rm(target, { recursive: true, force: true });
    } catch (error) {
      // Ignore if already deleted
      const err = error as NodeJS.ErrnoException;
      if (err.code !== 'ENOENT') {
        throw error;
      }
    }
  }

  /**
   * Clear a directory's contents without removing the directory itself
   */
  private async clearDirectory(dir: string): Promise<void> {
    if (!existsSync(dir)) {
      await mkdir(dir, { recursive: true });
      return;
    }

    const entries = await readdir(dir, { withFileTypes: true });
    for (const entry of entries) {
      await rm(join(dir, entry.name), { recursive: true, force: true });
    }
  }

  /**
   * Copy directory contents from source to target
   */
  private async copyDirectoryContents(
    source: string,
    target: string,
    rootSource: string = source
  ): Promise<void> {
    if (!existsSync(source)) {
      return;
    }

    const entries = await readdir(source, { withFileTypes: true });

    for (const entry of entries) {
      const sourcePath = join(source, entry.name);
      const targetPath = join(target, entry.name);
      const relativePath = relative(rootSource, sourcePath);

      if (relativePath !== '' && relativePath !== '.' && this.shouldIgnore(relativePath)) {
        continue;
      }

      if (entry.isDirectory()) {
        await mkdir(targetPath, { recursive: true });
        await this.copyDirectoryContents(sourcePath, targetPath, rootSource);
      } else {
        await copyFile(sourcePath, targetPath);
      }
    }
  }

  /**
   * Update internal state and emit event
   */
  private updateState(updates: Partial<SyncState>): void {
    this.state = { ...this.state, ...updates };
    this.emit('stateChange', this.getState());
  }

  private shouldIgnore(relativePath: string): boolean {
    const normalized = relativePath.split(/[/\\]+/).join('/');
    const fileName = basename(normalized);
    const segments = normalized.split('/');

    for (const patternRaw of this.config.ignorePatterns) {
      const pattern = patternRaw.trim();
      if (!pattern) continue;

      if (pattern.includes('*')) {
        if (this.matchesWildcard(fileName, pattern) || this.matchesWildcard(normalized, pattern)) {
          return true;
        }
        continue;
      }

      if (segments.includes(pattern) || fileName === pattern) {
        return true;
      }
    }

    return false;
  }

  private matchesWildcard(value: string, pattern: string): boolean {
    const escaped = pattern.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
    const regex = new RegExp(`^${escaped}$`);
    return regex.test(value);
  }
}
