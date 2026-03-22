#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${SPACES_VERSION:-0.1.0}"
BUILD_ARTIFACTS="${SPACES_BENCH_BUILD_ARTIFACTS:-1}"
FILE_COUNT="${SPACES_BENCH_FILE_COUNT:-300}"
API_PORT="${SPACES_BENCH_API_PORT:-$((36000 + ($$ % 1000)))}"
NFS_PORT="${SPACES_BENCH_NFS_PORT:-$((15000 + ($$ % 1000)))}"
WAIT_STEP_SECONDS="${SPACES_BENCH_WAIT_STEP_SECONDS:-0.05}"
WAIT_ATTEMPTS="${SPACES_BENCH_WAIT_ATTEMPTS:-400}"
WORK_ROOT="$(mktemp -d /tmp/spaces-bench-script.XXXXXX)"
ENTRY="$WORK_ROOT/entry"
MOUNTDIR="$WORK_ROOT/mount"
STATE="$WORK_ROOT/state"
DATA="$WORK_ROOT/data"
DBDIR="$WORK_ROOT/db"
RUNTIME_ROOT="$WORK_ROOT/runtime"

mkdir -p "$ENTRY" "$MOUNTDIR" "$STATE" "$DATA" "$DBDIR" "$RUNTIME_ROOT"

ps aux | awk '/spacesd-runtime\/bin\/java/ && /\/tmp\/spaces-bench-script\./ {print $2}' | xargs -I{} kill -9 {} 2>/dev/null || true
mount \
  | sed -n 's|.* on \(/[^ ]*spaces-bench-script\.[^ ]*\) (nfs.*|\1|p' \
  | sort -r \
  | while read -r mnt; do
      [ -n "$mnt" ] || continue
      umount -f "$mnt" >/dev/null 2>&1 || true
    done

if [ "$BUILD_ARTIFACTS" = "1" ]; then
  printf "Building release artifacts for bundled-runtime daemon...\n"
  SPACES_VERSION="$VERSION" "$ROOT_DIR/scripts/build-release-artifacts.sh" >/tmp/spaces-bench-build.log 2>&1
else
  printf "Skipping release artifact build (SPACES_BENCH_BUILD_ARTIFACTS=%s)\n" "$BUILD_ARTIFACTS"
fi

SUFFIX="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m | sed 's/x86_64/x64/;s/aarch64/arm64/;s/arm64/arm64/')"
DAEMON_TGZ="$ROOT_DIR/release/spacesd-$VERSION-$SUFFIX.tar.gz"
tar -xzf "$DAEMON_TGZ" -C "$RUNTIME_ROOT"

export SPACES_DAEMON_PATH="$RUNTIME_ROOT/spacesd"
export SPACES_API_URL="http://127.0.0.1:$API_PORT"
export SPACES_STATE_DIR="$STATE"
export SPACES_DATA_DIR="$DATA"
export SPACES_DB_PATH="$DBDIR/spaces.db"
export SPACES_API_HOST="127.0.0.1"
export SPACES_API_PORT="$API_PORT"
export SPACES_NFS_HOST="127.0.0.1"
export SPACES_NFS_PORT="$NFS_PORT"
export SPACES_API_TIMEOUT_MS="${SPACES_BENCH_API_TIMEOUT_MS:-8000}"

CMD="env -u NODE_OPTIONS pnpm --filter @spaces/web exec tsx bin/spaces.ts"

cleanup() {
  sh -lc "$CMD daemon stop" >/dev/null 2>&1 || true
  return 0
}
trap cleanup EXIT INT TERM

json_field() {
  field="$1"
  env -u NODE_OPTIONS node -e 'const fs=require("fs");const field=process.argv[1];const data=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(String(data[field] || ""))' "$field"
}

wait_for_content() {
  path="$1"
  expected="$2"
  for _ in $(seq 1 "$WAIT_ATTEMPTS"); do
    if [ -f "$path" ] && [ "$(cat "$path" 2>/dev/null || true)" = "$expected" ]; then
      return 0
    fi
    sleep "$WAIT_STEP_SECONDS"
  done
  return 1
}

