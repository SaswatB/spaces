#!/usr/bin/env bash
set -euo pipefail

# Installs prebuilt release artifacts. Local development builds are not consumed by this script.

BIN_DIR="${SPACES_BIN_DIR:-$HOME/.local/bin}"
REPO="${SPACES_GITHUB_REPOSITORY:-SaswatB/spaces}"
API_BASE_URL="${SPACES_GITHUB_API_BASE_URL:-https://api.github.com}"
BASE_URL="${SPACES_RELEASE_BASE_URL:-https://github.com/$REPO/releases/download}"

if [ "${SPACES_VERSION:-}" = "" ]; then
  VERSION="$(
    curl -fsSL "$API_BASE_URL/repos/$REPO/releases/latest" \
      | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' \
      | head -n 1
  )"
  if [ "$VERSION" = "" ]; then
    echo "Could not resolve latest Spaces release. Set SPACES_VERSION explicitly."
    exit 1
  fi
else
  VERSION="$SPACES_VERSION"
fi

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Darwin) PLATFORM="darwin" ;;
  *)
    echo "Unsupported OS for Spaces release install: $OS"
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
TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$BIN_DIR"

RELEASE_TARBALL="$TMP_DIR/spaces-${VERSION}-${SUFFIX}.tar.gz"
CHECKSUM_FILE="$TMP_DIR/SHA256SUMS-${SUFFIX}"

curl -fL "$BASE_URL/v$VERSION/spaces-${VERSION}-${SUFFIX}.tar.gz" -o "$RELEASE_TARBALL"

if curl -fsL "$BASE_URL/v$VERSION/SHA256SUMS-${SUFFIX}" -o "$CHECKSUM_FILE"; then
  (
    cd "$TMP_DIR"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum -c "SHA256SUMS-${SUFFIX}"
    else
      shasum -a 256 -c "SHA256SUMS-${SUFFIX}"
    fi
  )
else
  echo "No checksum file found for v$VERSION; installing without checksum verification."
fi

tar -xzf "$RELEASE_TARBALL" -C "$TMP_DIR"

if [ ! -f "$TMP_DIR/spaces" ]; then
  echo "spaces binary not found in archive"
  exit 1
fi

if [ ! -f "$TMP_DIR/spacesd" ]; then
  echo "spacesd binary not found in archive"
  exit 1
fi

install -m 0755 "$TMP_DIR/spaces" "$BIN_DIR/spaces"
install -m 0755 "$TMP_DIR/spacesd" "$BIN_DIR/spacesd"

if [ -d "$TMP_DIR/spacesd-runtime" ]; then
  rm -rf "$BIN_DIR/spacesd-runtime"
  cp -R "$TMP_DIR/spacesd-runtime" "$BIN_DIR/spacesd-runtime"
fi

if [ -d "$TMP_DIR/spacesd-lib" ]; then
  rm -rf "$BIN_DIR/spacesd-lib"
  cp -R "$TMP_DIR/spacesd-lib" "$BIN_DIR/spacesd-lib"
fi

echo "Installed Spaces $VERSION to $BIN_DIR"
echo "Make sure $BIN_DIR is on your PATH."
