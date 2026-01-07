import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { eq } from 'drizzle-orm';
import type { Db } from '../db/index.js';
import { entrypoints, layers, userMounts } from '../db/schema.js';
import { createOverlayBackend, type OverlayBackend } from '../overlay/index.js';
import { SyncManager } from '../sync/index.js';
import type {
  Config,
  Entrypoint,
  Layer,
  UserMount,
  CreateEntrypointInput,
  CreateLayerInput,
  CreateUserMountInput,
  LayerWithStatus,
  UserMountWithStatus,
} from '../types.js';

/**
 * Main service for managing spaces (entrypoints, layers, and mounts)
 */
export class SpacesService {
  private db: Db;
  private config: Config;
  private overlay: OverlayBackend;
  private syncManager: SyncManager;

  constructor(db: Db, config: Config) {
    this.db = db;
    this.config = config;
    this.overlay = createOverlayBackend();
    this.syncManager = new SyncManager({
      ignorePatterns: config.syncIgnorePatterns,
      debounceMs: config.syncDebounceMs,
    });

    // Ensure data directory exists
    if (!existsSync(config.dataDir)) {
      mkdirSync(config.dataDir, { recursive: true });
    }
  }

  // ===========================================================================
  // Entrypoints
  // ===========================================================================

  async createEntrypoint(input: CreateEntrypointInput): Promise<Entrypoint> {
    // Validate path exists
    if (!existsSync(input.path)) {
      throw new Error(`Path does not exist: ${input.path}`);
    }

    const now = new Date();
    const entrypoint: Entrypoint = {
      id: randomUUID(),
      name: input.name,
      path: input.path,
      createdAt: now,
      updatedAt: now,
    };

    await this.db.insert(entrypoints).values({
      id: entrypoint.id,
      name: entrypoint.name,
      path: entrypoint.path,
      createdAt: now,
      updatedAt: now,
    });

    return entrypoint;
  }

  async getEntrypoint(id: string): Promise<Entrypoint | null> {
    const result = await this.db.query.entrypoints.findFirst({
      where: eq(entrypoints.id, id),
    });
    return result ?? null;
  }

  async listEntrypoints(): Promise<Entrypoint[]> {
    return await this.db.query.entrypoints.findMany();
  }

  async deleteEntrypoint(id: string): Promise<void> {
    // Check for dependent layers
    const dependentLayers = await this.db.query.layers.findMany({
      where: eq(layers.entrypointId, id),
    });

    if (dependentLayers.length > 0) {
      throw new Error(
        `Cannot delete entrypoint: ${dependentLayers.length} layers depend on it`
      );
    }

    // Check for dependent user mounts
    const dependentMounts = await this.db.query.userMounts.findMany({
      where: eq(userMounts.entrypointId, id),
    });

    if (dependentMounts.length > 0) {
      throw new Error(
        `Cannot delete entrypoint: ${dependentMounts.length} user mounts depend on it`
      );
    }

    await this.db.delete(entrypoints).where(eq(entrypoints.id, id));
  }

  // ===========================================================================
  // Layers
  // ===========================================================================

