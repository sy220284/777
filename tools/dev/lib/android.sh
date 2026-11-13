android_packages_ready() {
  [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ] || return 1
  [ -x "$SDK_ROOT/platform-tools/adb" ] || return 1
  [ -d "$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS" ] || return 1
  if [ ! -d "$SDK_ROOT/platforms/android-$ANDROID_COMPILE_API" ] && [ ! -d "$SDK_ROOT/platforms/android-${ANDROID_COMPILE_API}.0" ]; then
    return 1
  fi
  if [ "$PROFILE" = full ]; then
    [ -x "$SDK_ROOT/emulator/emulator" ] || return 1
    find "$SDK_ROOT/system-images" -type d -path '*android-36*google_apis*x86_64' -print -quit 2>/dev/null | grep -q . || return 1
    find "$SDK_ROOT/system-images" -type d -path '*android-37.0*google_apis_ps16k*x86_64' -print -quit 2>/dev/null | grep -q . || return 1
  fi
}

create_avds() {
  [ "$PROFILE" = full ] || return 0
  [ "${CREATE_AVDS:-false}" = true ] || return 0

  local avdmanager="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" name image status
  while IFS='|' read -r name image; do
    if "$avdmanager" list avd 2>/dev/null | grep -Fq "Name: $name"; then
      ok "AVD 已存在：$name"
      continue
    fi
    say "使用产物内 system image 创建 AVD：$name"
    set +o pipefail
    echo no | "$avdmanager" create avd --force -n "$name" --package "$image"
    status="${PIPESTATUS[1]}"
    set -o pipefail
    [ "$status" -eq 0 ] || return "$status"
  done <<EOF_AVD
777-android16|$ANDROID_16_SYSTEM_IMAGE
777-android17|$ANDROID_17_SYSTEM_IMAGE
EOF_AVD
}
