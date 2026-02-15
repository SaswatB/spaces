# CLI + Daemon QA Checklist

## Scope

This checklist validates the `spaces` CLI and daemon integration (not web UI).

It covers:
- daemon lifecycle commands
- root/system commands
- entrypoint/layer/mount CRUD flows
- alias/flag behavior (`e`, `--mount-path`, `--force`, `--json`)

## Prerequisites

- Node 20+
- `pnpm`
- `bun` (for CLI single-file artifact build)
- `curl`
- macOS/Linux shell

No system Java 21 is required if using the bundled-runtime artifact path below.

## Quick Run

Use the scripted QA pass:

```bash
pnpm qa:cli-daemon
```

This script:
- builds production artifacts
- extracts bundled-runtime `spacesd`
- runs the CLI matrix against an isolated temporary data dir
- prints `PASS/XFAIL/FAIL` summary

Script location: `scripts/qa-cli-daemon.sh`.

## Manual Smoke Sequence

1. Build artifacts:

```bash
pnpm release:artifacts
```

2. Extract daemon artifact and point CLI to extracted `spacesd`.

3. Run:

```bash
pnpm --filter @spaces/web exec tsx bin/spaces.ts daemon start
pnpm --filter @spaces/web exec tsx bin/spaces.ts daemon status --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts status --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts remount --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts e list --json
```

4. Create/delete flow:

```bash
pnpm --filter @spaces/web exec tsx bin/spaces.ts entrypoint create --path <path> --name <name> --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts layer create --entrypoint <ep_id> --name <name> --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts mount create --name <name> --mount-path <path> --entrypoint <ep_id> --layer <layer_id> --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts mount delete <mount_id> --force --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts layer delete <layer_id> --force --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts entrypoint delete <ep_id> --force --json
pnpm --filter @spaces/web exec tsx bin/spaces.ts daemon stop
```

## Expected Outcomes

- `daemon status --json` reflects PID/running transitions.
- `status --json` returns counts.
- `remount --json` returns `{ "ok": true }`.
- create commands honor explicit flags (`--name`, `--entrypoint`, `--mount-path`, `--layer`).
- destructive operations work with `--force` in non-interactive runs.

## Troubleshooting

- If daemon start fails with Java class-version errors, ensure `SPACES_DAEMON_PATH` points to bundled `spacesd` artifact instead of Gradle’s `spaces-daemon` launcher.
- If mounts fail with permission-related errors (`mount_nfs`, `umount`), classify as environment-limited (`XFAIL`) in local QA.
