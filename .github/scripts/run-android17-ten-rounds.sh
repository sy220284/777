#!/usr/bin/env bash
set -euo pipefail

# Android 16 or 17, same shared x86_64 APK build and ten instrumented rounds.
# Historical script name retained for existing CI callers.
api="${1:-37}"
case "$api" in
  36) platform="16" ;;
  37) platform="17" ;;
  *) echo "expected Android API 36 or 37" >&2; exit 2 ;;
esac
# API level 36/37 is the instrumentation label; release numbers are 16/17.
# These artifact paths must match the workflow upload paths exactly.
page_kb="${2:-}"
case "$page_kb" in
  "") out=".ci/android${platform}-ten-rounds" ;;
  4|16) out=".ci/android${platform}-${page_kb}k-ten-rounds" ;;
  *) echo "expected page size 4, 16 or empty (legacy output)" >&2; exit 2 ;;
esac
mkdir -p "$out"
actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
actual_page="$(adb shell getconf PAGESIZE | tr -d '\r')"
if [[ "$actual_api" != "$api" ]]; then
  echo "::error::Android API mismatch: expected $api, got $actual_api" >&2
  exit 1
fi
if [[ -n "$page_kb" && "$actual_page" != "$((page_kb * 1024))" ]]; then
  echo "::error::Page size mismatch: expected ${page_kb}KB, got $actual_page bytes" >&2
  exit 1
fi
component="com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner"
classes="com.labteto.dshmobile.local.DevicePerformanceBaselineTest,com.labteto.dshmobile.local.tools.LocalMcpOAuthProtocolEndToEndAndroidTest,com.labteto.dshmobile.automation.AutomationDispatchEndToEndAndroidTest,com.labteto.dshmobile.ui.screens.local.DeviceScrollFrameAndMemoryTest"
{
  echo "git_sha=$GITHUB_SHA"
  echo "requested_api=$api"
  echo "runner_name=$RUNNER_NAME"
  echo "runner_os=$RUNNER_OS"
  echo "runner_arch=$RUNNER_ARCH"
  echo "device_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "device_image=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "device_page_size=$(adb shell getconf PAGESIZE | tr -d '\r')"
  echo "device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "device_refresh_rate=$(adb shell dumpsys display | grep -Eo 'fps=[0-9.]+' | head -1 || true)"
  echo "host_cpu=$(grep -m1 'model name' /proc/cpuinfo || true)"
  echo "host_mem=$(free -m | grep '^Mem:' || true)"
} > "$out/environment.txt"
cat "$out/environment.txt"

for round in $(seq 1 10); do
  printf 'ANDROID%s_TEN_ROUNDS_BEGIN round=%02d\n' "$api" "$round"
  echo "host_load_before=$(cat /proc/loadavg)" >> "$out/environment.txt"
  log="$out/round-$(printf '%02d' "$round").log"
  # New instrumentation test process, but the same AVD and host every round.
  adb shell am force-stop com.sy220284.dshmobile.debug || true
  adb shell am instrument -w -r -e class "$classes" "$component" 2>&1 |
      tr -d '\r' | tee "$log"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[12]|Process crashed' "$log" ||
      ! grep -Eq '^OK \(5 tests\)' "$log"; then
    echo "::error::Android $api performance round $round failed" >&2
    exit 1
  fi
  count="$(grep -c "INSTRUMENTATION_STATUS: stream=ANDROID_PERF api=${api} " "$log" || true)"
  if [[ "$count" -ne 6 ]]; then
    echo "::error::Android $api round $round emitted $count metrics; expected exactly 6" >&2
    exit 1
  fi
  grep "INSTRUMENTATION_STATUS: stream=ANDROID_PERF api=${api} " "$log" |
      sed "s/^/ANDROID${api}_TEN_ROUNDS round=$round /"
  printf 'ANDROID%s_TEN_ROUNDS_END round=%02d\n' "$api" "$round"
done
python3 .github/scripts/analyze-android17-ten-rounds.py "$out" "$api"
