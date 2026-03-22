#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${SPACES_VERSION:-0.1.0}"
BUILD_ARTIFACTS="${SPACES_QA_BUILD_ARTIFACTS:-1}"
WORK_ROOT="$(mktemp -d /tmp/spaces-qa-script.XXXXXX)"
ENTRY="$WORK_ROOT/entry"
ENTRY2="$WORK_ROOT/entry-two"
MOUNTDIR="$WORK_ROOT/mount"
STATE="$WORK_ROOT/state"
DATA="$WORK_ROOT/data"
DBDIR="$WORK_ROOT/db"
RUNTIME_ROOT="$WORK_ROOT/runtime"
API_PORT="${SPACES_QA_API_PORT:-$((34000 + ($$ % 1000)))}"
NFS_PORT="${SPACES_QA_NFS_PORT:-$((14000 + ($$ % 1000)))}"
WAIT_STEP_SECONDS="${SPACES_QA_WAIT_STEP_SECONDS:-0.05}"
WATCHER_READY_DELAY_SECONDS="${SPACES_QA_WATCHER_READY_DELAY_SECONDS:-0.15}"
WAIT_SHORT_ATTEMPTS="${SPACES_QA_WAIT_SHORT_ATTEMPTS:-20}"
WAIT_MEDIUM_ATTEMPTS="${SPACES_QA_WAIT_MEDIUM_ATTEMPTS:-40}"
WAIT_LONG_ATTEMPTS="${SPACES_QA_WAIT_LONG_ATTEMPTS:-60}"
WATCH_TIMEOUT_SWITCH_MS="${SPACES_QA_WATCH_TIMEOUT_SWITCH_MS:-3000}"
WATCH_TIMEOUT_NOOP_MS="${SPACES_QA_WATCH_TIMEOUT_NOOP_MS:-800}"
WATCH_TIMEOUT_REPLAY_MS="${SPACES_QA_WATCH_TIMEOUT_REPLAY_MS:-4000}"
READ_POLL_SECONDS="${SPACES_QA_READ_POLL_SECONDS:-0.02}"
READ_TIMEOUT_TICKS="${SPACES_QA_READ_TIMEOUT_TICKS:-20}"

mkdir -p "$ENTRY" "$ENTRY2" "$MOUNTDIR" "$STATE" "$DATA" "$DBDIR" "$RUNTIME_ROOT"
echo "hello" > "$ENTRY/file.txt"
echo "hello-two" > "$ENTRY2/file.txt"

# Clean up stale QA daemons and NFS mounts from interrupted runs.
ps aux | awk '/spacesd-runtime\/bin\/java/ && /\/tmp\/spaces-qa-script\./ {print $2}' | xargs -I{} kill -9 {} 2>/dev/null || true
mount \
  | sed -n 's|.* on \(/[^ ]*spaces-qa-script\.[^ ]*\) (nfs.*|\1|p' \
  | sort -r \
  | while read -r mnt; do
      [ -n "$mnt" ] || continue
      umount -f "$mnt" >/dev/null 2>&1 || true
    done

if [ "$BUILD_ARTIFACTS" = "1" ]; then
  printf "Building release artifacts for bundled-runtime daemon...\n"
  SPACES_VERSION="$VERSION" "$ROOT_DIR/scripts/build-release-artifacts.sh" >/tmp/spaces-qa-build.log 2>&1
else
  printf "Skipping release artifact build (SPACES_QA_BUILD_ARTIFACTS=%s)\n" "$BUILD_ARTIFACTS"
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
export SPACES_API_TIMEOUT_MS="${SPACES_QA_API_TIMEOUT_MS:-6000}"

CMD="env -u NODE_OPTIONS pnpm --filter @spaces/web exec tsx bin/spaces.ts"
PASS=0
FAIL=0
XFAIL=0

