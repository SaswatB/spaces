import { z } from 'zod';

// ============================================================================
// Core Domain Types
// ============================================================================

/**
 * An Entrypoint is a base directory that serves as the foundation for layers.
 * It's the "lower" directory in overlayfs terms - the read-only base.
 */
export const EntrypointSchema = z.object({
  id: z.string(),
  name: z.string().min(1),
  path: z.string().min(1), // Absolute path to the base directory
  createdAt: z.date(),
  updatedAt: z.date(),
});

export type Entrypoint = z.infer<typeof EntrypointSchema>;

/**
 * A Layer represents a set of changes on top of an entrypoint or another layer.
 * It has its own upper directory where modifications are stored.
 * Layers get an automatic mount at their mountPath.
 */
export const LayerSchema = z.object({
  id: z.string(),
  name: z.string().min(1),
  entrypointId: z.string(), // The root entrypoint this layer chain belongs to
  parentId: z.string().nullable(), // null = directly on entrypoint, otherwise parent layer ID
  upperDir: z.string().min(1), // Where changes are stored
  workDir: z.string().min(1), // Overlayfs work directory
  mountPath: z.string().min(1), // Where this layer is mounted
  createdAt: z.date(),
  updatedAt: z.date(),
});

export type Layer = z.infer<typeof LayerSchema>;

/**
 * A UserMount is a well-known path that can be attached to a layer.
 * It has its own upper directory and syncs bidirectionally with the attached layer.
 * This allows hot-switching between layers without remounting.
 */
export const UserMountSchema = z.object({
  id: z.string(),
  name: z.string().min(1),
  entrypointId: z.string(), // The entrypoint this mount is based on
  attachedLayerId: z.string().nullable(), // Which layer changes sync to/from (null = no sync)
  upperDir: z.string().min(1), // This mount's own upper directory
  workDir: z.string().min(1), // Overlayfs work directory
  mountPath: z.string().min(1), // The well-known path (e.g., ~/projects/myapp)
  createdAt: z.date(),
  updatedAt: z.date(),
});

export type UserMount = z.infer<typeof UserMountSchema>;

// ============================================================================
// Sync Types
// ============================================================================

export const SyncStatusSchema = z.enum([
  'idle', // No sync in progress
  'syncing', // Sync in progress
  'error', // Sync failed
]);

export type SyncStatus = z.infer<typeof SyncStatusSchema>;

export const FileChangeTypeSchema = z.enum([
  'add', // New file
  'modify', // Modified file
  'delete', // Deleted file (whiteout in overlayfs)
]);

export type FileChangeType = z.infer<typeof FileChangeTypeSchema>;

export const FileChangeSchema = z.object({
  type: FileChangeTypeSchema,
  relativePath: z.string(),
  timestamp: z.date(),
});

export type FileChange = z.infer<typeof FileChangeSchema>;

export const SyncStateSchema = z.object({
  userMountId: z.string(),
  status: SyncStatusSchema,
  lastSyncedAt: z.date().nullable(),
  pendingChanges: z.array(FileChangeSchema),
  error: z.string().nullable(),
});

export type SyncState = z.infer<typeof SyncStateSchema>;

// ============================================================================
// Mount Status Types
// ============================================================================

export const MountStatusSchema = z.enum([
  'mounted',
  'unmounted',
  'error',
]);

export type MountStatus = z.infer<typeof MountStatusSchema>;

export const LayerWithStatusSchema = LayerSchema.extend({
  mountStatus: MountStatusSchema,
});

export type LayerWithStatus = z.infer<typeof LayerWithStatusSchema>;

export const UserMountWithStatusSchema = UserMountSchema.extend({
  mountStatus: MountStatusSchema,
  syncState: SyncStateSchema.optional(),
});

export type UserMountWithStatus = z.infer<typeof UserMountWithStatusSchema>;

// ============================================================================
// Configuration
// ============================================================================

export const ConfigSchema = z.object({
  // Base directory for spaces data (upper dirs, work dirs, etc.)
  dataDir: z.string().default('/var/lib/spaces'),
  // Database path
  dbPath: z.string().default('/var/lib/spaces/spaces.db'),
  // Terminal command to spawn (with {path} placeholder as a full argument)
  terminalCommand: z
    .string()
    .default('x-terminal-emulator -e sh -c \'cd "$1" && exec "$SHELL"\' -- {path}'),
  // Sync debounce interval in milliseconds
  syncDebounceMs: z.number().default(100),
  // Patterns to ignore during sync (like .gitignore patterns)
  syncIgnorePatterns: z.array(z.string()).default([
    'node_modules',
    '.git',
    '*.swp',
    '*.swo',
    '*~',
    '.DS_Store',
  ]),
  // API bind host
  apiHost: z.string().default('127.0.0.1'),
  // Allowed CORS origin for the web UI
  corsOrigin: z.string().default('http://localhost:3000'),
  // Optional auth token to require for API calls
  authToken: z.string().nullable().default(null),
});

export type Config = z.infer<typeof ConfigSchema>;

// ============================================================================
// API Input/Output Types
// ============================================================================

export const CreateEntrypointInputSchema = z.object({
  name: z.string().min(1).optional(),
  path: z.string().min(1),
});

export type CreateEntrypointInput = z.infer<typeof CreateEntrypointInputSchema>;

export const CreateLayerInputSchema = z.object({
  name: z.string().min(1).optional(),
  entrypointId: z.string(),
  parentId: z.string().nullable(),
  mountPath: z.string().min(1).optional(), // Auto-generated if not provided
});

export type CreateLayerInput = z.infer<typeof CreateLayerInputSchema>;

export const CreateUserMountInputSchema = z.object({
  name: z.string().min(1),
  entrypointId: z.string(),
  mountPath: z.string().min(1),
  attachedLayerId: z.string().nullable(),
});

export type CreateUserMountInput = z.infer<typeof CreateUserMountInputSchema>;

export const AttachLayerInputSchema = z.object({
  userMountId: z.string(),
  layerId: z.string().nullable(), // null to detach
});

export type AttachLayerInput = z.infer<typeof AttachLayerInputSchema>;
