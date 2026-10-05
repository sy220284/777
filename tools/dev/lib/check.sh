check_command() {
  local command_name="$1"
  if command -v "$command_name" >/dev/null 2>&1; then ok "$command_name -> $(command -v "$command_name")"; return 0; fi
  fail "$command_name"
  return 1
}

check_android_package() {
  local label="$1" path="$2"
  if [ -e "$path" ]; then ok "$label"; return 0; fi
  fail "$label ($path)"
  return 1
}

check_environment() {
  local failures=0 command_name java_home
  say "检查 profile=$PROFILE"
  say "TOOLS_ROOT=$TOOLS_ROOT"
  say "SDK_ROOT=$SDK_ROOT"
  for command_name in bash curl gpg python3 dpkg-deb readelf sha256sum unzip tar git find awk sed grep head tr; do
    check_command "$command_name" || failures=$((failures + 1))
  done
  if [ "${BASH_VERSINFO[0]}" -ge 4 ]; then ok "Bash ${BASH_VERSION}（需要 >= 4）"; else fail "Bash ${BASH_VERSION}，需要 >= 4"; failures=$((failures + 1)); fi

  java_home="$(find_java17_home || true)"
  if [ -n "$java_home" ] && [ "$(java_major "$java_home")" = "$JDK_MAJOR" ]; then ok "JDK $JDK_MAJOR -> $java_home"; else fail "JDK $JDK_MAJOR"; failures=$((failures + 1)); fi

  if [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then ok "sdkmanager"; else fail "Android command-line tools"; failures=$((failures + 1)); fi
  check_android_package "Android platform-tools" "$SDK_ROOT/platform-tools/adb" || failures=$((failures + 1))
  if [ -d "$SDK_ROOT/platforms/android-$ANDROID_COMPILE_API" ] || [ -d "$SDK_ROOT/platforms/android-${ANDROID_COMPILE_API}.0" ]; then ok "Android platform API $ANDROID_COMPILE_API"; else fail "Android platform API $ANDROID_COMPILE_API"; failures=$((failures + 1)); fi
  check_android_package "Android build-tools $ANDROID_BUILD_TOOLS" "$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS" || failures=$((failures + 1))

  if [ "$PROFILE" = full ]; then
    local node_bin="$TOOLS_ROOT/node-current/bin/node"
    if [ -x "$node_bin" ] && [ "$($node_bin -p 'process.versions.node.split(`.`)[0]')" = 22 ]; then
      ok "Node.js $($node_bin -p 'process.versions.node')"
    elif command -v node >/dev/null 2>&1 && [ "$(node -p 'process.versions.node.split(`.`)[0]')" = 22 ]; then
      ok "Node.js $(node -p 'process.versions.node')"
    else fail "Node.js 22"; failures=$((failures + 1)); fi
    if [ -x "$TOOLS_ROOT/bin/actionlint" ]; then ok "actionlint $($TOOLS_ROOT/bin/actionlint -version 2>/dev/null | head -n 1)"; elif command -v actionlint >/dev/null 2>&1; then ok "actionlint $(actionlint -version 2>/dev/null | head -n 1)"; else fail "actionlint $ACTIONLINT_VERSION"; failures=$((failures + 1)); fi
    check_android_package "Android emulator" "$SDK_ROOT/emulator/emulator" || failures=$((failures + 1))
    if find "$SDK_ROOT/system-images" -type d -path '*android-36*google_apis*x86_64' -print -quit 2>/dev/null | grep -q .; then ok "Android 16 x86_64 system image"; else fail "Android 16 x86_64 system image"; failures=$((failures + 1)); fi
    if find "$SDK_ROOT/system-images" -type d -path '*android-37.0*google_apis_ps16k*x86_64' -print -quit 2>/dev/null | grep -q .; then ok "Android 17 16 KiB x86_64 system image"; else fail "Android 17 16 KiB x86_64 system image"; failures=$((failures + 1)); fi
    if [ -e /dev/kvm ]; then
      ok "KVM -> /dev/kvm"
    else
      manual "KVM：未检测到 /dev/kvm；需要在宿主 BIOS / Hyper-V / WSL 虚拟化层开启，脚本无法代替宿主完成。"
    fi
  fi

  if [ "$failures" -ne 0 ]; then
    fail "共 $failures 项必备能力未满足"
    if [ "${NON_INTERACTIVE:-false}" = true ]; then
      say "这些缺失项可由 AI 工具链自动配置："
      say "  bash tools/dev/ai-toolchain.sh bootstrap --profile $PROFILE"
    else
      say "这些缺失项可自动配置。推荐执行："
      say "  bash tools/dev/install.sh $PROFILE"
      say "或使用底层命令："
      say "  bash tools/dev/setup-toolchain.sh --auto --profile $PROFILE --accept-android-licenses --configure-shell"
    fi
    return 1
  fi
  ok "profile=$PROFILE 环境检查通过"
}
