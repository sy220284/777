#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOCK="$ROOT/upstream/deepseek-harness.lock.json"
RUNNER="$ROOT/tools/reference-validation/official-runner.ts"
ADVANCED_RUNNER="$ROOT/tools/reference-validation/official-advanced-runner.ts"
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
ADVANCED_RUNNER_TARGET="$UPSTREAM/packages/session/session-projection/.777-advanced-reference-runner.ts"
cp "$ADVANCED_RUNNER" "$ADVANCED_RUNNER_TARGET"

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

OFFICIAL_ADVANCED="$TMP/official-advanced.json"
NATIVE_ADVANCED="$TMP/native-advanced.json"
(
  cd "$UPSTREAM/packages/session/session-projection"
  corepack pnpm exec tsx .777-advanced-reference-runner.ts
) > "$OFFICIAL_ADVANCED"

"$ROOT/gradlew" -q :reference-validation:runAdvancedConformance   -PadvancedOutput="$NATIVE_ADVANCED"

node - "$OFFICIAL_ADVANCED" "$NATIVE_ADVANCED" <<'NODE'
const fs = require('fs')
const [officialPath, nativePath] = process.argv.slice(2)
const official = JSON.parse(fs.readFileSync(officialPath, 'utf8'))
const native = JSON.parse(fs.readFileSync(nativePath, 'utf8'))
const stable = value => {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])]))
  }
  return value
}
const left = JSON.stringify(stable(official))
const right = JSON.stringify(stable(native))
if (left !== right) {
  console.error('official advanced conformance mismatch')
  console.error('official:', JSON.stringify(official, null, 2))
  console.error('native:', JSON.stringify(native, null, 2))
  process.exit(1)
}
NODE
echo "advanced projection conformance matched $REPOSITORY@$COMMIT"
