#!/usr/bin/env bash
set -euo pipefail

# Installs prebuilt release artifacts. Local development builds are not consumed by this script.

BIN_DIR="${SPACES_BIN_DIR:-$HOME/.local/bin}"
REPO="${SPACES_GITHUB_REPOSITORY:-SaswatB/spaces}"
API_BASE_URL="${SPACES_GITHUB_API_BASE_URL:-https://api.github.com}"
BASE_URL="${SPACES_RELEASE_BASE_URL:-https://github.com/$REPO/releases/download}"
GITHUB_TOKEN_VALUE="${SPACES_GITHUB_TOKEN:-${GITHUB_TOKEN:-}}"

spaces_state_dir() {
  if [ "${SPACES_STATE_DIR:-}" != "" ]; then
    printf "%s\n" "$SPACES_STATE_DIR"
    return
  fi
  case "$(uname -s)" in
    Darwin) printf "%s\n" "$HOME/Library/Application Support/Spaces" ;;
    *) printf "%s\n" "${XDG_STATE_HOME:-$HOME/.local/state}/spaces" ;;
  esac
}

write_auto_update_config() {
  enabled="$1"
  state_dir="$(spaces_state_dir)"
  mkdir -p "$state_dir"
  cat > "$state_dir/config.json" <<EOF
{
  "autoUpdateCheck": $enabled
}
EOF
}

configure_auto_update_check() {
  if [ "${SPACES_AUTO_UPDATE_CHECK:-}" != "" ]; then
    case "$(printf "%s" "$SPACES_AUTO_UPDATE_CHECK" | tr '[:upper:]' '[:lower:]')" in
      1|true|on|yes)
        write_auto_update_config true
        echo "Daily update notices enabled."
        return
        ;;
      0|false|off|no)
        write_auto_update_config false
        echo "Daily update notices disabled."
        return
        ;;
      *)
        echo "Ignoring invalid SPACES_AUTO_UPDATE_CHECK=$SPACES_AUTO_UPDATE_CHECK"
        ;;
    esac
  fi

  if [ ! -t 0 ]; then
    echo "Daily update notices are off by default."
    return
  fi

  printf "Enable daily update notices when the Spaces CLI is used? [y/N] "
  read -r answer
  case "$(printf "%s" "$answer" | tr '[:upper:]' '[:lower:]')" in
    y|yes)
      write_auto_update_config true
      echo "Daily update notices enabled."
      ;;
    *)
      write_auto_update_config false
      echo "Daily update notices disabled."
      ;;
  esac
}

if [ "$GITHUB_TOKEN_VALUE" = "" ] && command -v gh >/dev/null 2>&1; then
  GITHUB_TOKEN_VALUE="$(gh auth token 2>/dev/null || true)"
fi

curl_get() {
  url="$1"
  if [ "$GITHUB_TOKEN_VALUE" != "" ]; then
    curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN_VALUE" "$url"
  else
    curl -fsSL "$url"
  fi
}

curl_download() {
  url="$1"
  target="$2"
  if [ "$GITHUB_TOKEN_VALUE" != "" ]; then
    curl -fL -H "Authorization: Bearer $GITHUB_TOKEN_VALUE" "$url" -o "$target"
  else
    curl -fL "$url" -o "$target"
  fi
}

if [ "${SPACES_VERSION:-}" = "" ]; then
  VERSION="$(
    curl_get "$API_BASE_URL/repos/$REPO/releases/latest" \
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

download_release_asset() {
  asset="$1"
  target="$2"
  if [ "${SPACES_RELEASE_BASE_URL:-}" = "" ] && command -v gh >/dev/null 2>&1; then
    gh release download "v$VERSION" --repo "$REPO" --pattern "$asset" --dir "$TMP_DIR" --clobber >/dev/null
    if [ "$TMP_DIR/$asset" != "$target" ]; then
      mv "$TMP_DIR/$asset" "$target"
    fi
  else
    curl_download "$BASE_URL/v$VERSION/$asset" "$target"
  fi
}

download_release_asset "spaces-${VERSION}-${SUFFIX}.tar.gz" "$RELEASE_TARBALL"

if download_release_asset "SHA256SUMS-${SUFFIX}" "$CHECKSUM_FILE"; then
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

if [ ! -f "$TMP_DIR/spaces-uninstall" ]; then
  echo "spaces-uninstall not found in archive"
  exit 1
fi

install -m 0755 "$TMP_DIR/spaces" "$BIN_DIR/spaces"
install -m 0755 "$TMP_DIR/spacesd" "$BIN_DIR/spacesd"
install -m 0755 "$TMP_DIR/spaces-uninstall" "$BIN_DIR/spaces-uninstall"

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
configure_auto_update_check
