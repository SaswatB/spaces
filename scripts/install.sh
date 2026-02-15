#!/usr/bin/env sh
set -euo pipefail

# Installs prebuilt release artifacts.
# Local development builds are not consumed by this script.

if [ "${SPACES_VERSION:-}" = "" ]; then
  echo "SPACES_VERSION is required (ex: 0.1.0)"
  exit 1
fi

if [ "${SPACES_RELEASE_BASE_URL:-}" = "" ]; then
  echo "SPACES_RELEASE_BASE_URL is required (ex: https://github.com/OWNER/REPO/releases/download)"
  exit 1
fi

BIN_DIR="${SPACES_BIN_DIR:-$HOME/.local/bin}"
VERSION="$SPACES_VERSION"
BASE_URL="$SPACES_RELEASE_BASE_URL"

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Darwin) PLATFORM="darwin" ;;
  Linux) PLATFORM="linux" ;;
  *)
    echo "Unsupported OS: $OS"
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
TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$BIN_DIR"

CLI_TARBALL="$TMP_DIR/spaces-${VERSION}-${SUFFIX}.tar.gz"
DAEMON_TARBALL="$TMP_DIR/spacesd-${VERSION}-${SUFFIX}.tar.gz"

curl -fL "$BASE_URL/v$VERSION/spaces-${VERSION}-${SUFFIX}.tar.gz" -o "$CLI_TARBALL"
curl -fL "$BASE_URL/v$VERSION/spacesd-${VERSION}-${SUFFIX}.tar.gz" -o "$DAEMON_TARBALL"

tar -xzf "$CLI_TARBALL" -C "$TMP_DIR"
tar -xzf "$DAEMON_TARBALL" -C "$TMP_DIR"

if [ ! -f "$TMP_DIR/spaces" ]; then
  echo "spaces binary not found in archive"
  exit 1
fi

DAEMON_PATH=""
if [ -f "$TMP_DIR/spacesd" ]; then
  DAEMON_PATH="$TMP_DIR/spacesd"
elif [ -f "$TMP_DIR/spaces-daemon" ]; then
  DAEMON_PATH="$TMP_DIR/spaces-daemon"
fi

if [ "$DAEMON_PATH" = "" ]; then
  echo "spacesd or spaces-daemon binary not found in archive"
  exit 1
fi

install -m 0755 "$TMP_DIR/spaces" "$BIN_DIR/spaces"
install -m 0755 "$DAEMON_PATH" "$BIN_DIR/spacesd"

echo "Installed spaces and spacesd to $BIN_DIR"
echo "Make sure $BIN_DIR is on your PATH."
