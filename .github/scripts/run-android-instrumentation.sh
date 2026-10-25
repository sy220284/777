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
capture_failure() {
  local status=$?
  if (( status != 0 )); then
    # AndroidJUnitRunner cannot report an exception after its own process dies.
    # Keep the platform crash stack and exit reason in this job's failure log.
    adb logcat -d -v threadtime -b crash >&2 || true
    adb shell dumpsys activity exit-info com.sy220284.dshmobile.debug >&2 || true
  fi
}
trap capture_failure EXIT
adb shell am instrument -w -r "$component" | tr -d '\r' | tee "$result_file"
# Native timeline captures are written by instrumentation after its assertions.
# Collect before the emulator exits; the original test result remains authoritative.
evidence_path="/sdcard/Android/data/com.sy220284.dshmobile.debug/files/work-timeline-review"
if adb shell test -d "$evidence_path"; then
  mkdir -p .ci/work-timeline-review
  adb pull "$evidence_path/." .ci/work-timeline-review/
fi
if ! validate_result "$result_file"; then
  echo '::error::Android instrumentation did not report a non-empty successful test result'
  exit 1
fi
