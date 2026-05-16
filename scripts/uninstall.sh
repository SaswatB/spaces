#!/usr/bin/env bash
set -euo pipefail

BIN_DIR="${SPACES_BIN_DIR:-$HOME/.local/bin}"

rm -f "$BIN_DIR/spaces"
rm -f "$BIN_DIR/spacesd"
rm -f "$BIN_DIR/spaces-uninstall"
rm -rf "$BIN_DIR/spacesd-runtime"
rm -rf "$BIN_DIR/spacesd-lib"

echo "Removed Spaces from $BIN_DIR"
