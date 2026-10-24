#!/usr/bin/env bash
set -euo pipefail

# Run two separate AndroidJUnitRunner incarnations with an OS process death
# between them. Do not claim this coverage when a single instrumentation
# method simulates a re-open in-process.
component="com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner"
test_class="com.labteto.dshmobile.local.session.SqliteCrossProcessRestoreAcceptanceAndroidTest"
app="com.sy220284.dshmobile.debug"
test_app="$app.test"
root=".ci/cross-process-restore"
mkdir -p "$root"
for phase in stage resume; do
  log="$root/$phase.log"
  echo "CROSS_PROCESS_RESTORE begin=$phase"
  adb shell am instrument -w -r -e class "$test_class" -e dsh_acceptance "$phase" \
      "$component" 2>&1 | tr -d '\r' | tee "$log"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$log" ||
     ! grep -Eq '^OK \(1 test\)' "$log"; then
    echo "CROSS_PROCESS_RESTORE_ERROR: $phase instrumentation did not pass" >&2
    exit 1
  fi
  if [[ "$phase" == stage ]]; then
    # kill both packages from adb, i.e. outside the app process. The pending
    # SQLite backup must survive and be consumed in a brand-new test process.
    adb shell am force-stop "$app"
    adb shell am force-stop "$test_app" || true
    echo "CROSS_PROCESS_RESTORE process_boundary=external_force_stop"
  fi
done
echo "CROSS_PROCESS_RESTORE_OK fresh_process_activated_pending_restore=true"
