#!/usr/bin/env bash
set -euo pipefail

# One Android 17 emulator + one x86_64 APK build, ten instrumented rounds.
out=".ci/android17-ten-rounds"
mkdir -p "$out"
component="com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner"
classes="com.labteto.dshmobile.local.DevicePerformanceBaselineTest,com.labteto.dshmobile.local.tools.LocalMcpOAuthProtocolEndToEndAndroidTest,com.labteto.dshmobile.automation.AutomationDispatchEndToEndAndroidTest,com.labteto.dshmobile.ui.screens.local.DeviceScrollFrameAndMemoryTest"
{
  echo "git_sha=$GITHUB_SHA"
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
  printf 'ANDROID17_TEN_ROUNDS_BEGIN round=%02d\n' "$round"
  echo "host_load_before=$(cat /proc/loadavg)" >> "$out/environment.txt"
  log="$out/round-$(printf '%02d' "$round").log"
  # New instrumentation test process, but the same AVD and host every round.
  adb shell am force-stop com.sy220284.dshmobile.debug || true
  adb shell am instrument -w -r -e class "$classes" "$component" 2>&1 |
      tr -d '\r' | tee "$log"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[12]|Process crashed' "$log" ||
      ! grep -Eq '^OK \(5 tests\)' "$log"; then
    echo "::error::Android 17 performance round $round failed" >&2
    exit 1
  fi
  count="$(grep -c 'INSTRUMENTATION_STATUS: stream=ANDROID_PERF api=37 ' "$log" || true)"
  if [[ "$count" -ne 6 ]]; then
    echo "::error::Android 17 round $round emitted $count metrics; expected exactly 6" >&2
    exit 1
  fi
  grep 'INSTRUMENTATION_STATUS: stream=ANDROID_PERF api=37 ' "$log" |
      sed "s/^/ANDROID17_TEN_ROUNDS round=$round /"
  printf 'ANDROID17_TEN_ROUNDS_END round=%02d\n' "$round"
done
python3 .github/scripts/analyze-android17-ten-rounds.py "$out"
