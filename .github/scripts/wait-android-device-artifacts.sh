#!/usr/bin/env bash
set -euo pipefail

# Only APKs produced from this exact checkout are valid device test inputs.
verify_apks() {
  local root="$1" expected_source="${2:-}" expected_head="${3:-}" name
  for name in app-debug.apk app-debug-androidTest.apk app-optimized.apk; do
    if [[ ! -s "$root/$name" ]]; then
      echo "::error::Missing or empty Android APK: $name" >&2; return 1
    fi
  done
  for name in SHA256SUMS.txt source-sha.txt head-sha.txt; do
    if [[ ! -s "$root/$name" ]]; then
      echo "::error::Missing APK provenance: $name" >&2; return 1
    fi
  done
  local source_sha head_sha manifest expected
  source_sha="$(tr -d '\r\n' < "$root/source-sha.txt")"
  head_sha="$(tr -d '\r\n' < "$root/head-sha.txt")"
  if [[ ! "$source_sha" =~ ^[0-9a-f]{40}$ || ! "$head_sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "::error::Invalid APK source Git SHA" >&2; return 1
  fi
  if [[ -n "$expected_source" && "$source_sha" != "$expected_source" ]]; then
    echo "::error::APK checkout SHA differs from current checkout" >&2; return 1
  fi
  if [[ -n "$expected_head" && "$head_sha" != "$expected_head" ]]; then
    echo "::error::APK PR head SHA does not match" >&2; return 1
  fi
  manifest="$(awk '{print $2}' "$root/SHA256SUMS.txt" | sort)"
  expected="$(printf '%s\n' app-debug-androidTest.apk app-debug.apk app-optimized.apk | sort)"
  if [[ "$manifest" != "$expected" ]]; then
    echo "::error::APK manifest missing or unexpected filenames" >&2; return 1
  fi
  (cd "$root" && sha256sum -c SHA256SUMS.txt)
}

if [[ "${1:-}" == "--self-test" ]]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  if verify_apks "$fixture" >/dev/null 2>&1; then exit 1; fi
  for name in app-debug.apk app-debug-androidTest.apk app-optimized.apk; do
    printf 'fixture %s\n' "$name" > "$fixture/$name"
  done
  if verify_apks "$fixture" >/dev/null 2>&1; then exit 1; fi
  printf '%040d\n' 1 > "$fixture/source-sha.txt"
  printf '%040d\n' 2 > "$fixture/head-sha.txt"
  (cd "$fixture" && sha256sum app-debug.apk app-debug-androidTest.apk app-optimized.apk > SHA256SUMS.txt)
  verify_apks "$fixture" "$(cat "$fixture/source-sha.txt")" "$(cat "$fixture/head-sha.txt")"
  if verify_apks "$fixture" "$(printf '%040d' 9)" >/dev/null 2>&1; then exit 1; fi
  printf 'corrupt\n' >> "$fixture/app-debug.apk"
  if verify_apks "$fixture" >/dev/null 2>&1; then exit 1; fi
  echo "APK provenance, checkout SHA and SHA-256 tampering self-test passed"
  exit 0
fi

if [[ "${1:-}" == "--verify" ]]; then
  verify_apks "${2:-.ci/device-apks}" "${GITHUB_SHA:-}" "${3:-}"
  exit
fi

: "${GITHUB_REPOSITORY:?GitHub repository required}"
: "${GH_TOKEN:?Read-only GitHub Actions token required}"
command -v gh >/dev/null || { echo "::error::GitHub CLI missing" >&2; exit 1; }

mode="${1:---current-run}"
output=".ci/device-apks"
if [[ "$mode" != "--from-ci" && "$mode" != "--current-run" ]]; then
  output="$mode"; mode="--current-run"
elif [[ -n "${2:-}" ]]; then
  output="$2"
fi

if [[ "$mode" == "--from-ci" ]]; then
  : "${GITHUB_SHA:?Checkout SHA required}"
  : "${GITHUB_EVENT_NAME:?GitHub event required}"
  : "${GITHUB_REF_NAME:?GitHub ref required}"
  if [[ "$GITHUB_EVENT_NAME" == "pull_request" ]]; then
    : "${GITHUB_EVENT_PATH:?PR event JSON required}"
    expected_head="$(jq -er '.pull_request.head.sha' "$GITHUB_EVENT_PATH")"
    branch="$(jq -er '.pull_request.head.ref' "$GITHUB_EVENT_PATH")"
    event=pull_request
  elif [[ "$GITHUB_EVENT_NAME" == "workflow_dispatch" ]]; then
    expected_head="$GITHUB_SHA"
    branch="$GITHUB_REF_NAME"
    event=manual
  else
    echo "::error::Cross-workflow reuse requires PR or explicit dispatch" >&2; exit 1
  fi
  for attempt in $(seq 1 180); do
    runs_json="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs?head_sha=$expected_head&per_page=100")"
    ci_run="$(printf '%s' "$runs_json" | jq -r \
      --arg branch "$branch" --arg head "$expected_head" --arg event "$event" '
      [.workflow_runs[] | select(
        .name == "CI" and .head_sha == $head and .head_branch == $branch and
        (if $event == "pull_request" then .event == "pull_request"
         else (.event == "push" or .event == "workflow_dispatch") end)
      )] | sort_by(.created_at, .id) | last | .id // empty')"
    if [[ -n "$ci_run" ]]; then
      artifact="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$ci_run/artifacts?per_page=100" \
        --jq '.artifacts[] | select(.name == "android-x86_64-test-apks" and .expired == false) | .id')"
      if [[ -n "$artifact" ]]; then
        staging="$(mktemp -d)"
        if gh run download "$ci_run" --repo "$GITHUB_REPOSITORY" \
          --name android-x86_64-test-apks --dir "$staging" &&
          verify_apks "$staging" "$GITHUB_SHA" "$expected_head"; then
          mkdir -p "$output"
          cp "$staging"/{app-debug.apk,app-debug-androidTest.apk,app-optimized.apk,SHA256SUMS.txt,source-sha.txt,head-sha.txt} "$output/"
          rm -rf "$staging"
          echo "Verified CI run $ci_run APKs match checkout $GITHUB_SHA"
          exit 0
        fi
        rm -rf "$staging"
        echo "::error::CI APK source SHA or checksum verification failed" >&2
        exit 1
      fi
      conclusion="$(printf '%s' "$runs_json" | jq -r --argjson id "$ci_run" \
        '.workflow_runs[] | select(.id == $id) | .conclusion // empty')"
      if [[ -n "$conclusion" ]]; then
        echo "::error::CI run $ci_run finished ($conclusion) without matching APKs" >&2
        exit 1
      fi
    fi
    if (( attempt % 6 == 0 )); then
      echo "Awaiting exact-revision CI APKs (probe $attempt/180)"
    fi
    sleep 10
  done
  echo "::error::No exact-revision CI APK artifact within 30 minutes" >&2
  exit 1
