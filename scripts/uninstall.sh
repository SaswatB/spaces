#!/usr/bin/env sh
set -euo pipefail

BIN_DIR="${SPACES_BIN_DIR:-$HOME/.local/bin}"

rm -f "$BIN_DIR/spaces"
rm -f "$BIN_DIR/spacesd"
rm -rf "$BIN_DIR/spacesd-runtime"
rm -rf "$BIN_DIR/spacesd-lib"

echo "Removed spaces and spacesd from $BIN_DIR"