cleanup() {
  sh -lc "$CMD daemon stop" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

is_env_mount_failure_output() {
  printf "%s" "$1" | rg -n "mount_nfs failed|Operation not permitted|Permission denied|umount failed|not mounted|No such file or directory" >/dev/null 2>&1
}

run_case() {
  name="$1"
  allow_env="$2"
  shift 2
  set +e
  out="$("$@" 2>&1)"
  code=$?
  set -e
  if [ "$code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    if [ "$allow_env" = "1" ] && printf "%s" "$out" | rg -n "mount_nfs failed|Operation not permitted|Permission denied|umount failed" >/dev/null 2>&1; then
      printf "XFAIL | %s\n" "$name"
      XFAIL=$((XFAIL + 1))
    else
      printf "FAIL | %s\n" "$name"
      FAIL=$((FAIL + 1))
    fi
  fi
  printf "%s\n" "$out" | sed -n '1,8p'
  printf "\n"
}

run_case_expect_fail() {
  name="$1"
  pattern="$2"
  shift 2
  set +e
  out="$("$@" 2>&1)"
  code=$?
  set -e

  matched=0
  if [ -z "$pattern" ]; then
    matched=1
  elif printf "%s" "$out" | rg -n "$pattern" >/dev/null 2>&1; then
    matched=1
  fi

  if [ "$code" -ne 0 ] && [ "$matched" -eq 1 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi
  printf "%s\n" "$out" | sed -n '1,8p'
  printf "\n"
}

read_text_file() {
  path="$1"
  tmp_out="$(mktemp /tmp/spaces-qa-read.XXXXXX)"
  perl -e '
    my $path = shift @ARGV;
    open my $fh, "<", $path or exit 2;
    local $/;
    my $body = <$fh>;
    print($body // "");
  ' "$path" >"$tmp_out" 2>/dev/null &
  reader_pid=$!

  tick=0
  while [ "$tick" -lt "$READ_TIMEOUT_TICKS" ]; do
    if ! kill -0 "$reader_pid" >/dev/null 2>&1; then
      if wait "$reader_pid" >/dev/null 2>&1; then
        cat "$tmp_out"
        rm -f "$tmp_out"
        return 0
      fi
      rm -f "$tmp_out"
      return 1
    fi
    sleep "$READ_POLL_SECONDS"
    tick=$((tick + 1))
  done

  kill -9 "$reader_pid" >/dev/null 2>&1 || true
  rm -f "$tmp_out"
  return 124
}

read_text_file_best_effort() {
  path="$1"
  if body="$(read_text_file "$path")"; then
    printf "%s" "$body"
    return 0
  fi
  code=$?
  if [ "$code" -eq 124 ]; then
    printf "<read-timeout>"
  else
    printf ""
  fi
  return 0
}

path_exists() {
  path="$1"
  perl -e '
    my $path = shift @ARGV;
    exit(-e $path ? 0 : 1);
  ' "$path" >/dev/null 2>&1 &
  stat_pid=$!

  tick=0
  while [ "$tick" -lt "$READ_TIMEOUT_TICKS" ]; do
    if ! kill -0 "$stat_pid" >/dev/null 2>&1; then
      if wait "$stat_pid" >/dev/null 2>&1; then
        return 0
      fi
      return 1
    fi
    sleep "$READ_POLL_SECONDS"
    tick=$((tick + 1))
  done

  kill -9 "$stat_pid" >/dev/null 2>&1 || true
  return 124
}

path_exists_best_effort() {
  path="$1"
  if path_exists "$path"; then
    printf "yes"
    return 0
  fi
  code=$?
  if [ "$code" -eq 1 ]; then
    printf "no"
    return 0
  fi
  printf "<stat-timeout>"
  return 0
}

wait_for_content() {
  path="$1"
  expected="$2"
  attempts="${3:-$WAIT_MEDIUM_ATTEMPTS}"

  for _ in $(seq 1 "$attempts"); do
    if content="$(read_text_file "$path")"; then
      if [ "$content" = "$expected" ]; then
        return 0
      fi
    else
      read_code=$?
      if [ "$read_code" -eq 124 ]; then
        :
      fi
    fi
    sleep "$WAIT_STEP_SECONDS"
  done
  return 1
}

wait_for_absent() {
  path="$1"
  attempts="${2:-$WAIT_MEDIUM_ATTEMPTS}"

  for _ in $(seq 1 "$attempts"); do
    if path_exists "$path"; then
      :
    else
      code=$?
      if [ "$code" -eq 1 ]; then
        return 0
      fi
      if [ "$code" -eq 124 ]; then
        :
      fi
    fi
    sleep "$WAIT_STEP_SECONDS"
  done
  return 1
}

wait_for_path_exists() {
  path="$1"
  attempts="${2:-$WAIT_MEDIUM_ATTEMPTS}"

  for _ in $(seq 1 "$attempts"); do
    if path_exists "$path"; then
      return 0
    fi
    sleep "$WAIT_STEP_SECONDS"
  done
  return 1
}

run_hot_reload_switch_case() {
  name="$1"
  mount_id="$2"
  next_layer_id="$3"
  watch_path="$4"
  needle="$5"
  expected_rel="$6"
  expected="$7"
  expected_path="$watch_path/$expected_rel"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const needle = process.argv[2];
const timeoutMs = Number(process.argv[3] || "3000");
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, timeoutMs);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  const name = String(filename || "");
  done = true;
  clearTimeout(timer);
  watcher.close();
  console.log(`watch-event ${eventType} ${name}`);
  process.exit(0);
});
  ' "$watch_path" "$needle" "$WATCH_TIMEOUT_SWITCH_MS" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep "$WATCHER_READY_DELAY_SECONDS"

  attach_out="$(sh -lc "$CMD mount attach '$mount_id' '$next_layer_id' --json" 2>&1)"
  attach_code=$?

  wait "$watcher_pid"
  watch_code=$?

  content=""
  content_code=1
  wait_for_content "$expected_path" "$expected" "$WAIT_LONG_ATTEMPTS" && content_code=0
  content="$(read_text_file_best_effort "$expected_path")"

  set -e

  if [ "$attach_code" -eq 0 ] && [ "$content_code" -eq 0 ] && [ "$watch_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  printf "%s\n" "$attach_out" | sed -n '1,8p'
  sed -n '1,4p' "$watch_file"
  printf "path=%s content=%s\n\n" "$expected_rel" "$content"
  rm -f "$watch_file"
}

run_hot_reload_noop_attach_case() {
  name="$1"
  mount_id="$2"
  layer_id="$3"
  watch_path="$4"
  stable_rel="$5"
  expected="$6"
  stable_path="$watch_path/$stable_rel"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"

  baseline_code=1
  wait_for_content "$stable_path" "$expected" "$WAIT_MEDIUM_ATTEMPTS" && baseline_code=0

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const timeoutMs = Number(process.argv[2] || "800");
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(3);
  }
}, timeoutMs);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  done = true;
  clearTimeout(timer);
  watcher.close();
  console.log(`watch-event ${eventType} ${String(filename || "")}`);
  process.exit(0);
});
  ' "$watch_path" "$WATCH_TIMEOUT_NOOP_MS" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep "$WATCHER_READY_DELAY_SECONDS"

  attach_out="$(sh -lc "$CMD mount attach '$mount_id' '$layer_id' --json" 2>&1)"
  attach_code=$?

  wait "$watcher_pid"
  watch_code=$?

  content=""
  content_code=1
  wait_for_content "$stable_path" "$expected" "$WAIT_SHORT_ATTEMPTS" && content_code=0
  content="$(read_text_file_best_effort "$stable_path")"

  set -e

  if [ "$baseline_code" -eq 0 ] &&
    [ "$attach_code" -eq 0 ] &&
    [ "$watch_code" -ne 0 ] &&
    rg -n "watch-timeout" "$watch_file" >/dev/null 2>&1 &&
    [ "$content_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  printf "%s\n" "$attach_out" | sed -n '1,8p'
  sed -n '1,4p' "$watch_file"
  printf "path=%s content=%s\n\n" "$stable_rel" "$content"
  rm -f "$watch_file"
}

