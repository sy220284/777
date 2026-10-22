#!/usr/bin/env bash
set -euo pipefail

# One CI host, one APK build, four sequential Android/page-size combinations.
# Never infer the page size from an image label: verify with getconf PAGESIZE.
root=".ci/android-page-matrix"
mkdir -p "$root"
sdk="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
avdm="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
emu="$ANDROID_SDK_ROOT/emulator/emulator"
component="com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner"
app="com.sy220284.dshmobile.debug"

image_package() {
  case "$1:$2" in
    36:4)  echo 'system-images;android-36;google_apis;x86_64' ;;
    36:16) echo 'system-images;android-36;google_apis_ps16k;x86_64' ;;
    37:4)  echo 'system-images;android-37.0;google_apis;x86_64' ;;
    37:16) echo 'system-images;android-37.0;google_apis_ps16k;x86_64' ;;
    *) echo "unsupported cell $1:$2" >&2; return 2 ;;
  esac
}

if [[ "$1" == "--cell" ]]; then
  api="$2"; page_kb="$3"
  case "$api" in 36) release=16 ;; 37) release=17 ;; *) exit 2 ;; esac
  package="$(image_package "$api" "$page_kb")"
  avd="dsh_$api_$page_kb"
  echo "CELL_START android=$release api=$api page_kb=$page_kb image=$package"
  "$sdk" --channel=3 --install "$package"
  printf 'no\n' | "$avdm" create avd --force -n "$avd" -d pixel_6 --package "$package"
  "$emu" -avd "$avd" -no-window -gpu swiftshader_indirect \
    -no-snapshot -noaudio -no-boot-anim -memory 3072 -cores 4 \
    > "$root/emulator-$release-$page_kb-k.log" 2>&1 &
  emulator_pid=$!
  cleanup() {
    adb emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" >/dev/null 2>&1 || true
    wait "$emulator_pid" >/dev/null 2>&1 || true
  }
  trap cleanup EXIT
  booted=false
  for attempt in $(seq 1 180); do
    state="$(adb get-state 2>/dev/null || true)"
    boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$state" == device && "$boot" == 1 ]]; then
      booted=true; break
    fi
    if (( attempt % 30 == 0 )); then
      echo "BOOT_PROBE attempt=$attempt adb=$state boot=$boot"
    fi
    sleep 2
  done
  if [[ "$booted" != true ]]; then
    echo "CELL_ERROR Android $release $page_kb KB boot timed out" >&2
    exit 1
  fi
  actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  actual_page="$(adb shell getconf PAGESIZE | tr -d '\r')"
  if [[ "$actual_api" != "$api" || "$actual_page" != "$((page_kb*1024))" ]]; then
    echo "CELL_ERROR expected_api=$api actual_api=$actual_api expected_page=$((page_kb*1024)) actual_page=$actual_page" >&2
    exit 1
  fi
  echo "CELL_VERIFIED api=$actual_api page_bytes=$actual_page image=$package"
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 0
  done
  bash .github/scripts/verify-android-test-viewport.sh 412 400
  adb install -r .ci/device-apks/app-debug.apk
  adb install -r .ci/device-apks/app-debug-androidTest.apk
  bash .github/scripts/run-android-instrumentation.sh "$component"
  bash .github/scripts/run-android17-ten-rounds.sh "$api" "$page_kb"
  adb shell am force-stop "$app" || true
  bash .github/scripts/smoke-test-android-startup.sh "$app"
  adb shell am force-stop "$app" || true
  adb install -r .ci/device-apks/app-optimized.apk
  bash .github/scripts/smoke-test-android-startup.sh "$app"
  echo "CELL_COMPLETE android=$release api=$api page_kb=$page_kb"
  exit 0
fi

bash .github/scripts/wait-android-device-artifacts.sh
for apk in app-debug.apk app-debug-androidTest.apk app-optimized.apk; do
  test -s ".ci/device-apks/$apk" || { echo "::error::Missing $apk" >&2; exit 1; }
done
test -e /dev/kvm || { echo "::error::KVM is unavailable" >&2; exit 1; }
sudo chmod 666 /dev/kvm
failures=0
printf 'android_release\tapi\tpage_kb\tstatus\n' > "$root/cell-status.tsv"
for entry in 36:4 36:16 37:4 37:16; do
  IFS=: read -r api page_kb <<< "$entry"
  release="$((api-20))"
  logfile="$root/cell-$release-$page_kb-k.log"
  echo "::group::Android $release / $page_kb KB"
  if bash "$0" --cell "$api" "$page_kb" > "$logfile" 2>&1; then
    printf '%s\t%s\t%s\tpassed\n' "$release" "$api" "$page_kb" >> "$root/cell-status.tsv"
  else
    printf '%s\t%s\t%s\tfailed\n' "$release" "$api" "$page_kb" >> "$root/cell-status.tsv"
    echo "::error::Android $release $page_kb KB failed: $logfile" >&2
    failures=$((failures+1))
  fi
  tail -n 30 "$logfile"
  echo "::endgroup::"
done
if [[ "$failures" -ne 0 ]]; then
  echo "::error::$failures of four test environments could not be verified" >&2
  exit 1
fi
python3 .github/scripts/compare-android-page-matrix.py "$root" ".ci"
