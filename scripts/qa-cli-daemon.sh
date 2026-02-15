#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${SPACES_VERSION:-0.1.0}"
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

printf "Building release artifacts for bundled-runtime daemon...\n"
SPACES_VERSION="$VERSION" "$ROOT_DIR/scripts/build-release-artifacts.sh" >/tmp/spaces-qa-build.log 2>&1
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

CMD="pnpm --filter @spaces/web exec tsx bin/spaces.ts"
PASS=0
FAIL=0
XFAIL=0

cleanup() {
  sh -lc "$CMD daemon stop" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

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
  perl -e '
    alarm 2;
    my $path = shift @ARGV;
    open my $fh, "<", $path or exit 2;
    local $/;
    print <$fh>;
  ' "$path" 2>/dev/null || true
}

marker_signature() {
  mount_root="$1"
  marker="$mount_root/.spaces-hot-reload"
  perl -e '
    my $path = shift @ARGV;
    if (!-e $path) {
      print "missing";
      exit 0;
    }
    my @st = stat($path);
    my $mtime = defined $st[9] ? $st[9] : 0;
    my $size = defined $st[7] ? $st[7] : 0;
    my $content = "";
    if (open my $fh, "<", $path) {
      local $/;
      $content = <$fh> // "";
      close $fh;
    }
    $content =~ s/\s+/ /g;
    print "$mtime:$size:$content";
  ' "$marker" 2>/dev/null || printf "missing"
}

wait_for_content() {
  path="$1"
  expected="$2"
  attempts="${3:-40}"

  for _ in $(seq 1 "$attempts"); do
    if [ -f "$path" ]; then
      content="$(read_text_file "$path")"
      if [ "$content" = "$expected" ]; then
        return 0
      fi
    fi
    sleep 0.1
  done
  return 1
}

wait_for_absent() {
  path="$1"
  attempts="${2:-40}"

  for _ in $(seq 1 "$attempts"); do
    if [ ! -e "$path" ]; then
      return 0
    fi
    sleep 0.1
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
  marker_before="$(marker_signature "$watch_path")"

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const needle = process.argv[2];
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, 6000);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  const name = String(filename || "");
  if (!needle || !name || name.includes(needle) || name.includes(".spaces-hot-reload")) {
    done = true;
    clearTimeout(timer);
    watcher.close();
    console.log(`watch-event ${eventType} ${name}`);
    process.exit(0);
  }
});
' "$watch_path" "$needle" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep 0.4

  attach_out="$(sh -lc "$CMD mount attach '$mount_id' '$next_layer_id' --json" 2>&1)"
  attach_code=$?

  wait "$watcher_pid"
  watch_code=$?

  content=""
  content_code=1
  wait_for_content "$expected_path" "$expected" 40 && content_code=0
  if [ -f "$expected_path" ]; then
    content="$(read_text_file "$expected_path")"
  fi
  marker_after="$(marker_signature "$watch_path")"
  marker_changed=0
  if [ "$marker_before" != "$marker_after" ]; then
    marker_changed=1
  fi

  set -e

  if [ "$attach_code" -eq 0 ] &&
    [ "$content_code" -eq 0 ] &&
    { [ "$watch_code" -eq 0 ] || [ "$marker_changed" -eq 1 ]; }; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  printf "%s\n" "$attach_out" | sed -n '1,8p'
  sed -n '1,4p' "$watch_file"
  printf "path=%s content=%s marker-changed=%s\n\n" "$expected_rel" "$content" "$marker_changed"
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
  marker_before="$(marker_signature "$watch_path")"

  baseline_code=1
  wait_for_content "$stable_path" "$expected" 40 && baseline_code=0

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(3);
  }
}, 2500);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  done = true;
  clearTimeout(timer);
  watcher.close();
  console.log(`watch-event ${eventType} ${String(filename || "")}`);
  process.exit(0);
});
' "$watch_path" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep 0.4

  attach_out="$(sh -lc "$CMD mount attach '$mount_id' '$layer_id' --json" 2>&1)"
  attach_code=$?

  wait "$watcher_pid"
  watch_code=$?

  content=""
  content_code=1
  wait_for_content "$stable_path" "$expected" 20 && content_code=0
  if [ -f "$stable_path" ]; then
    content="$(read_text_file "$stable_path")"
  fi
  marker_after="$(marker_signature "$watch_path")"
  marker_changed=0
  if [ "$marker_before" != "$marker_after" ]; then
    marker_changed=1
  fi

  set -e

  if [ "$baseline_code" -eq 0 ] &&
    [ "$attach_code" -eq 0 ] &&
    [ "$watch_code" -ne 0 ] &&
    rg -n "watch-timeout" "$watch_file" >/dev/null 2>&1 &&
    [ "$marker_changed" -eq 0 ] &&
    [ "$content_code" -eq 0 ]; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  printf "%s\n" "$attach_out" | sed -n '1,8p'
  sed -n '1,4p' "$watch_file"
  printf "path=%s content=%s marker-changed=%s\n\n" "$stable_rel" "$content" "$marker_changed"
  rm -f "$watch_file"
}

