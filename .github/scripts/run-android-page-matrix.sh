#!/usr/bin/env bash
set -euo pipefail

# One CI host, one APK build, four sequential Android/page-size combinations.
# Never infer the page size from an image label: verify with getconf PAGESIZE.
root=".ci/android-page-matrix"
mkdir -p "$root"
# avdmanager and emulator must resolve the SAME Android user/AVD directory.
# Mixed sdkmanager and CLI versions previously created AVDs that the emulator
# could not discover, producing four 'Unknown AVD name' boot timeouts.
export ANDROID_USER_HOME="$PWD/$root/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
# setup-android-cached pins command-line tools 22.0, not cmdline-tools/latest.
sdk="$(command -v sdkmanager)"
avdm="$(command -v avdmanager)"
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

if [[ "${1:-}" == "--cell" ]]; then
  api="$2"; page_kb="$3"
  case "$api" in 36) release=16 ;; 37) release=17 ;; *) exit 2 ;; esac
  package="$(image_package "$api" "$page_kb")"
  avd="dsh_${api}_${page_kb}"
  echo "CELL_START android=$release api=$api page_kb=$page_kb image=$package"
  # The experimental 16 KB SDK CDN sometimes cuts downloads short
  # ('Premature EOF'). A successful exit code alone is insufficient: verify
  # all payloads before AVD creation. Never substitute a 4 KB system image.
  IFS=';' read -r _sys_img image_api image_tag image_abi <<< "$package"
  image_dir="$ANDROID_SDK_ROOT/system-images/$image_api/$image_tag/$image_abi"
  image_ready() {
    [[ -s "$image_dir/source.properties" && -s "$image_dir/system.img" &&
       -s "$image_dir/ramdisk.img" ]]
  }
  image_installed=false
  for attempt in 1 2 3 4; do
    echo "SDK_IMAGE_ATTEMPT api=$api page_kb=$page_kb attempt=$attempt"
    if "$sdk" --channel=3 --install "$package" && image_ready; then
      image_installed=true
      break
    fi
    echo "SDK_IMAGE_RETRY image=$package attempt=$attempt reason=download_or_incomplete" >&2
    if (( attempt < 4 )); then
      rm -rf "$image_dir"
      sleep $((attempt * 3))
    fi
  done
  if [[ "$image_installed" != true ]]; then
    echo "CELL_ERROR SDK image unavailable or incomplete after four attempts: $package" >&2
    exit 1
  fi
  printf 'no\n' | "$avdm" create avd --force -n "$avd" -d pixel_6 --package "$package"
  echo "AVD_HOME=$ANDROID_AVD_HOME"
  echo "Registered AVDs:"
  "$avdm" list avd
  "$emu" -list-avds
  if ! "$emu" -list-avds | grep -Fxq "$avd"; then
    echo "CELL_ERROR Created AVD is invisible to emulator: $avd" >&2
    ls -la "$ANDROID_AVD_HOME" || true
    exit 1
  fi
  "$emu" -avd "$avd" -no-window -gpu swiftshader_indirect \
    -no-snapshot -noaudio -no-boot-anim -memory 3072 -cores 4 \
    > "$root/emulator-$release-$page_kb-k.log" 2>&1 &
  emulator_pid=$!
  cleanup() {
    adb emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" >/dev/null 2>&1 || true
    wait "$emulator_pid" >/dev/null 2>&1 || true
    # AVDs are independent; reclaim SDK image space before the next case.
    "$avdm" delete avd -n "$avd" >/dev/null 2>&1 || true
    "$sdk" --uninstall "$package" >/dev/null 2>&1 || true
    for attempt in $(seq 1 20); do
      state="$(adb get-state 2>/dev/null || true)"
      [[ "$state" != device ]] && break
      sleep 1
    done
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
  # The 210-test Android 16/17 suites remain mandatory in their dedicated
  # CI lanes. Repeating them four more times on this one runner consumes OS
  # JobScheduler quota and contaminates the later performance samples.
  # These ten rounds still execute real UI, MCP OAuth, and WorkManager tests.
  # Retain platform crash evidence before EXIT cleanup shuts this AVD down.
  # The Android 16 / 16KB test previously only said 'Process crashed',
  # leaving no Java/native stack in the uploaded artifact.
  if ! bash .github/scripts/run-android17-ten-rounds.sh "$api" "$page_kb"; then
    echo "CELL_ERROR Android $release $page_kb KB performance instrumentation failed" >&2
    adb logcat -d -v threadtime -b main -b system -b crash \
      > "$root/logcat-$release-$page_kb-k.txt" 2>&1 || true
    adb shell dumpsys activity exit-info "$app" \
      > "$root/exit-info-$release-$page_kb-k.txt" 2>&1 || true
    adb shell dumpsys dropbox --print \
      > "$root/dropbox-$release-$page_kb-k.txt" 2>&1 || true
    grep -E -A 22 -B 4 'FATAL EXCEPTION|Fatal signal|signal 11|SIGABRT|Process .* has died|INSTRUMENTATION_RESULT' \
      "$root/logcat-$release-$page_kb-k.txt" | tail -n 130 || true
    exit 1
  fi
  adb shell am force-stop "$app" || true
  bash .github/scripts/smoke-test-android-startup.sh "$app"
  adb shell am force-stop "$app" || true
  adb install -r .ci/device-apks/app-optimized.apk
  bash .github/scripts/smoke-test-android-startup.sh "$app"
  echo "CELL_COMPLETE android=$release api=$api page_kb=$page_kb"
  exit 0
fi

# The independent matrix workflow builds all three APKs on this same runner.
# Keep the previous same-run artifact mode for explicit backward compatibility.
if [[ "${1:-}" != "--local-apks" ]]; then
  bash .github/scripts/wait-android-device-artifacts.sh
fi
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
