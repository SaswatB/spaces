import { watch, type FSWatcher } from 'chokidar';
import { EventEmitter } from 'node:events';
import { relative, join } from 'node:path';
import { stat, constants } from 'node:fs/promises';
import type { FileChange, FileChangeType } from '../types.js';

/**
 * Events emitted by the file watcher
 */
export interface WatcherEvents {
  change: (change: FileChange) => void;
  error: (error: Error) => void;
  ready: () => void;
}

/**
 * Configuration for the file watcher
 */
export interface WatcherConfig {
  /** Patterns to ignore (glob patterns) */
  ignorePatterns: string[];
  /** Debounce interval in milliseconds */
  debounceMs: number;
}

/**
 * Watches a directory for file changes, handling overlayfs-specific behaviors
 * like whiteout files (character devices with major:minor 0:0).
 */
export class FileWatcher extends EventEmitter {
  private watcher: FSWatcher | null = null;
  private watchPath: string;
  private config: WatcherConfig;
  private debounceTimers: Map<string, NodeJS.Timeout> = new Map();
  private pendingChanges: Map<string, FileChange> = new Map();

  constructor(watchPath: string, config: WatcherConfig) {
    super();
    this.watchPath = watchPath;
    this.config = config;
  }

  /**
   * Start watching the directory
   */
  async start(): Promise<void> {
    if (this.watcher) {
      return;
    }

    this.watcher = watch(this.watchPath, {
      ignored: this.config.ignorePatterns,
      persistent: true,
      ignoreInitial: true,
      awaitWriteFinish: {
        stabilityThreshold: this.config.debounceMs,
        pollInterval: 50,
      },
      // Follow symlinks
      followSymlinks: true,
      // Use polling for overlayfs compatibility if needed
      usePolling: false,
    });

    this.watcher.on('add', (path) => this.handleChange(path, 'add'));
    this.watcher.on('change', (path) => this.handleChange(path, 'modify'));
    this.watcher.on('unlink', (path) => this.handleChange(path, 'delete'));
    this.watcher.on('addDir', (path) => this.handleChange(path, 'add'));
    this.watcher.on('unlinkDir', (path) => this.handleChange(path, 'delete'));
    this.watcher.on('error', (error) => this.emit('error', error));
    this.watcher.on('ready', () => this.emit('ready'));
  }

  /**
   * Stop watching
   */
  async stop(): Promise<void> {
    if (this.watcher) {
      await this.watcher.close();
      this.watcher = null;
    }

    // Clear all pending timers
    for (const timer of this.debounceTimers.values()) {
      clearTimeout(timer);
    }
    this.debounceTimers.clear();
    this.pendingChanges.clear();
  }

  /**
   * Handle a file change event
   */
  private async handleChange(
    absolutePath: string,
    type: FileChangeType
  ): Promise<void> {
    const relativePath = relative(this.watchPath, absolutePath);

    // Check if this is a whiteout file (overlayfs deletion marker)
    const actualType = await this.detectChangeType(absolutePath, type);

    const change: FileChange = {
      type: actualType,
      relativePath,
      timestamp: new Date(),
    };

    // Debounce rapid changes to the same file
    const existingTimer = this.debounceTimers.get(relativePath);
    if (existingTimer) {
      clearTimeout(existingTimer);
    }

    this.pendingChanges.set(relativePath, change);

    const timer = setTimeout(() => {
      const pendingChange = this.pendingChanges.get(relativePath);
      if (pendingChange) {
        this.emit('change', pendingChange);
        this.pendingChanges.delete(relativePath);
      }
      this.debounceTimers.delete(relativePath);
    }, this.config.debounceMs);

    this.debounceTimers.set(relativePath, timer);
  }

  /**
   * Detect the actual change type, handling overlayfs whiteouts
   */
  private async detectChangeType(
    absolutePath: string,
    reportedType: FileChangeType
  ): Promise<FileChangeType> {
    if (reportedType === 'delete') {
      return 'delete';
    }

    try {
      const stats = await stat(absolutePath);

      // Check if this is an overlayfs whiteout file
      // Whiteouts are character devices with major:minor 0:0
      if (stats.isCharacterDevice() && stats.rdev === 0) {
        return 'delete';
      }

      return reportedType;
    } catch {
      // If we can't stat the file, treat it as a delete
      return 'delete';
    }
  }

  /**
   * Flush any pending debounced changes immediately
   */
  flushPending(): void {
    for (const [relativePath, change] of this.pendingChanges) {
      const timer = this.debounceTimers.get(relativePath);
      if (timer) {
        clearTimeout(timer);
        this.debounceTimers.delete(relativePath);
      }
      this.emit('change', change);
    }
    this.pendingChanges.clear();
  }
}

/**
 * Check if a file is an overlayfs whiteout
 */
export async function isWhiteout(filePath: string): Promise<boolean> {
  try {
    const stats = await stat(filePath);
    return stats.isCharacterDevice() && stats.rdev === 0;
  } catch {
    return false;
  }
}
