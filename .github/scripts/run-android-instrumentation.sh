#!/usr/bin/env bash
set -euo pipefail

# adb can exit 0 even when AndroidJUnitRunner reports failed tests.
validate_result() {
  local result_file="$1"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[12]|Process crashed' "$result_file"; then
    return 1
  fi
  grep -Eq '^OK \([1-9][0-9]* tests?\)' "$result_file"
}

if [[ "${1:-}" == "--self-test" ]]; then
  fixture_dir="$(mktemp -d)"
  trap 'rm -rf "$fixture_dir"' EXIT
  printf 'OK (3 tests)\nINSTRUMENTATION_CODE: -1\n' > "$fixture_dir/passed"
  printf 'FAILURES!!!\nTests run: 3, Failures: 1\nINSTRUMENTATION_CODE: -1\n' > "$fixture_dir/failed"
  printf 'INSTRUMENTATION_FAILED: Process crashed\n' > "$fixture_dir/crashed"
  printf 'INSTRUMENTATION_STATUS_CODE: 1\n' > "$fixture_dir/incomplete"
  printf 'OK (0 tests)\n' > "$fixture_dir/empty"
  validate_result "$fixture_dir/passed"
  for fixture in failed crashed incomplete empty; do
    if validate_result "$fixture_dir/$fixture"; then
      echo "Unexpected instrumentation success: $fixture" >&2
      exit 1
    fi
  done
  echo 'Android instrumentation result self-test passed'
  exit 0
fi

component="${1:-com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner}"
result_file="${2:-.ci/android-instrumentation-result.txt}"
mkdir -p "$(dirname "$result_file")"
adb shell am instrument -w -r "$component" | tr -d '\r' | tee "$result_file"
if ! validate_result "$result_file"; then
  echo '::error::Android instrumentation did not report a non-empty successful test result'
  exit 1
fi
