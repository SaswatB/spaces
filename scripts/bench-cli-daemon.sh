#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${SPACES_VERSION:-0.1.0}"
BUILD_ARTIFACTS="${SPACES_BENCH_BUILD_ARTIFACTS:-1}"
FILE_COUNTS="${SPACES_BENCH_FILE_COUNTS:-${SPACES_BENCH_FILE_COUNT:-40}}"
FANOUT_MOUNTS="${SPACES_BENCH_FANOUT_MOUNTS:-3}"
API_PORT="${SPACES_BENCH_API_PORT:-$((36000 + ($$ % 1000)))}"
NFS_PORT="${SPACES_BENCH_NFS_PORT:-$((15000 + ($$ % 1000)))}"
WAIT_STEP_SECONDS="${SPACES_BENCH_WAIT_STEP_SECONDS:-0.05}"
WAIT_ATTEMPTS="${SPACES_BENCH_WAIT_ATTEMPTS:-400}"
WORK_ROOT="$(mktemp -d /tmp/spaces-bench-script.XXXXXX)"
STATE="$WORK_ROOT/state"
DATA="$WORK_ROOT/data"
DBDIR="$WORK_ROOT/db"
RUNTIME_ROOT="$WORK_ROOT/runtime"
ENTRY_ROOT="$WORK_ROOT/entries"
MOUNT_ROOT="$WORK_ROOT/mounts"

mkdir -p "$STATE" "$DATA" "$DBDIR" "$RUNTIME_ROOT" "$ENTRY_ROOT" "$MOUNT_ROOT"

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
RELEASE_TGZ="$ROOT_DIR/release/spaces-$VERSION-$SUFFIX.tar.gz"
tar -xzf "$RELEASE_TGZ" -C "$RUNTIME_ROOT"

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
export SPACES_PERF_TRACE="${SPACES_PERF_TRACE:-1}"

cleanup() {
  sh -lc "$CMD daemon stop" >/dev/null 2>&1 || true
  return 0
}
trap cleanup EXIT INT TERM

json_field() {
  field="$1"
  env -u NODE_OPTIONS node -e 'const fs=require("fs");const field=process.argv[1];const data=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(String(data[field] || ""))' "$field"
}

ms_now() {
  env -u NODE_OPTIONS node -e 'process.stdout.write(String(Date.now()))'
}

perf_reset() {
  curl -sf -X POST "http://127.0.0.1:$API_PORT/system/perf/reset" >/dev/null
}

wait_for_replication_idle() {
  for _ in $(seq 1 "$WAIT_ATTEMPTS"); do
    body="$(curl -sf "http://127.0.0.1:$API_PORT/system/replication-idle" 2>/dev/null || true)"
    if [ -n "$body" ] && printf "%s" "$body" \
      | env -u NODE_OPTIONS node -e '
const fs = require("fs");
const body = JSON.parse(fs.readFileSync(0, "utf8"));
process.exit(body.idle ? 0 : 1);
'; then
      return 0
    fi
    sleep "$WAIT_STEP_SECONDS"
  done
  return 1
}

