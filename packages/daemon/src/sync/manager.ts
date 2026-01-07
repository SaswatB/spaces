import { EventEmitter } from 'node:events';
import { SyncEngine, type SyncEngineConfig } from './engine.js';
import type { SyncState, UserMount, Layer } from '../types.js';

/**
 * Manages sync engines for all user mounts.
 * Each user mount with an attached layer gets its own sync engine.
 */
export class SyncManager extends EventEmitter {
  private engines: Map<string, SyncEngine> = new Map();
  private config: SyncEngineConfig;

  constructor(config: SyncEngineConfig) {
    super();
    this.config = config;
  }

  /**
   * Create or update a sync engine for a user mount
   */
  async setupSync(userMount: UserMount, layer: Layer | null): Promise<void> {
    const existingEngine = this.engines.get(userMount.id);

    if (layer === null) {
      // No layer attached, stop and remove engine
      if (existingEngine) {
        await existingEngine.detach();
        this.engines.delete(userMount.id);
      }
      return;
    }

    if (existingEngine) {
      // Switch to new layer
      await existingEngine.switchLayer(layer.upperDir);
    } else {
      // Create new engine
      const engine = new SyncEngine(
        userMount.id,
        userMount.upperDir,
        this.config
      );

      // Forward events
      engine.on('syncStart', () => this.emit('syncStart', userMount.id));
      engine.on('syncComplete', () => this.emit('syncComplete', userMount.id));
      engine.on('syncError', (error) =>
        this.emit('syncError', userMount.id, error)
      );
      engine.on('stateChange', (state) =>
        this.emit('stateChange', userMount.id, state)
      );
      engine.on('conflict', (path) =>
        this.emit('conflict', userMount.id, path)
      );

      await engine.attach(layer.upperDir);
      this.engines.set(userMount.id, engine);
    }
  }

  /**
   * Stop syncing for a user mount
   */
  async stopSync(userMountId: string): Promise<void> {
    const engine = this.engines.get(userMountId);
    if (engine) {
      await engine.detach();
      this.engines.delete(userMountId);
    }
  }

  /**
   * Get sync state for a user mount
   */
  getSyncState(userMountId: string): SyncState | null {
    const engine = this.engines.get(userMountId);
    return engine?.getState() ?? null;
  }

  /**
   * Get all sync states
   */
  getAllSyncStates(): Map<string, SyncState> {
    const states = new Map<string, SyncState>();
    for (const [id, engine] of this.engines) {
      states.set(id, engine.getState());
    }
    return states;
  }

  /**
   * Force a full resync for a user mount
   */
  async forceSync(userMountId: string): Promise<void> {
    const engine = this.engines.get(userMountId);
    if (engine) {
      await engine.forceSync();
    }
  }

  /**
   * Shut down all sync engines
   */
  async shutdown(): Promise<void> {
    const stopPromises = Array.from(this.engines.keys()).map((id) =>
      this.stopSync(id)
    );
    await Promise.all(stopPromises);
  }
}
