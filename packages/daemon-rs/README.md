# Spaces Rust Daemon (Draft)

This is a Rust rewrite of the Spaces daemon that will serve overlay views over NFSv3 on macOS.

## Goals
- Replace overlayfs with a userspace overlay implementation.
- Export overlay views via NFS (using `nfsserve`).
- Replicate file operations across mounts so client watchers see local events.

## Runtime expectations (macOS)
- Run the daemon as root (required for NFS and mounting).
- User-visible mount paths are backed by NFS exports; the daemon will replay ops by writing to these paths.

## NFS layout
The NFS server exposes a virtual tree rooted at `/spaces` (configurable via `SPACES_NFS_EXPORT_ROOT`):
- `/spaces/mounts/<user-mount-id>` (user mount views)
- `/spaces/layers/<layer-id>` (layer views)

Mount operations map these export paths to the local mount paths stored in the database.

## Environment variables
- `SPACES_DATA_DIR` (default `/var/lib/spaces`)
- `SPACES_DB_PATH` (default `${SPACES_DATA_DIR}/spaces.db`)
- `SPACES_API_HOST` / `SPACES_API_PORT` (default `127.0.0.1:3100`)
- `SPACES_NFS_HOST` / `SPACES_NFS_PORT` (default `127.0.0.1:11111`)
- `SPACES_NFS_EXPORT_ROOT` (default `/spaces`)
- `SPACES_AUTH_TOKEN` (optional)

## OpenAPI
- Serve OpenAPI JSON at `http://<api-host>:<api-port>/openapi.json`.
- Generate web types with `pnpm openapi:generate` from the repo root.
- Run the CLI with `pnpm cli` (uses `packages/web/bin/spaces.ts`).

## Status
This is a scaffold with placeholders for:
- Overlay resolution, whiteout/opaque handling, copy-up (partially implemented).
- NFS server implementation and mount orchestration (running, but not fully battle-tested).
- Replication engine that replays ops across mounts (suppression added; daemon UID/GID hooks pending).
- API endpoints for entrypoints/layers/user mounts (REST scaffold).