run_hot_reload_switch_delta_case() {
  name="$1"
  mount_id="$2"
  next_layer_id="$3"
  watch_path="$4"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const needle = process.argv[2];
const timeoutMs = Number(process.argv[3] || "3000");
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, timeoutMs);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  const name = String(filename || "");
  done = true;
  clearTimeout(timer);
  watcher.close();
  console.log(`watch-event ${eventType} ${name}`);
  process.exit(0);
});
  ' "$watch_path" "delta-" "$WATCH_TIMEOUT_SWITCH_MS" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep "$WATCHER_READY_DELAY_SECONDS"

  attach_out="$(sh -lc "$CMD mount attach '$mount_id' '$next_layer_id' --json" 2>&1)"
  attach_code=$?

  wait "$watcher_pid"
  watch_code=$?

  add_code=1
  removed_code=1
  renamed_old_code=1
  renamed_new_code=1

  wait_for_content "$watch_path/delta-added.txt" "added-two" "$WAIT_LONG_ATTEMPTS" && add_code=0
  wait_for_absent "$watch_path/delta-removed.txt" "$WAIT_LONG_ATTEMPTS" && removed_code=0
  wait_for_absent "$watch_path/delta-renamed-old.txt" "$WAIT_LONG_ATTEMPTS" && renamed_old_code=0
  wait_for_content "$watch_path/delta-renamed-new.txt" "renamed-two" "$WAIT_LONG_ATTEMPTS" && renamed_new_code=0

  set -e

  if [ "$attach_code" -eq 0 ] &&
    [ "$add_code" -eq 0 ] &&
    [ "$removed_code" -eq 0 ] &&
    [ "$renamed_old_code" -eq 0 ] &&
    [ "$renamed_new_code" -eq 0 ] &&
    [ "$watch_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  printf "%s\n" "$attach_out" | sed -n '1,8p'
  sed -n '1,4p' "$watch_file"
  printf "delta-added=%s\n" "$(read_text_file_best_effort "$watch_path/delta-added.txt")"
  printf "delta-removed-exists=%s\n" "$(path_exists_best_effort "$watch_path/delta-removed.txt")"
  printf "delta-renamed-old-exists=%s\n" "$(path_exists_best_effort "$watch_path/delta-renamed-old.txt")"
  printf "delta-renamed-new=%s\n" "$(read_text_file_best_effort "$watch_path/delta-renamed-new.txt")"
  printf "\n"
  rm -f "$watch_file"
}

run_replication_nested_tree_case() {
  name="$1"
  source_layer_mount_path="$2"
  watch_path="$3"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const needle = process.argv[2];
const timeoutMs = Number(process.argv[3] || "4000");
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, timeoutMs);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  const name = String(filename || "");
  if (!needle || !name || name.includes(needle)) {
    done = true;
    clearTimeout(timer);
    watcher.close();
    console.log(`watch-event ${eventType} ${name}`);
    process.exit(0);
  }
});
  ' "$watch_path" "replay-tree/" "$WATCH_TIMEOUT_REPLAY_MS" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep "$WATCHER_READY_DELAY_SECONDS"

  write_code=0
  wait_for_path_exists "$source_layer_mount_path" "$WAIT_LONG_ATTEMPTS" || write_code=1
  if [ "$write_code" -eq 0 ]; then
    mkdir -p "$source_layer_mount_path/replay-tree/a/b" || write_code=$?
  fi
  if [ "$write_code" -eq 0 ]; then
    printf "tree-two\n" > "$source_layer_mount_path/replay-tree/a/b/c.txt" || write_code=$?
    printf "tree-two-final\n" > "$source_layer_mount_path/replay-tree/a/b/c.txt" || write_code=$?
  fi

  wait "$watcher_pid"
  watch_code=$?

  nested_code=1
  wait_for_content "$watch_path/replay-tree/a/b/c.txt" "tree-two-final" "$WAIT_LONG_ATTEMPTS" && nested_code=0

  set -e

  if [ "$write_code" -eq 0 ] && [ "$nested_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  sed -n '1,4p' "$watch_file"
  printf "watch-code=%s\n" "$watch_code"
  printf "replay-tree=%s\n\n" "$(read_text_file_best_effort "$watch_path/replay-tree/a/b/c.txt")"
  rm -f "$watch_file"
}