  async createLayer(input: CreateLayerInput): Promise<Layer> {
    const entrypoint = await this.getEntrypoint(input.entrypointId);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${input.entrypointId}`);
    }

    // Validate parent if specified
    if (input.parentId) {
      const parent = await this.getLayer(input.parentId);
      if (!parent) {
        throw new Error(`Parent layer not found: ${input.parentId}`);
      }
      if (parent.entrypointId !== input.entrypointId) {
        throw new Error('Parent layer must belong to the same entrypoint');
      }
    }

    const now = new Date();
    const id = randomUUID();

    // Create directories for this layer
    const upperDir = join(this.config.dataDir, 'layers', id, 'upper');
    const workDir = join(this.config.dataDir, 'layers', id, 'work');
    const defaultMountPath =
      input.mountPath ?? join(this.config.dataDir, 'mounts', 'layers', id);

    mkdirSync(upperDir, { recursive: true });
    mkdirSync(workDir, { recursive: true });
    mkdirSync(defaultMountPath, { recursive: true });

    const layer: Layer = {
      id,
      name: input.name,
      entrypointId: input.entrypointId,
      parentId: input.parentId ?? null,
      upperDir,
      workDir,
      mountPath: defaultMountPath,
      createdAt: now,
      updatedAt: now,
    };

    await this.db.insert(layers).values({
      id: layer.id,
      name: layer.name,
      entrypointId: layer.entrypointId,
      parentId: layer.parentId,
      upperDir: layer.upperDir,
      workDir: layer.workDir,
      mountPath: layer.mountPath,
      createdAt: now,
      updatedAt: now,
    });

    // Mount the layer
    await this.mountLayer(layer);

    return layer;
  }

  async getLayer(id: string): Promise<Layer | null> {
    const result = await this.db.query.layers.findFirst({
      where: eq(layers.id, id),
    });
    return (result as Layer | undefined) ?? null;
  }

  async getLayerWithStatus(id: string): Promise<LayerWithStatus | null> {
    const layer = await this.getLayer(id);
    if (!layer) return null;

    const mountStatus = await this.overlay.getMountStatus(layer.mountPath);
    return { ...layer, mountStatus };
  }

  async listLayers(entrypointId?: string): Promise<Layer[]> {
    if (entrypointId) {
      const result = await this.db.query.layers.findMany({
        where: eq(layers.entrypointId, entrypointId),
      });
      return result as Layer[];
    }
    const result = await this.db.query.layers.findMany();
    return result as Layer[];
  }

  async listLayersWithStatus(entrypointId?: string): Promise<LayerWithStatus[]> {
    const layersList = await this.listLayers(entrypointId);
    return Promise.all(
      layersList.map(async (layer) => ({
        ...layer,
        mountStatus: await this.overlay.getMountStatus(layer.mountPath),
      }))
    );
  }

  async deleteLayer(id: string): Promise<void> {
    const layer = await this.getLayer(id);
    if (!layer) {
      throw new Error(`Layer not found: ${id}`);
    }

    // Check for child layers
    const childLayers = await this.db.query.layers.findMany({
      where: eq(layers.parentId, id),
    });

    if (childLayers.length > 0) {
      throw new Error(
        `Cannot delete layer: ${childLayers.length} layers depend on it`
      );
    }

    // Check for attached user mounts
    const attachedMounts = await this.db.query.userMounts.findMany({
      where: eq(userMounts.attachedLayerId, id),
    });

    if (attachedMounts.length > 0) {
      throw new Error(
        `Cannot delete layer: ${attachedMounts.length} user mounts are attached to it`
      );
    }

    // Unmount
    await this.unmountLayer(layer);

    // Delete from database
    await this.db.delete(layers).where(eq(layers.id, id));

    // Note: We don't delete the upper/work directories - that's a separate cleanup operation
  }

  async mountLayer(layer: Layer): Promise<void> {
    // Build the lower dirs chain
    const lowerDirs = await this.buildLowerDirs(layer);

    await this.overlay.mount(
      lowerDirs,
      layer.upperDir,
      layer.workDir,
      layer.mountPath
    );
  }

  async unmountLayer(layer: Layer): Promise<void> {
    await this.overlay.unmount(layer.mountPath);
  }

  /**
   * Build the chain of lower directories for a layer
   */
  private async buildLowerDirs(layer: Layer): Promise<string[]> {
    const dirs: string[] = [];

    // Walk up the parent chain, collecting upper dirs
    let current: Layer | null = layer;
    const parentUpperDirs: string[] = [];

    while (current?.parentId) {
      const parent = await this.getLayer(current.parentId);
      if (parent) {
        parentUpperDirs.unshift(parent.upperDir);
        current = parent;
      } else {
        break;
      }
    }

    // Get the entrypoint path
    const entrypoint = await this.getEntrypoint(layer.entrypointId);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${layer.entrypointId}`);
    }

    // Lower dirs: entrypoint first (bottom), then parent upper dirs in order
    dirs.push(entrypoint.path, ...parentUpperDirs);

    return dirs;
  }

  // ===========================================================================
  // User Mounts
  // ===========================================================================

  async createUserMount(input: CreateUserMountInput): Promise<UserMount> {
    const entrypoint = await this.getEntrypoint(input.entrypointId);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${input.entrypointId}`);
    }

    // Validate attached layer if specified
    if (input.attachedLayerId) {
      const layer = await this.getLayer(input.attachedLayerId);
      if (!layer) {
        throw new Error(`Layer not found: ${input.attachedLayerId}`);
      }
      if (layer.entrypointId !== input.entrypointId) {
        throw new Error('Attached layer must belong to the same entrypoint');
      }
    }

    const now = new Date();
    const id = randomUUID();

    // Create directories for this user mount
    const upperDir = join(this.config.dataDir, 'usermounts', id, 'upper');
    const workDir = join(this.config.dataDir, 'usermounts', id, 'work');

    mkdirSync(upperDir, { recursive: true });
    mkdirSync(workDir, { recursive: true });
    mkdirSync(input.mountPath, { recursive: true });

    const userMount: UserMount = {
      id,
      name: input.name,
      entrypointId: input.entrypointId,
      attachedLayerId: input.attachedLayerId ?? null,
      upperDir,
      workDir,
      mountPath: input.mountPath,
      createdAt: now,
      updatedAt: now,
    };

    await this.db.insert(userMounts).values({
      id: userMount.id,
      name: userMount.name,
      entrypointId: userMount.entrypointId,
      attachedLayerId: userMount.attachedLayerId,
      upperDir: userMount.upperDir,
      workDir: userMount.workDir,
      mountPath: userMount.mountPath,
      createdAt: now,
      updatedAt: now,
    });

    // Mount the user mount
    await this.mountUserMount(userMount);

    // Set up sync if attached to a layer
    if (userMount.attachedLayerId) {
      const layer = await this.getLayer(userMount.attachedLayerId);
      if (layer) {
        await this.syncManager.setupSync(userMount, layer);
      }
    }

    return userMount;
  }

  async getUserMount(id: string): Promise<UserMount | null> {
    const result = await this.db.query.userMounts.findFirst({
      where: eq(userMounts.id, id),
    });
    return result ?? null;
  }

  async getUserMountWithStatus(id: string): Promise<UserMountWithStatus | null> {
    const userMount = await this.getUserMount(id);
    if (!userMount) return null;

    const mountStatus = await this.overlay.getMountStatus(userMount.mountPath);
    const syncState = this.syncManager.getSyncState(userMount.id) ?? undefined;

    return { ...userMount, mountStatus, syncState };
  }

  async listUserMounts(entrypointId?: string): Promise<UserMount[]> {
    if (entrypointId) {
      return await this.db.query.userMounts.findMany({
        where: eq(userMounts.entrypointId, entrypointId),
      });
    }
    return await this.db.query.userMounts.findMany();
  }

  async listUserMountsWithStatus(
    entrypointId?: string
  ): Promise<UserMountWithStatus[]> {
    const mounts = await this.listUserMounts(entrypointId);
    return Promise.all(
      mounts.map(async (mount) => ({
        ...mount,
        mountStatus: await this.overlay.getMountStatus(mount.mountPath),
        syncState: this.syncManager.getSyncState(mount.id) ?? undefined,
      }))
    );
  }

  async deleteUserMount(id: string): Promise<void> {
    const userMount = await this.getUserMount(id);
    if (!userMount) {
      throw new Error(`User mount not found: ${id}`);
    }

    // Stop sync
    await this.syncManager.stopSync(id);

    // Unmount
    await this.unmountUserMount(userMount);

    // Delete from database
    await this.db.delete(userMounts).where(eq(userMounts.id, id));
  }

  async attachLayer(userMountId: string, layerId: string | null): Promise<void> {
    const userMount = await this.getUserMount(userMountId);
    if (!userMount) {
      throw new Error(`User mount not found: ${userMountId}`);
    }

    let layer: Layer | null = null;
    if (layerId) {
      layer = await this.getLayer(layerId);
      if (!layer) {
        throw new Error(`Layer not found: ${layerId}`);
      }
      if (layer.entrypointId !== userMount.entrypointId) {
        throw new Error('Layer must belong to the same entrypoint');
      }
    }

    // Update the database
    await this.db
      .update(userMounts)
      .set({
        attachedLayerId: layerId,
        updatedAt: new Date(),
      })
      .where(eq(userMounts.id, userMountId));

    // Update sync
    await this.syncManager.setupSync(
      { ...userMount, attachedLayerId: layerId },
      layer
    );
  }

  async mountUserMount(userMount: UserMount): Promise<void> {
    const entrypoint = await this.getEntrypoint(userMount.entrypointId);
    if (!entrypoint) {
      throw new Error(`Entrypoint not found: ${userMount.entrypointId}`);
    }

    // User mount is always based on the entrypoint
    // (sync engine handles copying from attached layer)
    await this.overlay.mount(
      [entrypoint.path],
      userMount.upperDir,
      userMount.workDir,
      userMount.mountPath
    );
  }

  async unmountUserMount(userMount: UserMount): Promise<void> {
    await this.overlay.unmount(userMount.mountPath);
  }

  // ===========================================================================
  // Terminal
  // ===========================================================================

  async openTerminal(path: string): Promise<void> {
    const commandParts = splitCommand(this.config.terminalCommand).map((part) =>
      part.replaceAll('{path}', path)
    );
    const command = commandParts.shift();
    if (!command) {
      throw new Error('Terminal command is empty');
    }
    try {
      // Detach the terminal process without a shell to avoid injection
      const child = spawn(command, commandParts, {
        detached: true,
        stdio: 'ignore',
      });
      child.unref();
    } catch (error) {
      const err = error as Error;
      throw new Error(`Failed to open terminal: ${err.message}`);
    }
  }

  // ===========================================================================
  // Startup / Shutdown
  // ===========================================================================

  /**
   * Remount all layers and user mounts on startup
   */
  async remountAll(): Promise<void> {
    // Remount layers (order matters - parents before children)
    const allLayers = await this.listLayers();

    // Sort layers so parents come before children
    const sortedLayers = this.sortLayersByDependency(allLayers);

    for (const layer of sortedLayers) {
      try {
        if (!(await this.overlay.isMounted(layer.mountPath))) {
          await this.mountLayer(layer);
        }
      } catch (error) {
        console.error(`Failed to mount layer ${layer.name}:`, error);
      }
    }

    // Remount user mounts
    const allMounts = await this.listUserMounts();
    for (const userMount of allMounts) {
      try {
        if (!(await this.overlay.isMounted(userMount.mountPath))) {
          await this.mountUserMount(userMount);
        }

        // Set up sync if attached
        if (userMount.attachedLayerId) {
          const layer = await this.getLayer(userMount.attachedLayerId);
          if (layer) {
            await this.syncManager.setupSync(userMount, layer);
          }
        }
      } catch (error) {
        console.error(`Failed to mount user mount ${userMount.name}:`, error);
      }
    }
  }

  /**
   * Unmount all and shut down
   */
  async shutdown(): Promise<void> {
    // Stop all sync engines
    await this.syncManager.shutdown();

    // Unmount user mounts first
    const allMounts = await this.listUserMounts();
    for (const userMount of allMounts) {
      try {
        await this.unmountUserMount(userMount);
      } catch (error) {
        console.error(`Failed to unmount user mount ${userMount.name}:`, error);
      }
    }

    // Unmount layers (children before parents)
    const allLayers = await this.listLayers();
    const sortedLayers = this.sortLayersByDependency(allLayers).reverse();

    for (const layer of sortedLayers) {
      try {
        await this.unmountLayer(layer);
      } catch (error) {
        console.error(`Failed to unmount layer ${layer.name}:`, error);
      }
    }
  }

  /**
   * Sort layers so parents come before children
   */
  private sortLayersByDependency(layersList: Layer[]): Layer[] {
    const result: Layer[] = [];
    const remaining = new Set(layersList.map((l) => l.id));
    const layerMap = new Map(layersList.map((l) => [l.id, l]));

    while (remaining.size > 0) {
      for (const id of remaining) {
        const layer = layerMap.get(id)!;
        // A layer can be added if it has no parent or its parent is already in result
        if (!layer.parentId || !remaining.has(layer.parentId)) {
          result.push(layer);
          remaining.delete(id);
        }
      }
    }

    return result;
  }
}

function splitCommand(command: string): string[] {
  const parts: string[] = [];
  let current = '';
  let quote: '"' | "'" | null = null;

  for (let i = 0; i < command.length; i += 1) {
    const char = command[i]!;

    if (quote) {
      if (char === quote) {
        quote = null;
        continue;
      }
      if (char === '\\' && quote === '"' && i + 1 < command.length) {
        current += command[i + 1]!;
        i += 1;
        continue;
      }
      current += char;
      continue;
    }

    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }

    if (/\s/.test(char)) {
      if (current) {
        parts.push(current);
        current = '';
      }
      continue;
    }

    if (char === '\\' && i + 1 < command.length) {
      current += command[i + 1]!;
      i += 1;
      continue;
    }

    current += char;
  }

  if (quote) {
    throw new Error('Terminal command has an unterminated quote');
  }

  if (current) {
    parts.push(current);
  }

  return parts;
}
