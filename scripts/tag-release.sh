#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage: scripts/tag-release.sh [major|minor|patch]

Bumps Spaces versions, commits the bump, creates an annotated vX.Y.Z tag,
and pushes the commit and tag to origin.

Default bump level: minor
USAGE
}

LEVEL="${1:-minor}"
REMOTE="${SPACES_RELEASE_REMOTE:-origin}"

case "$LEVEL" in
  major|minor|patch) ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    echo "Unsupported bump level: $LEVEL"
    usage
    exit 1
    ;;
esac

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Not inside a git worktree."
  exit 1
fi

if [ "$(git status --porcelain)" != "" ]; then
  echo "Worktree is not clean. Commit or stash changes before tagging a release."
  git status --short
  exit 1
fi

BRANCH="$(git branch --show-current)"
if [ "$BRANCH" = "" ]; then
  echo "Cannot release from a detached HEAD."
  exit 1
fi

if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
  echo "Unknown git remote: $REMOTE"
  exit 1
fi

NEXT_VERSION="$(
  node - "$LEVEL" <<'NODE'
const fs = require("node:fs");

const level = process.argv[2];
const rootPackage = JSON.parse(fs.readFileSync("package.json", "utf8"));
const match = String(rootPackage.version).match(/^(\d+)\.(\d+)\.(\d+)$/);
if (!match) {
  throw new Error(`Unsupported version: ${rootPackage.version}`);
}

let major = Number(match[1]);
let minor = Number(match[2]);
let patch = Number(match[3]);

if (level === "major") {
  major += 1;
  minor = 0;
  patch = 0;
} else if (level === "minor") {
  minor += 1;
  patch = 0;
} else if (level === "patch") {
  patch += 1;
} else {
  throw new Error(`Unsupported bump level: ${level}`);
}

process.stdout.write(`${major}.${minor}.${patch}`);
NODE
)"

TAG="v$NEXT_VERSION"

if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "Tag already exists locally: $TAG"
  exit 1
fi

if git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "Tag already exists on $REMOTE: $TAG"
  exit 1
fi

node - "$NEXT_VERSION" <<'NODE'
const fs = require("node:fs");

const version = process.argv[2];
const jsonFiles = [
  "package.json",
  "packages/web/package.json",
  "packages/daemon-jvm/src/main/resources/openapi.json",
  "packages/web/src/lib/openapi.json",
];

for (const file of jsonFiles) {
  const value = JSON.parse(fs.readFileSync(file, "utf8"));
  if (file.endsWith("openapi.json")) {
    value.info.version = version;
  } else {
    value.version = version;
  }
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

const gradleFile = "packages/daemon-jvm/build.gradle.kts";
const gradle = fs.readFileSync(gradleFile, "utf8");
const updated = gradle.replace(/^version = "([^"]+)"$/m, `version = "${version}"`);
if (updated === gradle) {
  throw new Error(`Could not update ${gradleFile}`);
}
fs.writeFileSync(gradleFile, updated);
NODE

git add \
  package.json \
  packages/web/package.json \
  packages/daemon-jvm/build.gradle.kts \
  packages/daemon-jvm/src/main/resources/openapi.json \
  packages/web/src/lib/openapi.json

git commit -m "Release $TAG"
git tag -a "$TAG" -m "Release $TAG"

git push "$REMOTE" "$BRANCH"
git push "$REMOTE" "$TAG"

echo "Released $TAG."
