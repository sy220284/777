#!/usr/bin/env bash
set -euo pipefail

package_name="${1:?usage: smoke-test-android-startup.sh <package> [startup-wait-seconds] [stable-seconds]}"
wait_seconds="${2:-15}"
stable_seconds="${3:-8}"

if ! [[ "$wait_seconds" =~ ^[1-9][0-9]*$ && "$stable_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::Startup and stability durations must be positive integers" >&2
  exit 2
fi

# Clear stale test-suite crashes so every failure report describes this launch.
adb logcat -c
launched=true
if ! adb shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1; then
  launched=false
fi

first_pid=""
if [ "$launched" = true ]; then
  # Cold starts on software-rendered CI emulators can need several seconds.
  for ((elapsed = 0; elapsed < wait_seconds; elapsed++)); do
    current_pid="$(adb shell pidof "$package_name" 2>/dev/null | tr -d '\r' || true)"
    if [ -n "$current_pid" ]; then
      first_pid="$current_pid"
      break
    fi
    sleep 1
  done
fi

stable=false
if [ -n "$first_pid" ]; then
  stable=true
  for ((elapsed = 0; elapsed < stable_seconds; elapsed++)); do
    sleep 1
    current_pid="$(adb shell pidof "$package_name" 2>/dev/null | tr -d '\r' || true)"
    if [ "$current_pid" != "$first_pid" ]; then
      stable=false
      echo "::error::App process vanished or restarted (before=$first_pid, after=$current_pid)" >&2
      break
    fi
  done
fi

if [ "$stable" = true ]; then
  echo "Startup smoke passed: $package_name survived ${stable_seconds}s with pid $first_pid"
  exit 0
fi

echo "::error::Startup smoke failed: $package_name (launched=$launched; initial_pid=${first_pid:-absent})" >&2
echo "=== package and launcher diagnostics ==="
adb shell pm path "$package_name" || true
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$package_name" || true
echo "=== startup crash buffer: $package_name ==="
adb logcat -d -b crash -v threadtime || true
echo "=== startup filtered logcat: $package_name ==="
adb logcat -d -v threadtime \
  | grep -E "AndroidRuntime|FATAL EXCEPTION|$package_name|DshApplication|MainActivity|ActivityTaskManager|am_proc_died|Process .* has died|SecurityException|IllegalStateException" \
  || true
exit 1
