# Spaces Agent Notes

## Intention

Spaces is trying to provide OverlayFS-like behavior on systems where native OverlayFS is not available or is not a good fit, especially macOS.

The core idea is:

- keep the source tree and layer data as normal local files
- expose merged layer views through an in-process NFS server
- mount those exports locally with `mount_nfs`
- perform local filesystem operations against mounted paths so editor/dev-server watchers see real filesystem events

This is not a generic distributed filesystem. It is a local development tool that wants overlay semantics plus watcher compatibility.

## Repository Shape

- `packages/daemon-jvm`: JVM daemon, HTTP API, NFS server, overlay/VFS logic, replication logic
- `packages/web/bin/spaces.ts`: CLI used for daemon and data model operations
- `packages/web/src`: web UI and shared API client/types
- `scripts/qa-cli-daemon.sh`: canonical end-to-end CLI/daemon QA script
- `docs/cli-daemon-qa-checklist.md`: QA checklist and script usage

## Architecture

There are four major pieces.

### 1. Metadata + control plane

The daemon stores entrypoints, layers, and user mounts in SQLite.

- entrypoint: base local path
- layer: writable overlay delta with `upperDir`, `workDir`, and exported `mountPath`
- user mount: user-facing mounted path, optionally attached to a layer

The HTTP API in `Main.kt` and `SpacesService.kt` is the control plane used by the CLI and web UI.

### 2. NFS-backed overlay view

`SpacesVfs.kt` implements the dCache `VirtualFileSystem` and presents exports under:

- `/layers/<layerId>`
- `/mounts/<mountId>`

Each exported root is backed by an `OverlayView`:

- `entrypoint`: base tree
- `layers`: stack of upper dirs from current layer downward through parents

`OverlayEngine.kt` is the canonical source of overlay visibility semantics:

- path resolution across stacked layers
- lower-path resolution excluding the current top upper
- whiteout handling via `.wh.<name>`
- opaque directory marker support via `.wh..wh..opq`
- copy-up and parent directory materialization

Important rule: if overlay behavior changes, prefer changing `OverlayEngine.kt` first and making all consumers use it, rather than re-implementing layer logic ad hoc.

### 3. Local mount management

`MountManager.kt` mounts daemon exports back onto local paths with `mount_nfs`.

This is the mechanism that makes external tools interact with normal local-looking paths instead of talking to the daemon directly.

### 4. Watcher-visible replication

When writes happen through one mounted view, other mounted views attached to the same underlying layer need to observe the change and external recursive watchers need to wake up.

This is handled by:

- `SpacesVfs.emitOp(...)`
- `ReplicationService` in `Replication.kt`
- `SpacesVfs.replay(...)`

The current approach is:

- read source state through the in-process overlay model, not through mounted NFS paths
- for safe cases, replay changes onto sibling mounted paths to surface local filesystem events
- for some destructive or attach-switch cases, emit transient pulse files like `.spaces-reload-pulse.*` at mount root instead of replaying content destructively

That distinction matters. Replaying destructive ops blindly into a user mount that is attached to the same underlying layer can create echo loops or incorrect visible state.

## Data Model

### Entrypoint

The immutable base path for a stack.

### Layer

A writable delta on top of:

- its parent layer view, if it has a parent
- otherwise the entrypoint

The layer's `upperDir` is authoritative writable state. The `workDir` exists for overlay-style bookkeeping, even though the current implementation is custom rather than kernel OverlayFS.

### User mount

A user-facing mount path that is either:

- detached: read-only view of the entrypoint
- attached to a layer: writable mounted view of that layer stack

Current design intent is "alternate user-facing mounted view of a layer", not a separate persistent writable overlay on top of a layer. The code and tests now assume that.

## Overlay Semantics That Must Hold

These are the invariants to protect.

- Creating a file/dir/link/symlink over any already-visible lower path must fail.
- Deleting a lower-visible path must create a whiteout in the top upper so the path stays hidden.
- Deleting a top-upper file that shadows lower content must also leave a whiteout.
- Renaming a lower-visible path must copy it up into the top upper and whiteout the old path.
- Child-layer diff must compare against the parent layer view, not always directly against the entrypoint.
- Replay of directory state must remove stale target entries, not only copy new ones.
- Replay must avoid echoing destructive operations back into attached user mounts that share the same underlying layer.

If any of these regress, the system will look correct in simple happy paths but fail under real editing workflows.

## Key Implementation Details

### Overlay resolution

`OverlayEngine.resolvePath(...)` resolves the visible path through:

1. top upper
2. parent uppers in order
3. entrypoint

`resolveLowerPath(...)` does the same thing but excludes the current top upper. This is what copy-up, delete, and rename logic should use when deciding whether lower content exists.

### Whiteouts and opaque dirs

- whiteout marker: `.wh.<name>`
- opaque marker: `.wh..wh..opq`

Root-level whiteouts matter too. Do not assume a whiteout always lives in a non-root parent directory.

### VFS operations

`SpacesVfs.kt` contains the behavior that clients actually hit over NFS:

- `create`, `mkdir`, `link`, `symlink`: must respect already-visible lower paths
- `remove`: must whiteout lower-visible content
- `move`: must preserve rename semantics for inherited files
- `getattr`, `lookup`, `list`: must reflect merged overlay state
- `setattr`, `write`: must copy up before mutating lower-visible files

### Attach invalidation

`invalidateMountForLayerSwitch(...)` intentionally uses a transient pulse file instead of touching real user content during layer switches. Touching real files through NFS here can cause accidental copy-up and pin stale content into the wrong upper.

### Replay behavior

`replay(...)` now has three modes conceptually:

- normal replay for non-destructive updates
- convergent directory replay for subtree updates
- pulse-only invalidation for destructive operations that target a user mount attached to the same underlying layer

If you change replay behavior, re-run the CLI/daemon QA script. Unit tests alone are not enough.

## Testing Strategy

### Unit tests

`packages/daemon-jvm/src/test/kotlin/com/spaces/daemon/DaemonOverlayTest.kt`

Covers:

- inherited delete semantics
- inherited rename semantics
- create/mkdir over lower-visible paths
- parent-aware layer diff
- replay cleanup of stale directory entries

### End-to-end tests

Canonical command:

```bash
pnpm qa:cli-daemon
```

This script:

- builds release artifacts
- launches the packaged daemon
- mounts live NFS exports locally
- exercises CLI flows and watcher-sensitive behavior

Watcher-sensitive checks use `fs.watch(...)` from Node on mounted paths. Content convergence checks use polling helpers after the event.

Useful env var while iterating on the script:

```bash
SPACES_QA_BUILD_ARTIFACTS=0 pnpm qa:cli-daemon
```

## Guidance For Future Agents

- Prefer targeted fixes over broad rewrites unless overlay semantics are being fundamentally redesigned.
- Do not add alternate overlay logic in `SpacesService.kt`, `Replication.kt`, or the CLI when `OverlayEngine.kt` should own it.
- Be careful with changes that touch user mounts attached to layers. Shared underlying layer state is subtle.
- If a change affects watcher behavior, validate both the daemon unit tests and `pnpm qa:cli-daemon`.
- Treat the QA script as the canonical integration test, not as optional documentation.
