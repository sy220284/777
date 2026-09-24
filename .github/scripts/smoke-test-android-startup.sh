#!/usr/bin/env bash
set -euo pipefail

package_name="${1:?usage: smoke-test-android-startup.sh <package> [wait-seconds]}"
wait_seconds="${2:-15}"

adb logcat -c
adb shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1
first_pid=""
stable=true
for ((elapsed = 0; elapsed < wait_seconds; elapsed++)); do
  sleep 1
  current_pid="$(adb shell pidof "$package_name" 2>/dev/null | tr -d "\r" || true)"
  if [ -z "$current_pid" ]; then
    stable=false
    break
  fi
  if [ -z "$first_pid" ]; then
    first_pid="$current_pid"
  elif [ "$current_pid" != "$first_pid" ]; then
    stable=false
    break
  fi
done

if [ "$stable" = true ] && [ -n "$first_pid" ]; then
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
