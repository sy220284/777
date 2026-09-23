#!/usr/bin/env bash
set -euo pipefail

package_name="${1:?usage: smoke-test-android-startup.sh <package> [wait-seconds]}"
wait_seconds="${2:-3}"

adb logcat -c
adb shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1
sleep "$wait_seconds"

if adb shell pidof "$package_name" >/dev/null 2>&1; then
  echo "Startup smoke passed: $package_name is alive"
  exit 0
fi

echo "=== startup crash buffer: $package_name ==="
adb logcat -d -b crash -v threadtime || true
echo "=== startup filtered logcat: $package_name ==="
adb logcat -d -v threadtime \
  | grep -E "AndroidRuntime|FATAL EXCEPTION|$package_name|DshApplication|MainActivity" \
  || true
exit 1
