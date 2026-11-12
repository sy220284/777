#!/usr/bin/env bash
set -euo pipefail

# Android Emulator CDN occasionally returns a truncated archive while SDK
# installation reports "Premature EOF". Verify on-disk artifacts explicitly
# and retry a clean package installation; never start an incomplete AVD.
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
emulator_dir="$sdk_root/emulator"
image_dir="$sdk_root/system-images/android-36/google_apis/x86_64"
image_package='system-images;android-36;google_apis;x86_64'

ready() {
  test -x "$emulator_dir/emulator" &&
  test -s "$emulator_dir/source.properties" &&
  test -s "$image_dir/source.properties" &&
  test -s "$image_dir/system.img" &&
  test -s "$image_dir/ramdisk.img"
}

if [[ "${1:-}" == "--self-test" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  sdk_root="$tmp"
  emulator_dir="$sdk_root/emulator"
  image_dir="$sdk_root/system-images/android-36/google_apis/x86_64"
  if ready; then echo "Incomplete emulator unexpectedly passed" >&2; exit 1; fi
  mkdir -p "$emulator_dir" "$image_dir"
  touch "$emulator_dir/emulator"
  chmod +x "$emulator_dir/emulator"
  printf 'Pkg.Revision=1\n' > "$emulator_dir/source.properties"
  printf 'Pkg.Revision=1\n' > "$image_dir/source.properties"
  printf 'system\n' > "$image_dir/system.img"
  if ready; then echo "Incomplete system image unexpectedly passed" >&2; exit 1; fi
  printf 'ramdisk\n' > "$image_dir/ramdisk.img"
  ready
  printf 'corrupted image removed\n' > "$emulator_dir/emulator"
  chmod -x "$emulator_dir/emulator"
  if ready; then echo "Non-executable emulator unexpectedly passed" >&2; exit 1; fi
  echo "Android emulator SDK package integrity self-test passed"
  exit 0
fi

sdkmanager="$(command -v sdkmanager)"
for attempt in 1 2 3 4; do
  echo "PREPARE_ANDROID16_EMULATOR attempt=$attempt"
  if "$sdkmanager" --channel=0 --install emulator "$image_package" && ready; then
    "$emulator_dir/emulator" -version | head -n 2
    echo "Android 16 emulator and x86_64 Google APIs image verified"
    exit 0
  fi
  echo "Android 16 emulator incomplete after attempt $attempt; cleaning failed package" >&2
  if (( attempt == 4 )); then break; fi
  rm -rf "$emulator_dir" "$image_dir"
  sleep $((attempt * 3))
done
echo "::error::Android 16 emulator or system image unavailable after 4 verified attempts" >&2
exit 1