run_hot_reload_switch_delta_case() {
  name="$1"
  mount_id="$2"
  next_layer_id="$3"
  watch_path="$4"
  watch_file="$(mktemp /tmp/spaces-qa-watch.XXXXXX)"
  marker_before="$(marker_signature "$watch_path")"

  set +e
  node -e '
const fs = require("fs");
const root = process.argv[1];
const needle = process.argv[2];
let done = false;
const timer = setTimeout(() => {
  if (!done) {
    done = true;
    console.error("watch-timeout");
    process.exit(1);
  }
}, 6000);
const watcher = fs.watch(root, { recursive: true }, (eventType, filename) => {
  if (done) return;
  const name = String(filename || "");
  if (!needle || !name || name.includes(needle) || name.includes(".spaces-hot-reload")) {
    done = true;
    clearTimeout(timer);
    watcher.close();
    console.log(`watch-event ${eventType} ${name}`);
    process.exit(0);
  }
});
' "$watch_path" "delta-" > "$watch_file" 2>&1 &
  watcher_pid=$!
  sleep 0.4

  attach_out="$(sh -lc "$CMD mount attach '$mount_id' '$next_layer_id' --json" 2>&1)"
  attach_code=$?

  wait "$watcher_pid"
  watch_code=$?

  add_code=1
  removed_code=1
  renamed_old_code=1
  renamed_new_code=1

  wait_for_content "$watch_path/delta-added.txt" "added-two" 40 && add_code=0
  wait_for_absent "$watch_path/delta-removed.txt" 40 && removed_code=0
  wait_for_absent "$watch_path/delta-renamed-old.txt" 40 && renamed_old_code=0
  wait_for_content "$watch_path/delta-renamed-new.txt" "renamed-two" 40 && renamed_new_code=0
  marker_after="$(marker_signature "$watch_path")"
  marker_changed=0
  if [ "$marker_before" != "$marker_after" ]; then
    marker_changed=1
  fi

  set -e

  if [ "$attach_code" -eq 0 ] &&
    [ "$add_code" -eq 0 ] &&
    [ "$removed_code" -eq 0 ] &&
    [ "$renamed_old_code" -eq 0 ] &&
    [ "$renamed_new_code" -eq 0 ] &&
    { [ "$watch_code" -eq 0 ] || [ "$marker_changed" -eq 1 ]; }; then
    printf "PASS | %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "FAIL | %s\n" "$name"
    FAIL=$((FAIL + 1))
  fi

  printf "%s\n" "$attach_out" | sed -n '1,8p'
  sed -n '1,4p' "$watch_file"
  printf "delta-added=%s\n" "$(read_text_file "$watch_path/delta-added.txt")"
  printf "delta-removed-exists=%s\n" "$([ -e "$watch_path/delta-removed.txt" ] && echo yes || echo no)"
  printf "delta-renamed-old-exists=%s\n" "$([ -e "$watch_path/delta-renamed-old.txt" ] && echo yes || echo no)"
  printf "delta-renamed-new=%s\n" "$(read_text_file "$watch_path/delta-renamed-new.txt")"
  printf "marker-changed=%s\n\n" "$marker_changed"
  rm -f "$watch_file"
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
PARENT1_ID=""
PARENT1_MOUNT_PATH=""
CHILD1_ID=""
CHILD1_MOUNT_PATH=""
PARENT2_ID=""
PARENT2_MOUNT_PATH=""
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
      printf "FAIL | layer create 2\n"
      FAIL=$((FAIL + 1))
      sed -n '1,8p' /tmp/spaces-qa-layer2.err
      printf "\n"
    else
      printf "PASS | layer create 2\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$L2_JSON" | sed -n '1,8p'
      LAYER2_ID="$(printf "%s" "$L2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
      LAYER2_MOUNT_PATH="$(printf "%s" "$L2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
    fi
  fi

  if [ -n "$LAYER_MOUNT_PATH" ]; then
    printf "layer-one\n" > "$LAYER_MOUNT_PATH/hot-switch.txt" || true
    printf "delta-removed\n" > "$LAYER_MOUNT_PATH/delta-removed.txt" || true
    printf "renamed-two\n" > "$LAYER_MOUNT_PATH/delta-renamed-old.txt" || true
    mkdir -p "$LAYER_MOUNT_PATH/nested/deep" || true
    printf "deep-one\n" > "$LAYER_MOUNT_PATH/nested/deep/file.txt" || true
  fi
  if [ -n "$LAYER2_MOUNT_PATH" ]; then
    printf "layer-two\n" > "$LAYER2_MOUNT_PATH/hot-switch.txt" || true
    printf "added-two\n" > "$LAYER2_MOUNT_PATH/delta-added.txt" || true
    printf "renamed-two\n" > "$LAYER2_MOUNT_PATH/delta-renamed-new.txt" || true
    mkdir -p "$LAYER2_MOUNT_PATH/nested/deep" || true
    printf "deep-two\n" > "$LAYER2_MOUNT_PATH/nested/deep/file.txt" || true
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

  if [ -n "$EP_ID" ]; then
    P1_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-parent-a --json" 2>/tmp/spaces-qa-parent1.err || true)"
    if [ -s /tmp/spaces-qa-parent1.err ]; then
      printf "FAIL | layer create parent-a\n"
      FAIL=$((FAIL + 1))
      sed -n '1,8p' /tmp/spaces-qa-parent1.err
      printf "\n"
    else
      printf "PASS | layer create parent-a\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$P1_JSON" | sed -n '1,8p'
      PARENT1_ID="$(printf "%s" "$P1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
      PARENT1_MOUNT_PATH="$(printf "%s" "$P1_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
    fi

    if [ -n "$PARENT1_ID" ]; then
      C1_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-child-a --parent '$PARENT1_ID' --json" 2>/tmp/spaces-qa-child1.err || true)"
      if [ -s /tmp/spaces-qa-child1.err ]; then
        printf "FAIL | layer create child-a\n"
        FAIL=$((FAIL + 1))
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
      printf "FAIL | layer create parent-b\n"
      FAIL=$((FAIL + 1))
      sed -n '1,8p' /tmp/spaces-qa-parent2.err
      printf "\n"
    else
      printf "PASS | layer create parent-b\n"
      PASS=$((PASS + 1))
      printf "%s\n\n" "$P2_JSON" | sed -n '1,8p'
      PARENT2_ID="$(printf "%s" "$P2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.id||"")')"
      PARENT2_MOUNT_PATH="$(printf "%s" "$P2_JSON" | node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(0,"utf8"));process.stdout.write(j.mountPath||"")')"
    fi

    if [ -n "$PARENT2_ID" ]; then
      C2_JSON="$(sh -lc "$CMD layer create --entrypoint '$EP_ID' --name qa-child-b --parent '$PARENT2_ID' --json" 2>/tmp/spaces-qa-child2.err || true)"
      if [ -s /tmp/spaces-qa-child2.err ]; then
        printf "FAIL | layer create child-b\n"
        FAIL=$((FAIL + 1))
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

  if [ -n "$PARENT1_MOUNT_PATH" ]; then
    printf "parent-one\n" > "$PARENT1_MOUNT_PATH/parent-switch.txt" || true
  fi
  if [ -n "$PARENT2_MOUNT_PATH" ]; then
    printf "parent-two\n" > "$PARENT2_MOUNT_PATH/parent-switch.txt" || true
  fi
  if [ -n "$CHILD1_ID" ] && [ -n "$CHILD2_ID" ]; then
    run_case "mount attach parent child-a baseline" 0 sh -lc "$CMD mount attach '$MOUNT_ID' '$CHILD1_ID' --json"
    run_hot_reload_switch_case "mount attach parent-layer switch emits watcher event + content update" "$MOUNT_ID" "$CHILD2_ID" "$MOUNTDIR" "parent-switch.txt" "parent-switch.txt" "parent-two"
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
      printf "FAIL | layer create other entrypoint\n"
      FAIL=$((FAIL + 1))
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
