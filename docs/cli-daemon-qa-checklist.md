# CLI + Daemon QA Checklist

## Scope

This checklist validates the `spaces` CLI and daemon integration (not web UI).

It covers:
- daemon lifecycle commands
- root/system commands
- entrypoint/layer/mount CRUD flows
- alias/flag behavior (`e`, `--mount-path`, `--force`, `--json`)
- watcher-sensitive layer attach flows (switch, detach/reattach, no-op attach)
- layer-switch delta semantics (add/remove/rename + nested subtree content)
- CLI validation failures (unknown flags, cross-entrypoint attach, non-interactive delete without `--force`)

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
- exits nonzero when any hard `FAIL` occurs

High-value automated QA assertions include:
- `mount attach` to same layer is a no-op for watcher signal and content.
- `mount attach` switch updates watched content and path deltas (add/remove/rename).
- parent-layer-chain attach switch updates effective content and should emit watcher signal.
- detached -> attach switch updates content and should emit watcher signal.
- deleting an inherited parent-layer path through a child-layer mount keeps it hidden in both the layer view and attached user mount.
- renaming an inherited parent-layer path through a child-layer mount hides the old path and surfaces the new path in both views.
- destructive non-interactive commands require `--force`.
- unknown `create` flags are rejected.
- cross-entrypoint mount attach is rejected.

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
- Set `SPACES_QA_BUILD_ARTIFACTS=0` to reuse an existing `release/` build while iterating on the QA script.
