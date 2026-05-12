#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${SPACES_VERSION:-${1:-}}"
if [ "$VERSION" = "" ]; then
  echo "SPACES_VERSION or first argument is required (ex: 0.1.0)"
  exit 1
fi

JAVA_BIN="${JAVA_HOME:-}/bin/java"
JDEPS_BIN="${JAVA_HOME:-}/bin/jdeps"
JLINK_BIN="${JAVA_HOME:-}/bin/jlink"
if [ "${JAVA_HOME:-}" = "" ]; then
  JAVA_BIN="java"
  JDEPS_BIN="jdeps"
  JLINK_BIN="jlink"
fi

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')"
if [ "$JAVA_MAJOR" -lt 21 ]; then
  TOOLCHAIN_HOME="$(
    gradle -p "$ROOT_DIR/packages/daemon-jvm" -q javaToolchains \
      | awk '/Language Version:[[:space:]]*21/{want=1} want && /Location:/{print $3; exit}'
  )"
  if [ "$TOOLCHAIN_HOME" != "" ] && [ -x "$TOOLCHAIN_HOME/bin/java" ]; then
    JAVA_BIN="$TOOLCHAIN_HOME/bin/java"
    JDEPS_BIN="$TOOLCHAIN_HOME/bin/jdeps"
    JLINK_BIN="$TOOLCHAIN_HOME/bin/jlink"
    JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')"
  fi
fi

if [ "$JAVA_MAJOR" -lt 21 ]; then
  echo "Java 21+ is required to build release artifacts."
  echo "Current java reports major version: $JAVA_MAJOR"
  echo "Install JDK 21 or run a Gradle task once to auto-provision toolchains."
  exit 1
fi

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Darwin) PLATFORM="darwin" ;;
  *)
    echo "Unsupported OS for release artifacts: $OS"
    echo "Spaces release artifacts are currently macOS-only."
    exit 1
    ;;
esac

case "$ARCH" in
  arm64|aarch64) ARCH="arm64" ;;
  x86_64|amd64) ARCH="x64" ;;
  *)
    echo "Unsupported arch: $ARCH"
    exit 1
    ;;
esac

SUFFIX="${PLATFORM}-${ARCH}"
OUT_DIR="${RELEASE_OUT_DIR:-$ROOT_DIR/release}"
WORK_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

mkdir -p "$OUT_DIR"

echo "Building CLI single-file binary (bun)..."
bun build "$ROOT_DIR/packages/web/bin/spaces.ts" --compile --outfile "$WORK_DIR/spaces"
chmod +x "$WORK_DIR/spaces"

echo "Building daemon distribution (gradle installDist)..."
gradle -p "$ROOT_DIR/packages/daemon-jvm" installDist

DIST_DIR="$ROOT_DIR/packages/daemon-jvm/build/install/spaces-daemon"
LIB_DIR="$DIST_DIR/lib"
if [ ! -d "$LIB_DIR" ]; then
  echo "Daemon lib directory not found: $LIB_DIR"
  exit 1
fi

echo "Creating bundled Java runtime (jlink)..."
RAW_MODULES="$("$JDEPS_BIN" --ignore-missing-deps --multi-release 21 --print-module-deps "$LIB_DIR"/*.jar 2>/dev/null || true)"
MODULES="$(printf "%s\n" "$RAW_MODULES" | grep -E '^[a-z0-9,._-]+$' | tail -n1 || true)"
if [ "$MODULES" = "" ]; then
  MODULES="java.base,java.logging,java.sql,jdk.unsupported"
fi
"$JLINK_BIN" \
  --add-modules "$MODULES" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --output "$WORK_DIR/spacesd-runtime"

mkdir -p "$WORK_DIR/spacesd-lib"
cp "$LIB_DIR"/*.jar "$WORK_DIR/spacesd-lib/"

cat > "$WORK_DIR/spacesd" <<'LAUNCHER'
#!/usr/bin/env sh
set -eu
SELF_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_BIN="$SELF_DIR/spacesd-runtime/bin/java"
CLASSPATH="$SELF_DIR/spacesd-lib/*"
exec "$JAVA_BIN" -classpath "$CLASSPATH" com.spaces.daemon.MainKt "$@"
LAUNCHER
chmod +x "$WORK_DIR/spacesd"

CLI_ARCHIVE="$OUT_DIR/spaces-${VERSION}-${SUFFIX}.tar.gz"
DAEMON_ARCHIVE="$OUT_DIR/spacesd-${VERSION}-${SUFFIX}.tar.gz"

echo "Packing $CLI_ARCHIVE"
tar -C "$WORK_DIR" -czf "$CLI_ARCHIVE" spaces
echo "Packing $DAEMON_ARCHIVE"
tar -C "$WORK_DIR" -czf "$DAEMON_ARCHIVE" spacesd spacesd-runtime spacesd-lib

CHECKSUM_FILE="$OUT_DIR/SHA256SUMS-${SUFFIX}"
echo "Writing $CHECKSUM_FILE"
(
  cd "$OUT_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "spaces-${VERSION}-${SUFFIX}.tar.gz" "spacesd-${VERSION}-${SUFFIX}.tar.gz"
  else
    shasum -a 256 "spaces-${VERSION}-${SUFFIX}.tar.gz" "spacesd-${VERSION}-${SUFFIX}.tar.gz"
  fi
) > "$CHECKSUM_FILE"

echo "Artifacts written:"
echo "  $CLI_ARCHIVE"
echo "  $DAEMON_ARCHIVE"
echo "  $CHECKSUM_FILE"
