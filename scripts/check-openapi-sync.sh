#!/usr/bin/env bash
set -euo pipefail

pnpm openapi:generate

if ! git diff --quiet -- \
  packages/web/src/lib/openapi.json \
  packages/web/src/lib/openapi-types.ts; then
  echo "OpenAPI artifacts are out of date."
  echo "Run: pnpm openapi:generate"
  git diff -- \
    packages/web/src/lib/openapi.json \
    packages/web/src/lib/openapi-types.ts || true
  exit 1
fi

echo "OpenAPI artifacts are in sync."