perf_summary() {
  label="$1"
  phase="$2"
  file_count="$3"
  curl -sf "http://127.0.0.1:$API_PORT/system/perf" \
    | env -u NODE_OPTIONS node -e '
const fs = require("fs");
const label = process.argv[1];
const phase = process.argv[2];
const fileCount = process.argv[3];
const metrics = JSON.parse(fs.readFileSync(0, "utf8"));
const top = metrics
  .sort((a, b) => b.totalMs - a.totalMs)
  .slice(0, 8)
  .map((metric) => `${metric.name}:${metric.totalMs.toFixed(1)}ms/${metric.count}x(max=${metric.maxMs.toFixed(1)}ms)`)
  .join(", ");
process.stdout.write(`PERF label=${label} file_count=${fileCount} phase=${phase} top=[${top}]`);
' "$label" "$phase" "$file_count"
  printf "\n"
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

wait_for_mounts_content() {
  rel="$1"
  expected="$2"
  shift 2
  for mount_path in "$@"; do
    wait_for_content "$mount_path/$rel" "$expected"
  done
}

benchmark_case() {
  file_count="$1"
  case_root="$WORK_ROOT/case-$file_count"
  entry="$case_root/entry"
  mount_root="$case_root/mounts"
  mount_ids=""
  mount_paths=""

  mkdir -p "$entry" "$mount_root"

  printf "Preparing %s files per layer...\n" "$file_count"
  for i in $(seq 1 "$file_count"); do
    printf "entry-%s\n" "$i" > "$entry/file-$i.txt"
  done
  mkdir -p "$entry/nested/deep"
  printf "entry-bench\n" > "$entry/nested/deep/marker.txt"

  ep_json="$(sh -lc "$CMD entrypoint create --path '$entry' --name 'bench-entry-$file_count' --json")"
  ep_id="$(printf "%s" "$ep_json" | json_field id)"
  l1_json="$(sh -lc "$CMD layer create --entrypoint '$ep_id' --name 'bench-layer-a-$file_count' --json")"
  l2_json="$(sh -lc "$CMD layer create --entrypoint '$ep_id' --name 'bench-layer-b-$file_count' --json")"
  l1_id="$(printf "%s" "$l1_json" | json_field id)"
  l2_id="$(printf "%s" "$l2_json" | json_field id)"
  l1_upper="$(printf "%s" "$l1_json" | json_field upperDir)"
  l2_upper="$(printf "%s" "$l2_json" | json_field upperDir)"
  l2_mount="$(printf "%s" "$l2_json" | json_field mountPath)"

  for i in $(seq 1 "$file_count"); do
    printf "layer-a-%s\n" "$i" > "$l1_upper/file-$i.txt"
    printf "layer-b-%s\n" "$i" > "$l2_upper/file-$i.txt"
  done
  mkdir -p "$l1_upper/nested/deep" "$l2_upper/nested/deep"
  printf "layer-a-marker\n" > "$l1_upper/nested/deep/marker.txt"
  printf "layer-b-marker\n" > "$l2_upper/nested/deep/marker.txt"

  mount_index=1
  while [ "$mount_index" -le "$FANOUT_MOUNTS" ]; do
    mount_path="$mount_root/mount-$mount_index"
    mkdir -p "$mount_path"
    mount_json="$(sh -lc "$CMD mount create --name 'bench-mount-$file_count-$mount_index' --mount-path '$mount_path' --entrypoint '$ep_id' --layer '$l1_id' --json")"
    mount_id="$(printf "%s" "$mount_json" | json_field id)"
    if [ -n "$mount_ids" ]; then
      mount_ids="$mount_ids $mount_id"
      mount_paths="$mount_paths $mount_path"
    else
      mount_ids="$mount_id"
      mount_paths="$mount_path"
    fi
    mount_index=$((mount_index + 1))
  done

  set -- $mount_ids
  primary_mount_id="$1"
  set -- $mount_paths
  primary_mount_path="$1"

  printf "Benchmarking attach switch across %s files to %s mount(s)...\n" "$file_count" "$FANOUT_MOUNTS"
  wait_for_replication_idle
  perf_reset
  attach_request_start="$(ms_now)"
  sh -lc "$CMD mount attach '$primary_mount_id' '$l2_id' --json" >/dev/null
  attach_request_end="$(ms_now)"
  wait_for_content "$primary_mount_path/file-$file_count.txt" "layer-b-$file_count"
  wait_for_content "$primary_mount_path/nested/deep/marker.txt" "layer-b-marker"
  wait_for_replication_idle
  attach_visible_end="$(ms_now)"
  attach_request_ms=$((attach_request_end - attach_request_start))
  attach_converge_ms=$((attach_visible_end - attach_request_end))
  attach_total_ms=$((attach_visible_end - attach_request_start))
  perf_summary "attach" "after_attach" "$file_count"

  printf "Benchmarking replay fanout from layer mount into %s fixed user mount(s)...\n" "$FANOUT_MOUNTS"
  set -- $mount_ids
  for mount_id in "$@"; do
    sh -lc "$CMD mount attach '$mount_id' '$l2_id' --json" >/dev/null
  done
  set -- $mount_paths
  wait_for_mounts_content "file-$file_count.txt" "layer-b-$file_count" "$@"
  sh -lc "$CMD layer mount '$l2_id' --json" >/dev/null
  wait_for_path_exists "$l2_mount"

  wait_for_replication_idle
  perf_reset
  replay_write_start="$(ms_now)"
  for i in $(seq 1 "$file_count"); do
    printf "replay-%s\n" "$i" > "$l2_mount/replay-$i.txt"
  done
  replay_write_end="$(ms_now)"
  set -- $mount_paths
  wait_for_mounts_content "replay-$file_count.txt" "replay-$file_count" "$@"
  wait_for_replication_idle
  replay_visible_end="$(ms_now)"
  replay_write_ms=$((replay_write_end - replay_write_start))
  replay_converge_ms=$((replay_visible_end - replay_write_end))
  replay_total_ms=$((replay_visible_end - replay_write_start))
  perf_summary "replay" "after_replay" "$file_count"

  printf "RESULT file_count=%s fanout_mounts=%s attach_request_ms=%s attach_converge_ms=%s attach_total_ms=%s replay_write_ms=%s replay_converge_ms=%s replay_total_ms=%s\n" \
    "$file_count" \
    "$FANOUT_MOUNTS" \
    "$attach_request_ms" \
    "$attach_converge_ms" \
    "$attach_total_ms" \
    "$replay_write_ms" \
    "$replay_converge_ms" \
    "$replay_total_ms"
}

printf "Starting daemon...\n"
sh -lc "$CMD daemon start" >/dev/null
for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$API_PORT/system/health" >/dev/null 2>&1 && break
  sleep 0.2
done

OLD_IFS="${IFS}"
IFS=','
for raw_count in $FILE_COUNTS; do
  IFS="${OLD_IFS}"
  file_count="$(printf "%s" "$raw_count" | tr -d '[:space:]')"
  [ -n "$file_count" ] || continue
  benchmark_case "$file_count"
  IFS=','
done
IFS="${OLD_IFS}"
