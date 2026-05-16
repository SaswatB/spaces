# Spaces

Spaces provides OverlayFS-like local development layers on macOS. It keeps the source tree and layer data as normal local files, serves merged views from a local daemon, and mounts those views back onto local paths so editors and dev servers see real filesystem events.

Spaces is meant for local development workflows where you want to switch or compare writable views of the same repo without copying the whole tree.

## Install

Install the latest macOS release:

```bash
curl -fsSL https://raw.githubusercontent.com/SaswatB/spaces/main/scripts/install.sh | bash
```

The installer places `spaces`, `spacesd`, `spaces-uninstall`, and the bundled daemon runtime in `~/.local/bin` by default. Set `SPACES_BIN_DIR` to install somewhere else:

```bash
SPACES_BIN_DIR=/usr/local/bin bash -c "$(curl -fsSL https://raw.githubusercontent.com/SaswatB/spaces/main/scripts/install.sh)"
```

Uninstall:

```bash
spaces-uninstall
```

The installer asks whether to enable daily update notices. The default is off; non-interactive installs leave it off. To preselect the answer:

```bash
curl -fsSL https://raw.githubusercontent.com/SaswatB/spaces/main/scripts/install.sh | SPACES_AUTO_UPDATE_CHECK=1 bash
```

Spaces releases are currently macOS-only.

## Usage

Start the daemon and check status:

```bash
spaces daemon start
spaces status
```

Register a source tree as an entrypoint:

```bash
spaces entrypoint create --path /path/to/repo --name repo
```

Create a writable layer on top of that entrypoint:

```bash
spaces layer create --entrypoint repo --name feature-a
```

The layer command prints a mounted path for the merged view. You can edit files there directly, or create a stable user-facing mount that can be reattached to different layers:

```bash
mkdir -p ~/spaces/repo
spaces mount create repo-dev ~/spaces/repo --entrypoint repo --layer feature-a
```

Point your editor or dev server at the stable mount path:

```bash
cd ~/spaces/repo
```

Create another layer and switch the stable mount to it:

```bash
spaces layer create --entrypoint repo --name feature-b
spaces mount attach repo-dev feature-b
```

Useful inspection commands:

```bash
spaces entrypoint list
spaces layer list
spaces mount list
spaces layer diff feature-b
```

Cleanup when you are done:

```bash
spaces mount delete repo-dev --force
spaces layer delete feature-a --force
spaces layer delete feature-b --force
spaces entrypoint delete repo --force
spaces daemon stop
```

Update notices are opt-in and run at most once per day when the CLI is used. View the config path and current settings:

```bash
spaces config
```

To check or update manually:

```bash
spaces update --check
spaces update
```

Most commands accept `--json` for structured output. Destructive commands require confirmation unless `--force` is passed.

## Development

Install dependencies:

```bash
pnpm install
```

Common commands:

```bash
pnpm dev
pnpm dev:daemon
pnpm dev:web
pnpm cli -- status
pnpm typecheck
pnpm openapi:check
pnpm qa:cli-daemon
```

- `pnpm dev`: run the daemon and web app in dev mode.
- `pnpm dev:daemon`: run the JVM daemon from source.
- `pnpm dev:web`: run the web UI.
- `pnpm cli -- <args>`: run the Spaces CLI from source.
- `pnpm typecheck`: run TypeScript checks.
- `pnpm openapi:check`: verify generated OpenAPI client files are current.
- `pnpm openapi:generate`: refresh web OpenAPI files from daemon resources.
- `pnpm qa:cli-daemon`: build/run packaged daemon artifacts and execute the main e2e suite.
- `pnpm bench:cli-daemon`: run CLI/daemon benchmark coverage.

## Repository Layout

- `packages/daemon-jvm`: JVM daemon, HTTP API, NFS server, overlay/VFS logic, and replication.
- `packages/web/bin/spaces.ts`: CLI for daemon and data model operations.
- `packages/web/src`: web UI and shared API client/types.
- `scripts/qa-cli-daemon.sh`: canonical CLI/daemon end-to-end QA.
- `docs/`: focused architecture, QA, and release notes.

## Releases

```bash
pnpm release:artifacts
pnpm release:tag
pnpm release:tag patch
pnpm install:release
pnpm uninstall:release
```

- `pnpm release:artifacts`: build a local release tarball for the current platform.
- `pnpm release:tag [major|minor|patch]`: bump versions, commit `Release vX.Y.Z`, create an annotated `vX.Y.Z` tag, and push the branch plus tag. Default bump is `minor`.
- `pnpm install:release`: install the latest published release, or `SPACES_VERSION=<version> pnpm install:release`.
- `pnpm uninstall:release`: remove installed `spaces`, `spaces-uninstall`, `spacesd`, and bundled daemon runtime files.

Pushing a `v*` tag triggers the GitHub Actions release workflow, which builds macOS artifacts and publishes them to a GitHub Release.

## More Detail

- [CLI/daemon QA checklist](docs/cli-daemon-qa-checklist.md)
- [Release updates](docs/release-update.md)
- [Watcher invalidation architecture](docs/watcher-invalidation-architecture.md)