run_overlay_delete_case() {
  name="$1"
  source_mount_path="$2"
  watch_path="$3"
  relative="$4"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"

  baseline_source=1
  baseline_watch=1
  wait_for_path_exists "$source_mount_path" "$WAIT_LONG_ATTEMPTS" && baseline_source=0
  if [ "$baseline_source" -eq 0 ]; then
    wait_for_path_exists "$source_mount_path/$relative" "$WAIT_LONG_ATTEMPTS" && baseline_source=0 || baseline_source=1
  fi
  wait_for_path_exists "$watch_path/$relative" "$WAIT_LONG_ATTEMPTS" && baseline_watch=0

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const timeoutMs = Number(process.argv[2] || "3000");
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, timeoutMs);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  done = true;
  clearTimeout(timer);
  watcher.close();
  console.log(`watch-event ${eventType} ${String(filename || "")}`);
  process.exit(0);
});
  ' "$watch_path" "$WATCH_TIMEOUT_REPLAY_MS" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep "$WATCHER_READY_DELAY_SECONDS"

  rm -f "$source_mount_path/$relative" >/tmp/spaces-qa-live-delete.err 2>&1
  delete_code=$?
  delete_out="$(cat /tmp/spaces-qa-live-delete.err 2>/dev/null || true)"

  wait "$watcher_pid"
  watch_code=$?

  source_absent=1
  watch_absent=1
  wait_for_absent "$source_mount_path/$relative" "$WAIT_LONG_ATTEMPTS" && source_absent=0
  wait_for_absent "$watch_path/$relative" "$WAIT_LONG_ATTEMPTS" && watch_absent=0
  set -e

  if [ "$delete_code" -eq 0 ] &&
    [ "$source_absent" -eq 0 ] &&
    [ "$watch_absent" -eq 0 ] &&
    [ "$baseline_source" -eq 0 ] &&
    [ "$baseline_watch" -eq 0 ] &&
    [ "$watch_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    if is_env_mount_failure_output "$delete_out"; then
      printf "XFAIL | %s\n" "$name"
      XFAIL=$((XFAIL + 1))
    else
      printf "FAIL | %s\n" "$name"
      FAIL=$((FAIL + 1))
    fi
  fi

  sed -n '1,4p' "$watch_file"
  printf "delete=%s\n" "$delete_out"
  printf "source-exists=%s\n" "$(path_exists_best_effort "$source_mount_path/$relative")"
  printf "watch-exists=%s\n\n" "$(path_exists_best_effort "$watch_path/$relative")"
  rm -f "$watch_file" /tmp/spaces-qa-live-delete.err
}

run_overlay_rename_case() {
  name="$1"
  source_mount_path="$2"
  watch_path="$3"
  old_rel="$4"
  new_rel="$5"
  expected="$6"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"

  baseline_old_source=1
  baseline_old_watch=1
  wait_for_path_exists "$source_mount_path/$old_rel" "$WAIT_LONG_ATTEMPTS" && baseline_old_source=0
  wait_for_path_exists "$watch_path/$old_rel" "$WAIT_LONG_ATTEMPTS" && baseline_old_watch=0

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const timeoutMs = Number(process.argv[2] || "3000");
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, timeoutMs);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  done = true;
  clearTimeout(timer);
  watcher.close();
  console.log(`watch-event ${eventType} ${String(filename || "")}`);
  process.exit(0);
});
  ' "$watch_path" "$WATCH_TIMEOUT_REPLAY_MS" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep "$WATCHER_READY_DELAY_SECONDS"

  mv "$source_mount_path/$old_rel" "$source_mount_path/$new_rel" >/tmp/spaces-qa-live-rename.err 2>&1
  rename_code=$?
  rename_out="$(cat /tmp/spaces-qa-live-rename.err 2>/dev/null || true)"

  wait "$watcher_pid"
  watch_code=$?

  old_source_absent=1
  old_watch_absent=1
  new_source_content=1
  new_watch_content=1
  wait_for_absent "$source_mount_path/$old_rel" "$WAIT_LONG_ATTEMPTS" && old_source_absent=0
  wait_for_absent "$watch_path/$old_rel" "$WAIT_LONG_ATTEMPTS" && old_watch_absent=0
  wait_for_content "$source_mount_path/$new_rel" "$expected" "$WAIT_LONG_ATTEMPTS" && new_source_content=0
  wait_for_content "$watch_path/$new_rel" "$expected" "$WAIT_LONG_ATTEMPTS" && new_watch_content=0
  set -e

  if [ "$rename_code" -eq 0 ] &&
    [ "$baseline_old_source" -eq 0 ] &&
    [ "$baseline_old_watch" -eq 0 ] &&
    [ "$old_source_absent" -eq 0 ] &&
    [ "$old_watch_absent" -eq 0 ] &&
    [ "$new_source_content" -eq 0 ] &&
    [ "$new_watch_content" -eq 0 ] &&
    [ "$watch_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    if is_env_mount_failure_output "$rename_out"; then
      printf "XFAIL | %s\n" "$name"
      XFAIL=$((XFAIL + 1))
    else
      printf "FAIL | %s\n" "$name"
      FAIL=$((FAIL + 1))
    fi
  fi

  sed -n '1,4p' "$watch_file"
  printf "rename=%s\n" "$rename_out"
  printf "old-source-exists=%s\n" "$(path_exists_best_effort "$source_mount_path/$old_rel")"
  printf "old-watch-exists=%s\n" "$(path_exists_best_effort "$watch_path/$old_rel")"
  printf "new-source=%s\n" "$(read_text_file_best_effort "$source_mount_path/$new_rel")"
  printf "new-watch=%s\n\n" "$(read_text_file_best_effort "$watch_path/$new_rel")"
  rm -f "$watch_file" /tmp/spaces-qa-live-rename.err
}