wait_for_path_exists() {
  path="$1"
  for _ in $(seq 1 "$WAIT_ATTEMPTS"); do
    if [ -e "$path" ]; then
      return 0
    fi
    sleep "$WAIT_STEP_SECONDS"
  done
  return 1
}

ms_now() {
  env -u NODE_OPTIONS node -e 'process.stdout.write(String(Date.now()))'
}

printf "Preparing %s files per layer...\n" "$FILE_COUNT"
for i in $(seq 1 "$FILE_COUNT"); do
  printf "entry-%s\n" "$i" > "$ENTRY/file-$i.txt"
done
mkdir -p "$ENTRY/nested/deep"
printf "entry-bench\n" > "$ENTRY/nested/deep/marker.txt"

printf "Starting daemon...\n"
sh -lc "$CMD daemon start" >/dev/null
for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$API_PORT/system/health" >/dev/null 2>&1 && break
  sleep 0.2
done

EP_JSON="$(sh -lc "$CMD entrypoint create --path '$ENTRY' --name bench-entry --json")"
EP_ID="$(printf "%s" "$EP_JSON" | json_field id)"
L1_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name bench-layer-a --json")"
L2_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name bench-layer-b --json")"
L1_ID="$(printf "%s" "$L1_JSON" | json_field id)"
L2_ID="$(printf "%s" "$L2_JSON" | json_field id)"
L1_UPPER="$(printf "%s" "$L1_JSON" | json_field upperDir)"
L2_UPPER="$(printf "%s" "$L2_JSON" | json_field upperDir)"
L2_MOUNT="$(printf "%s" "$L2_JSON" | json_field mountPath)"

for i in $(seq 1 "$FILE_COUNT"); do
  printf "layer-a-%s\n" "$i" > "$L1_UPPER/file-$i.txt"
  printf "layer-b-%s\n" "$i" > "$L2_UPPER/file-$i.txt"
done
mkdir -p "$L1_UPPER/nested/deep" "$L2_UPPER/nested/deep"
printf "layer-a-marker\n" > "$L1_UPPER/nested/deep/marker.txt"
printf "layer-b-marker\n" > "$L2_UPPER/nested/deep/marker.txt"

MOUNT_JSON="$(sh -lc "$CMD mount create --name bench-mount --mount-path '$MOUNTDIR' --entrypoint '$EP_ID' --layer '$L1_ID' --json")"
MOUNT_ID="$(printf "%s" "$MOUNT_JSON" | json_field id)"

printf "Benchmarking attach switch across %s files...\n" "$FILE_COUNT"
attach_start="$(ms_now)"
sh -lc "$CMD mount attach '$MOUNT_ID' '$L2_ID' --json" >/dev/null
wait_for_content "$MOUNTDIR/file-$FILE_COUNT.txt" "layer-b-$FILE_COUNT"
wait_for_content "$MOUNTDIR/nested/deep/marker.txt" "layer-b-marker"
attach_end="$(ms_now)"
attach_ms=$((attach_end - attach_start))

printf "Benchmarking replay from layer mount into fixed user mount path...\n"
sh -lc "$CMD layer mount '$L2_ID' --json" >/dev/null
wait_for_path_exists "$L2_MOUNT"
replay_start="$(ms_now)"
for i in $(seq 1 "$FILE_COUNT"); do
  printf "replay-%s\n" "$i" > "$L2_MOUNT/replay-$i.txt"
done
wait_for_content "$MOUNTDIR/replay-$FILE_COUNT.txt" "replay-$FILE_COUNT"
replay_end="$(ms_now)"
replay_ms=$((replay_end - replay_start))

printf "RESULT attach_switch_ms=%s replay_write_ms=%s file_count=%s\n" "$attach_ms" "$replay_ms" "$FILE_COUNT"
