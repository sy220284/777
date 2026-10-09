#!/usr/bin/env bash
set -euo pipefail

# Prewarm the emulator while a separate runner builds exactly one test APK set.
# Never load APKs from another run or an unverified external source.
verify_apks() {
  local root="$1"
  for name in app-debug.apk app-debug-androidTest.apk app-optimized.apk; do
    if [[ ! -s "$root/$name" ]]; then
      echo "::error::Missing or empty same-run Android APK: $name" >&2
      return 1
    fi
  done
}

if [[ "${1:-}" == "--self-test" ]]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  if verify_apks "$fixture" >/dev/null 2>&1; then
    echo "Missing APK set incorrectly passed" >&2
    exit 1
  fi
  touch "$fixture/app-debug.apk" "$fixture/app-debug-androidTest.apk" "$fixture/app-optimized.apk"
  if verify_apks "$fixture" >/dev/null 2>&1; then
    echo "Empty APK set incorrectly passed" >&2
    exit 1
  fi
  for name in app-debug.apk app-debug-androidTest.apk app-optimized.apk; do
    printf 'fixture\n' > "$fixture/$name"
  done
  verify_apks "$fixture"
  echo "Same-run artifact completeness self-test passed"
  exit 0
fi

: "${GITHUB_REPOSITORY:?Repository identity is required}"
: "${GITHUB_RUN_ID:?Current workflow run identity is required}"
: "${GH_TOKEN:?Read-only GitHub Actions token is required}"
command -v gh >/dev/null || { echo "::error::GitHub CLI is required" >&2; exit 1; }

output="${1:-.ci/device-apks}"
mkdir -p "$output"
# At most 15 minutes; leave budget within the emulator lane's 30-minute timeout.
for attempt in $(seq 1 90); do
  ids="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/artifacts?per_page=100" \
    --jq '.artifacts[] | select(.name == "android-x86_64-test-apks" and .expired == false) | .id' \
    2>/dev/null || true)"
  if [[ -n "$ids" ]]; then
    if gh run download "$GITHUB_RUN_ID" --repo "$GITHUB_REPOSITORY" \
        --name android-x86_64-test-apks --dir "$output"; then
      verify_apks "$output"
      echo "Downloaded verified device APKs from current GitHub Actions run $GITHUB_RUN_ID"
      exit 0
    fi
    rm -f "$output/app-debug.apk" "$output/app-debug-androidTest.apk" "$output/app-optimized.apk"
  fi

  state="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs?per_page=100" \
    --jq '.jobs[] | select(.name == "device-artifacts-x86") | .conclusion // empty' \
    2>/dev/null || true)"
  if [[ "$state" == *failure* || "$state" == *cancelled* || "$state" == *timed_out* ]]; then
    echo "::error::Shared device-artifacts-x86 job ended with $state" >&2
    exit 1
  fi
  if (( attempt % 6 == 0 )); then
    echo "Emulator booted; current-run APK is not ready (probe $attempt/90)"
  fi
  sleep 10
done
echo "::error::No shared APK artifact published in this GitHub Actions run within 15 minutes" >&2
exit 1