run_case "daemon start" 0 sh -lc "$CMD daemon start"
for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$API_PORT/system/health" >/dev/null 2>&1 && break
  sleep 0.2
done
run_case "health" 0 curl -sf "http://127.0.0.1:$API_PORT/system/health"
run_case "daemon status json" 0 sh -lc "$CMD daemon status --json"
run_case "status json" 0 sh -lc "$CMD status --json"
run_case "remount json" 0 sh -lc "$CMD remount --json"

EP_JSON="$(sh -lc "$CMD entrypoint create --path '$ENTRY' --name qa-entry --json" 2>/tmp/spaces-qa-entry.err || true)"
if [ -s /tmp/spaces-qa-entry.err ]; then
  printf "FAIL | entrypoint create\n"
  FAIL=$((FAIL + 1))
  sed -n '1,8p' /tmp/spaces-qa-entry.err
  EP_ID=""
else
  printf "PASS | entrypoint create\n"
  PASS=$((PASS + 1))
  printf "%s\n\n" "$EP_JSON" | sed -n '1,8p'
  EP_ID="$(printf "%s" "$EP_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
fi

run_case "entrypoint list alias e" 0 sh -lc "$CMD e list --json"
if [ -n "$EP_ID" ]; then
  run_case "entrypoint list by id" 0 sh -lc "$CMD entrypoint list '$EP_ID' --json"
fi

LAYER_ID=""
LAYER_MOUNT_PATH=""
LAYER_UPPER_DIR=""
if [ -n "$EP_ID" ]; then
  L_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-layer --json" 2>/tmp/spaces-qa-layer.err || true)"
  if [ -s /tmp/spaces-qa-layer.err ]; then
    if rg -n "mount_nfs failed|Operation not permitted|Permission denied" /tmp/spaces-qa-layer.err >/dev/null 2>&1; then
      printf "XFAIL | layer create\n"
      XFAIL=$((XFAIL + 1))
    else
      printf "FAIL | layer create\n"
      FAIL=$((FAIL + 1))
    fi
    sed -n '1,8p' /tmp/spaces-qa-layer.err
  else
    printf "PASS | layer create\n"
    PASS=$((PASS + 1))
    printf "%s\n\n" "$L_JSON" | sed -n '1,8p'
    LAYER_ID="$(printf "%s" "$L_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
    LAYER_MOUNT_PATH="$(printf "%s" "$L_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
    LAYER_UPPER_DIR="$(printf "%s" "$L_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.upperDir||"")')"
  fi
fi

run_case "layer list" 0 sh -lc "$CMD layer list --json"
if [ -n "$LAYER_ID" ]; then
  run_case "layer diff" 0 sh -lc "$CMD layer diff '$LAYER_ID' --json"
  run_case "mount create" 1 sh -lc "$CMD mount create --name qa-mount --mount-path '$MOUNTDIR' --entrypoint '$EP_ID' --layer '$LAYER_ID' --json"
else
  run_case "mount create (entrypoint only)" 1 sh -lc "$CMD mount create --name qa-mount --mount-path '$MOUNTDIR' --entrypoint '$EP_ID' --json"
fi

