#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOCK="$ROOT/upstream/deepseek-harness.lock.json"
RUNNER="$ROOT/tools/reference-validation/official-runner.ts"
VECTOR_DIR="$ROOT/reference-validation/src/test/resources/vectors"
OUTPUT_DIR="$ROOT/reference-validation/src/test/resources/official"

REPOSITORY="$(node -e "const f=require('fs');const j=JSON.parse(f.readFileSync(process.argv[1],'utf8'));process.stdout.write(j.repository)" "$LOCK")"
COMMIT="$(node -e "const f=require('fs');const j=JSON.parse(f.readFileSync(process.argv[1],'utf8'));process.stdout.write(j.commit)" "$LOCK")"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
UPSTREAM="$TMP/deepseek-harness"

git clone --filter=blob:none "https://github.com/$REPOSITORY.git" "$UPSTREAM"
git -C "$UPSTREAM" checkout --detach "$COMMIT"
cp "$RUNNER" "$UPSTREAM/.777-reference-runner.ts"

(
  cd "$UPSTREAM"
  corepack pnpm install --frozen-lockfile
)

mkdir -p "$OUTPUT_DIR"
for vector in "$VECTOR_DIR"/*.json; do
  name="$(basename "$vector")"
  absolute_vector="$(cd "$(dirname "$vector")" && pwd)/$name"
  (
    cd "$UPSTREAM"
    corepack pnpm exec tsx .777-reference-runner.ts "$absolute_vector"
  ) > "$OUTPUT_DIR/$name"
  echo "refreshed $name from $REPOSITORY@$COMMIT"
done
