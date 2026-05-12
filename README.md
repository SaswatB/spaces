# Spaces

Spaces provides OverlayFS-like local development layers on systems where native OverlayFS is unavailable or awkward, especially macOS. It keeps source trees and layer data as normal local files, serves merged views through an in-process NFS daemon, and mounts those exports locally so editors and dev servers see real filesystem events.

## Repository

- `packages/daemon-jvm`: JVM daemon, HTTP API, NFS server, overlay/VFS logic, and replication.
- `packages/web/bin/spaces.ts`: CLI for daemon and data model operations.
- `packages/web/src`: web UI and shared API client/types.
- `scripts/qa-cli-daemon.sh`: canonical CLI/daemon end-to-end QA.
- `docs/`: focused architecture and release notes.

## Common Commands

```bash
pnpm install
pnpm typecheck
pnpm dev
pnpm cli -- status
```

Useful scripts:

- `pnpm dev`: run daemon and web app in dev mode.
- `pnpm dev:daemon`: run the JVM daemon from source.
- `pnpm dev:web`: run the web UI.
- `pnpm cli -- <args>`: run the Spaces CLI from source.
- `pnpm typecheck`: run TypeScript checks.
- `pnpm openapi:check`: verify generated OpenAPI client files are current.
- `pnpm openapi:generate`: refresh web OpenAPI files from daemon resources.
- `pnpm qa:cli-daemon`: build/run packaged daemon artifacts and execute the main e2e suite.
- `pnpm bench:cli-daemon`: run CLI/daemon benchmark coverage.

## Release Scripts

```bash
pnpm release:artifacts
pnpm release:tag
pnpm release:tag patch
pnpm install:release
pnpm uninstall:release
```

- `pnpm release:artifacts`: build local release tarballs for the current platform.
- `pnpm release:tag [major|minor|patch]`: bump versions, commit `Release vX.Y.Z`, create an annotated `vX.Y.Z` tag, and push the branch plus tag. Default bump is `minor`.
- `pnpm install:release`: install published release artifacts. Requires `SPACES_VERSION` and `SPACES_RELEASE_BASE_URL`.
- `pnpm uninstall:release`: remove installed `spaces`, `spacesd`, and bundled daemon runtime files.

Pushing a `v*` tag triggers the GitHub Actions release workflow, which builds macOS artifacts and publishes them to a GitHub Release.

## More Detail

- [CLI/daemon QA checklist](docs/cli-daemon-qa-checklist.md)
- [Release updates](docs/release-update.md)
- [Watcher invalidation architecture](docs/watcher-invalidation-architecture.md)