run_case "mount list" 0 sh -lc "$CMD mount list --json"
MOUNT_ID="$(sh -lc "$CMD mount list --json" 2>/dev/null | node -e 'const fs=require("fs");const s=fs.readFileSync(0,"utf8");try{const j=JSON.parse(s);if(Array.isArray(j)&&j[0]?.id)process.stdout.write(j[0].id)}catch{}')"
EP2_ID=""
LAYER_EP2_ID=""
LAYER2_ID=""
LAYER2_MOUNT_PATH=""
LAYER2_UPPER_DIR=""
PARENT1_ID=""
PARENT1_MOUNT_PATH=""
PARENT1_UPPER_DIR=""
CHILD1_ID=""
CHILD1_MOUNT_PATH=""
PARENT2_ID=""
PARENT2_MOUNT_PATH=""
PARENT2_UPPER_DIR=""
CHILD2_ID=""
CHILD2_MOUNT_PATH=""
if [ -n "$MOUNT_ID" ]; then
  run_case_expect_fail "mount delete requires --force in non-interactive mode" "Refusing destructive action in non-interactive mode|--force" sh -lc "$CMD mount delete '$MOUNT_ID' --json"
  run_case_expect_fail "entrypoint create rejects unknown flag" "Unknown option\\(s\\): --bogus" sh -lc "$CMD entrypoint create --path '$ENTRY' --name qa-bad --bogus nope --json"
  if [ -n "$EP_ID" ]; then
    run_case_expect_fail "layer create rejects unknown flag" "Unknown option\\(s\\): --bogus" sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-bad-layer --bogus nope --json"
    run_case_expect_fail "mount create rejects unknown flag" "Unknown option\\(s\\): --bogus" sh -lc "$CMD mount create --name qa-bad-mount --mount-path '$WORK_ROOT/mount-bad' --entrypoint '$EP_ID' --bogus nope --json"
  fi

  if [ -n "$EP_ID" ]; then
    L2_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-layer-2 --json" 2>/tmp/spaces-qa-layer2.err || true)"
    if [ -s /tmp/spaces-qa-layer2.err ]; then
      if is_env_mount_failure_output "$(cat /tmp/spaces-qa-layer2.err 2>/dev/null || true)"; then
        printf "XFAIL | layer create 2\n"
        XFAIL=$((XFAIL + 1))
      else
        printf "FAIL | layer create 2\n"
        FAIL=$((FAIL + 1))
      fi
      sed -n '1,8p' /tmp/spaces-qa-layer2.err
      printf "\n"
    else
      printf "PASS | layer create 2\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$L2_JSON" | sed -n '1,8p'
      LAYER2_ID="$(printf "%s" "$L2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
      LAYER2_MOUNT_PATH="$(printf "%s" "$L2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
      LAYER2_UPPER_DIR="$(printf "%s" "$L2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.upperDir||"")')"
    fi
  fi

  if [ -n "$LAYER_UPPER_DIR" ]; then
    mkdir -p "$LAYER_UPPER_DIR" || true
    printf "layer-one\n" > "$LAYER_UPPER_DIR/hot-switch.txt" || true
    printf "delta-removed\n" > "$LAYER_UPPER_DIR/delta-removed.txt" || true
    printf "renamed-two\n" > "$LAYER_UPPER_DIR/delta-renamed-old.txt" || true
    mkdir -p "$LAYER_UPPER_DIR/nested/deep" || true
    printf "deep-one\n" > "$LAYER_UPPER_DIR/nested/deep/file.txt" || true
  fi
  if [ -n "$LAYER2_UPPER_DIR" ]; then
    mkdir -p "$LAYER2_UPPER_DIR" || true
    printf "layer-two\n" > "$LAYER2_UPPER_DIR/hot-switch.txt" || true
    printf "added-two\n" > "$LAYER2_UPPER_DIR/delta-added.txt" || true
    printf "renamed-two\n" > "$LAYER2_UPPER_DIR/delta-renamed-new.txt" || true
    mkdir -p "$LAYER2_UPPER_DIR/nested/deep" || true
    printf "deep-two\n" > "$LAYER2_UPPER_DIR/nested/deep/file.txt" || true
  fi

  if [ -n "$LAYER_ID" ]; then
    run_hot_reload_noop_attach_case "mount attach same layer is a no-op for watcher + content" "$MOUNT_ID" "$LAYER_ID" "$MOUNTDIR" "file.txt" "hello"
  fi

  if [ -n "$LAYER2_ID" ]; then
    run_hot_reload_switch_case "mount attach switch emits watcher event + content update" "$MOUNT_ID" "$LAYER2_ID" "$MOUNTDIR" "hot-switch.txt" "hot-switch.txt" "layer-two"
  fi

  if [ -n "$LAYER_ID" ] && [ -n "$LAYER2_ID" ]; then
    run_case "mount attach reset to layer1 for delta switch case" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$LAYER_ID' --json"
    run_hot_reload_switch_delta_case "mount attach switch applies add/remove/rename deltas" "$MOUNT_ID" "$LAYER2_ID" "$MOUNTDIR"
    run_case "mount attach reset to layer1 for nested switch case" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$LAYER_ID' --json"
    run_hot_reload_switch_case "mount attach switch updates nested subtree content" "$MOUNT_ID" "$LAYER2_ID" "$MOUNTDIR" "nested/deep/file.txt" "nested/deep/file.txt" "deep-two"
  fi

  if [ -n "$LAYER2_ID" ] && [ -n "$LAYER2_MOUNT_PATH" ]; then
    run_case "mount attach layer2 baseline for replay tree case" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$LAYER2_ID' --json"
    run_case "layer2 mount ensure for replay tree case" 1 sh -lc "$CMD layer mount '$LAYER2_ID' --json"
    run_replication_nested_tree_case "replication replays nested directory tree updates" "$LAYER2_MOUNT_PATH" "$MOUNTDIR"
  fi

  if [ -n "$EP_ID" ]; then
    P1_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-parent-a --json" 2>/tmp/spaces-qa-parent1.err || true)"
    if [ -s /tmp/spaces-qa-parent1.err ]; then
      if is_env_mount_failure_output "$(cat /tmp/spaces-qa-parent1.err 2>/dev/null || true)"; then
        printf "XFAIL | layer create parent-a\n"
        XFAIL=$((XFAIL + 1))
      else
        printf "FAIL | layer create parent-a\n"
        FAIL=$((FAIL + 1))
      fi
      sed -n '1,8p' /tmp/spaces-qa-parent1.err
      printf "\n"
    else
      printf "PASS | layer create parent-a\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$P1_JSON" | sed -n '1,8p'
      PARENT1_ID="$(printf "%s" "$P1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
      PARENT1_MOUNT_PATH="$(printf "%s" "$P1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
      PARENT1_UPPER_DIR="$(printf "%s" "$P1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.upperDir||"")')"
    fi

    if [ -n "$PARENT1_ID" ]; then
      C1_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-child-a --parent '$PARENT1_ID' --json" 2>/tmp/spaces-qa-child1.err || true)"
      if [ -s /tmp/spaces-qa-child1.err ]; then
        if is_env_mount_failure_output "$(cat /tmp/spaces-qa-child1.err 2>/dev/null || true)"; then
          printf "XFAIL | layer create child-a\n"
          XFAIL=$((XFAIL + 1))
        else
          printf "FAIL | layer create child-a\n"
          FAIL=$((FAIL + 1))
        fi
        sed -n '1,8p' /tmp/spaces-qa-child1.err
        printf "\n"
      else
        printf "PASS | layer create child-a\n"
        PASS=$((PASS + 1))
        printf "%s\n\n" "$C1_JSON" | sed -n '1,8p'
        CHILD1_ID="$(printf "%s" "$C1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
        CHILD1_MOUNT_PATH="$(printf "%s" "$C1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
      fi
    fi

    P2_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-parent-b --json" 2>/tmp/spaces-qa-parent2.err || true)"
    if [ -s /tmp/spaces-qa-parent2.err ]; then
      if is_env_mount_failure_output "$(cat /tmp/spaces-qa-parent2.err 2>/dev/null || true)"; then
        printf "XFAIL | layer create parent-b\n"
        XFAIL=$((XFAIL + 1))
      else
        printf "FAIL | layer create parent-b\n"
        FAIL=$((FAIL + 1))
      fi
      sed -n '1,8p' /tmp/spaces-qa-parent2.err
      printf "\n"
    else
      printf "PASS | layer create parent-b\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$P2_JSON" | sed -n '1,8p'
      PARENT2_ID="$(printf "%s" "$P2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
      PARENT2_MOUNT_PATH="$(printf "%s" "$P2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
      PARENT2_UPPER_DIR="$(printf "%s" "$P2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.upperDir||"")')"
    fi

    if [ -n "$PARENT2_ID" ]; then
      C2_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-child-b --parent '$PARENT2_ID' --json" 2>/tmp/spaces-qa-child2.err || true)"
      if [ -s /tmp/spaces-qa-child2.err ]; then
        if is_env_mount_failure_output "$(cat /tmp/spaces-qa-child2.err 2>/dev/null || true)"; then
          printf "XFAIL | layer create child-b\n"
          XFAIL=$((XFAIL + 1))
        else
          printf "FAIL | layer create child-b\n"
          FAIL=$((FAIL + 1))
        fi
        sed -n '1,8p' /tmp/spaces-qa-child2.err
        printf "\n"
      else
        printf "PASS | layer create child-b\n"
        PASS=$((PASS + 1))
        printf "%s\n\n" "$C2_JSON" | sed -n '1,8p'
        CHILD2_ID="$(printf "%s" "$C2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
        CHILD2_MOUNT_PATH="$(printf "%s" "$C2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
      fi
    fi
  fi

  if [ -n "$PARENT1_UPPER_DIR" ]; then
    mkdir -p "$PARENT1_UPPER_DIR" || true
    printf "parent-one\n" > "$PARENT1_UPPER_DIR/parent-switch.txt" || true
    printf "delete-me\n" > "$PARENT1_UPPER_DIR/inherited-delete.txt" || true
  fi
  if [ -n "$PARENT2_UPPER_DIR" ]; then
    mkdir -p "$PARENT2_UPPER_DIR" || true
    printf "parent-two\n" > "$PARENT2_UPPER_DIR/parent-switch.txt" || true
    printf "rename-me\n" > "$PARENT2_UPPER_DIR/inherited-rename-old.txt" || true
  fi
  if [ -n "$CHILD1_ID" ] && [ -n "$CHILD2_ID" ]; then
    run_case "mount attach parent child-a baseline" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$CHILD1_ID' --json"
    run_hot_reload_switch_case "mount attach parent-layer switch emits watcher event + content update" "$MOUNT_ID" "$CHILD2_ID" "$MOUNTDIR" "parent-switch.txt" "parent-switch.txt" "parent-two"
  fi
  if [ -n "$CHILD1_ID" ] && [ -n "$CHILD1_MOUNT_PATH" ]; then
    run_case "mount attach parent child-a baseline for inherited delete" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$CHILD1_ID' --json"
    run_overlay_delete_case "child layer delete hides inherited parent file and updates user mount" "$CHILD1_MOUNT_PATH" "$MOUNTDIR" "inherited-delete.txt"
  fi
  if [ -n "$CHILD2_ID" ] && [ -n "$CHILD2_MOUNT_PATH" ]; then
    run_case "mount attach parent child-b baseline for inherited rename" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$CHILD2_ID' --json"
    run_overlay_rename_case "child layer rename hides inherited old path and updates user mount" "$CHILD2_MOUNT_PATH" "$MOUNTDIR" "inherited-rename-old.txt" "inherited-rename-new.txt" "rename-me"
  fi

  EP2_JSON="$(sh -lc "$CMD entrypoint create --path '$ENTRY2' --name qa-entry-two --json" 2>/tmp/spaces-qa-entry2.err || true)"
  if [ -s /tmp/spaces-qa-entry2.err ]; then
    printf "FAIL | entrypoint create 2\n"
    FAIL=$((FAIL + 1))
    sed -n '1,8p' /tmp/spaces-qa-entry2.err
    printf "\n"
  else
    printf "PASS | entrypoint create 2\n"
    PASS=$((PASS + 1))
    printf "%s\n\n" "$EP2_JSON" | sed -n '1,8p'
    EP2_ID="$(printf "%s" "$EP2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
  fi

  if [ -n "$EP2_ID" ]; then
    L_EP2_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP2_ID' --name qa-layer-other-ep --json" 2>/tmp/spaces-qa-layer-ep2.err || true)"
    if [ -s /tmp/spaces-qa-layer-ep2.err ]; then
      if is_env_mount_failure_output "$(cat /tmp/spaces-qa-layer-ep2.err 2>/dev/null || true)"; then
        printf "XFAIL | layer create other entrypoint\n"
        XFAIL=$((XFAIL + 1))
      else
        printf "FAIL | layer create other entrypoint\n"
        FAIL=$((FAIL + 1))
      fi
      sed -n '1,8p' /tmp/spaces-qa-layer-ep2.err
      printf "\n"
    else
      printf "PASS | layer create other entrypoint\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$L_EP2_JSON" | sed -n '1,8p'
      LAYER_EP2_ID="$(printf "%s" "$L_EP2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
    fi
  fi
  if [ -n "$LAYER_EP2_ID" ]; then
    run_case_expect_fail "mount attach rejects cross-entrypoint layer" "same entrypoint|Layer must belong to the same entrypoint|Layer not found" sh -lc "$CMD mount attach '$MOUNT_ID' '$LAYER_EP2_ID' --json"
  fi

  run_case "mount attach detach" 0 sh -lc "$CMD mount attach '$MOUNT_ID' --json"
  if [ -n "$LAYER2_ID" ]; then
    run_hot_reload_switch_case "mount attach from detached emits watcher event + content update" "$MOUNT_ID" "$LAYER2_ID" "$MOUNTDIR" "hot-switch.txt" "hot-switch.txt" "layer-two"
  fi
  run_case "mount mount" 1 sh -lc "$CMD mount mount '$MOUNT_ID' --json"
  run_case "mount unmount" 1 sh -lc "$CMD mount unmount '$MOUNT_ID' --json"
  run_case "mount delete force" 1 sh -lc "$CMD mount delete '$MOUNT_ID' --force --json"
  if [ -n "$CHILD2_ID" ]; then
    run_case "child-b delete force" 1 sh -lc "$CMD layer delete '$CHILD2_ID' --force --json"
  fi
  if [ -n "$CHILD1_ID" ]; then
    run_case "child-a delete force" 1 sh -lc "$CMD layer delete '$CHILD1_ID' --force --json"
  fi
  if [ -n "$PARENT2_ID" ]; then
    run_case "parent-b delete force" 1 sh -lc "$CMD layer delete '$PARENT2_ID' --force --json"
  fi
  if [ -n "$PARENT1_ID" ]; then
    run_case "parent-a delete force" 1 sh -lc "$CMD layer delete '$PARENT1_ID' --force --json"
  fi
  if [ -n "$LAYER2_ID" ]; then
    run_case "layer2 delete force" 1 sh -lc "$CMD layer delete '$LAYER2_ID' --force --json"
  fi
  if [ -n "$LAYER_EP2_ID" ]; then
    run_case "layer other entrypoint delete force" 1 sh -lc "$CMD layer delete '$LAYER_EP2_ID' --force --json"
  fi
  if [ -n "$EP2_ID" ]; then
    run_case "entrypoint 2 delete force" 1 sh -lc "$CMD entrypoint delete '$EP2_ID' --force --json"
  fi
fi

if [ -n "$LAYER_ID" ]; then
  run_case "layer delete force" 1 sh -lc "$CMD layer delete '$LAYER_ID' --force --json"
fi
if [ -n "$EP_ID" ]; then
  run_case "entrypoint delete force" 1 sh -lc "$CMD entrypoint delete '$EP_ID' --force --json"
fi

run_case "daemon restart" 0 sh -lc "$CMD daemon restart"
run_case "daemon stop" 0 sh -lc "$CMD daemon stop"
run_case "daemon status stopped" 0 sh -lc "$CMD daemon status --json"

printf "SUMMARY PASS=%s XFAIL=%s FAIL=%s\n" "$PASS" "$XFAIL" "$FAIL"
if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
