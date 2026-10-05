install_android_commandline_tools() {
  local sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  if [ -x "$sdkmanager" ]; then return 0; fi
  if [ "$ARCH_KIND" != x64 ]; then
    echo "[777-toolchain] Google Linux command-line tools 自动包当前只按 x86_64 验证；ARM64 请先提供可工作的 sdkmanager。" >&2
    return 1
  fi
  local archive="$TOOLS_ROOT/downloads/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_REV}_latest.zip"
  local url="https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_REV}_latest.zip"
  download_verified "$url" "$archive" "$ANDROID_CMDLINE_TOOLS_LINUX_X64_SHA256"
  local tmp
  tmp="$(mktemp -d)"
  unzip -q "$archive" -d "$tmp"
  test -x "$tmp/cmdline-tools/bin/sdkmanager"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
  ok "Android command-line tools $ANDROID_CMDLINE_TOOLS_REV 已安装"
}

accept_android_licenses() {
  local sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  if [ "$ACCEPT_ANDROID_LICENSES" = true ]; then
    say "按显式参数接受 Android SDK licenses"
    set +o pipefail
    yes | "$sdkmanager" --sdk_root="$SDK_ROOT" --licenses >/dev/null
    local status="${PIPESTATUS[1]}"
    set -o pipefail
    return "$status"
  fi
  if [ -t 0 ]; then
    say "需要确认 Android SDK licenses（自动接受请显式加 --accept-android-licenses）"
    "$sdkmanager" --sdk_root="$SDK_ROOT" --licenses
    return 0
  fi
  echo "[777-toolchain] 非交互自动配置需要显式传入 --accept-android-licenses。" >&2
  return 1
}

android_packages_ready() {
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

install_android_packages() {
  if android_packages_ready; then
    ok "Android SDK packages 已满足，跳过 sdkmanager 安装"
    return 0
  fi
  local sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  local canonical="platforms;android-$ANDROID_COMPILE_API"
  accept_android_licenses

  say "安装 / 校验 Android SDK 基础 packages"
  "$sdkmanager" --sdk_root="$SDK_ROOT" platform-tools "build-tools;$ANDROID_BUILD_TOOLS"

  say "安装 Android platform：$ANDROID_PLATFORM_PACKAGE"
  if ! "$sdkmanager" --sdk_root="$SDK_ROOT" "$ANDROID_PLATFORM_PACKAGE"; then
    if [ "$canonical" = "$ANDROID_PLATFORM_PACKAGE" ]; then
      return 1
    fi
    warn "CI 固定 platform 包名安装失败，尝试 sdkmanager 标准包名：$canonical"
    "$sdkmanager" --sdk_root="$SDK_ROOT" "$canonical"
  fi

  if [ "$PROFILE" = full ]; then
    say "安装 Android Emulator 与 Android 16 / 17 system images"
    "$sdkmanager" --sdk_root="$SDK_ROOT" emulator "$ANDROID_16_SYSTEM_IMAGE" "$ANDROID_17_SYSTEM_IMAGE"
  fi
}

create_avds() {
  [ "$PROFILE" = full ] || return 0
  [ "$CREATE_AVDS" = true ] || return 0
  local avdmanager="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" name image status
  while IFS='|' read -r name image; do
    if "$avdmanager" list avd 2>/dev/null | grep -Fq "Name: $name"; then ok "AVD 已存在：$name"; continue; fi
    say "创建 AVD：$name ($image)"
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