fi

: "${GITHUB_RUN_ID:?Current workflow run ID required}"
mkdir -p "$output"
for attempt in $(seq 1 90); do
  artifact="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/artifacts?per_page=100" \
    --jq '.artifacts[] | select(.name == "android-x86_64-test-apks" and .expired == false) | .id' \
    2>/dev/null || true)"
  if [[ -n "$artifact" ]]; then
    if gh run download "$GITHUB_RUN_ID" --repo "$GITHUB_REPOSITORY" \
      --name android-x86_64-test-apks --dir "$output"; then
      verify_apks "$output" "${GITHUB_SHA:-}"
      echo "Verified current-run APK origin and SHA-256: $GITHUB_RUN_ID"
      exit 0
    fi
    rm -f "$output"/{app-debug.apk,app-debug-androidTest.apk,app-optimized.apk,SHA256SUMS.txt,source-sha.txt,head-sha.txt}
  fi
  state="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs?per_page=100" \
    --jq '.jobs[] | select(.name == "device-artifacts-x86") | .conclusion // empty' \
    2>/dev/null || true)"
  if [[ "$state" == *failure* || "$state" == *cancelled* || "$state" == *timed_out* ]]; then
    echo "::error::device-artifacts-x86 ended with $state" >&2; exit 1
  fi
  if (( attempt % 6 == 0 )); then
    echo "Emulator booted; same-run APK not ready (probe $attempt/90)"
  fi
  sleep 10
done
echo "::error::No same-run APK within 15 minutes" >&2
exit 1
