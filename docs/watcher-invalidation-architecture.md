# Watcher Invalidation Architecture

Spaces avoids transient marker files for watcher invalidation. Marker files are brittle because they add synthetic filesystem entries that editors, dev servers, and cleanup tools can observe or race with.

The current invalidation model uses real local filesystem events only when replaying safe content changes. When replaying into a user mount attached to the same underlying layer would echo a destructive or shape-changing operation back into that layer, the daemon invalidates the in-process NFS path identity instead.

## Path Generation Invariant

For user mounts under `/mounts/<mountId>`, VFS file handles include:

- the user mount generation from SQLite
- a per-path invalidation generation

When a same-layer attached mount needs to observe a path change without replaying content into its own mounted directory, `SpacesVfs.invalidateMountPath(...)` bumps the per-path generation for the changed path and its parent. New lookups return different NFS handles and different stat file IDs/generations. Old handles become stale.

This gives the macOS NFS client a real metadata identity change without creating `.spaces-*` files and without touching user content.

Layer exports under `/layers/<layerId>` do not use per-path invalidation generations. They are the source view for writes and replication, so their handles stay stable across same-layer user-mount invalidations.

## Lifecycle

Path generations are mount-local state:

- attach/detach clears the mount's path generations after a successful remount
- deleting a user mount clears its path generations
- mount generation changes still invalidate old handles across layer switches

## Required Coverage

Changes in this area should keep tests for:

- same-layer invalidation does not materialize content into the attached user mount path
- same-layer invalidation changes user-mount path identity
- old user-mount handles become stale after invalidation
- layer-mount identity is unaffected by same-layer user-mount invalidation
- `pnpm qa:cli-daemon` passes, because unit tests do not exercise macOS NFS client behavior
