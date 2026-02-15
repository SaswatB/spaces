#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${SPACES_VERSION:-0.1.0}"
WORK_ROOT="$(mktemp -d /tmp/spaces-qa-script.XXXXXX)"
ENTRY="$WORK_ROOT/entry"
MOUNTDIR="$WORK_ROOT/mount"
STATE="$WORK_ROOT/state"
DATA="$WORK_ROOT/data"
DBDIR="$WORK_ROOT/db"
RUNTIME_ROOT="$WORK_ROOT/runtime"
API_PORT="${SPACES_QA_API_PORT:-34327}"
NFS_PORT="${SPACES_QA_NFS_PORT:-13327}"

mkdir -p "$ENTRY" "$MOUNTDIR" "$STATE" "$DATA" "$DBDIR" "$RUNTIME_ROOT"
echo "hello" > "$ENTRY/file.txt"

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
if [ -n "$MOUNT_ID" ]; then
  run_case "mount attach detach" 0 sh -lc "$CMD mount attach '$MOUNT_ID' --json"
  run_case "mount mount" 1 sh -lc "$CMD mount mount '$MOUNT_ID' --json"
  run_case "mount unmount" 1 sh -lc "$CMD mount unmount '$MOUNT_ID' --json"
  run_case "mount delete force" 1 sh -lc "$CMD mount delete '$MOUNT_ID' --force --json"
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
