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

resolve_android_platform_package() {
  local sdkmanager="$1" candidate canonical available
  canonical="platforms;android-$ANDROID_COMPILE_API"
  available="$($sdkmanager --sdk_root="$SDK_ROOT" --list 2>/dev/null || true)"
  for candidate in "$ANDROID_PLATFORM_PACKAGE" "$canonical"; do
    if printf '%s\n' "$available" | awk -F'|' '{ gsub(/^[ \t]+|[ \t]+$/, "", $1); print $1 }' | grep -Fxq "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "[777-toolchain] Android SDK 中未发现 API $ANDROID_COMPILE_API platform（尝试：$ANDROID_PLATFORM_PACKAGE / $canonical）" >&2
  return 1
}

install_android_packages() {
  local sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" platform_package
  platform_package="$(resolve_android_platform_package "$sdkmanager")"
  accept_android_licenses
  local packages=(platform-tools "$platform_package" "build-tools;$ANDROID_BUILD_TOOLS")
  if [ "$PROFILE" = full ]; then packages+=(emulator "$ANDROID_16_SYSTEM_IMAGE" "$ANDROID_17_SYSTEM_IMAGE"); fi
  say "安装 / 校验 Android SDK packages：${packages[*]}"
  "$sdkmanager" --sdk_root="$SDK_ROOT" "${packages[@]}"
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
