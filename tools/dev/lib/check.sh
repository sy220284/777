check_command() {
  local command_name="$1"
  if command -v "$command_name" >/dev/null 2>&1; then
    ok "$command_name -> $(command -v "$command_name")"
    return 0
  fi
  fail "$command_name"
  return 1
}

check_android_package() {
  local label="$1" path="$2"
  if [ -e "$path" ]; then
    ok "$label"
    return 0
  fi
  fail "$label ($path)"
  return 1
}

check_environment() {
  local failures=0 command_name java_home gradle_version

  say "检查 profile=$PROFILE"
  say "TOOLS_ROOT=$TOOLS_ROOT"
  say "SDK_ROOT=$SDK_ROOT"
  say "GRADLE_HOME=$GRADLE_HOME"
  say "GRADLE_USER_HOME=$GRADLE_USER_HOME"

  while IFS= read -r command_name; do
    check_command "$command_name" || failures=$((failures + 1))
  done < <(required_host_commands)

  if [ "${BASH_VERSINFO[0]}" -ge 4 ]; then
    ok "Bash ${BASH_VERSION}（需要 >= 4）"
  else
    fail "Bash ${BASH_VERSION}，需要 >= 4"
    failures=$((failures + 1))
  fi

  java_home="$(find_compatible_java_home || true)"
  if [ -n "$java_home" ] && [ "$(java_major "$java_home")" -ge "$BUILD_JDK_MIN_MAJOR" ] && [ -x "$java_home/bin/javac" ]; then
    ok "JDK $(java_major "$java_home")（要求 >= $BUILD_JDK_MIN_MAJOR，含 javac）-> $java_home"
  else
    fail "JDK >= $BUILD_JDK_MIN_MAJOR（必须包含 javac，单独 JRE 不满足）"
    failures=$((failures + 1))
  fi

  if [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    ok "Android command-line tools"
  else
    fail "Android command-line tools"
    failures=$((failures + 1))
  fi
  check_android_package "Android platform-tools" "$SDK_ROOT/platform-tools/adb" || failures=$((failures + 1))
  if [ -d "$SDK_ROOT/platforms/android-$ANDROID_COMPILE_API" ] || [ -d "$SDK_ROOT/platforms/android-${ANDROID_COMPILE_API}.0" ]; then
    ok "Android platform API $ANDROID_COMPILE_API"
  else
    fail "Android platform API $ANDROID_COMPILE_API"
    failures=$((failures + 1))
  fi
  check_android_package "Android build-tools $ANDROID_BUILD_TOOLS" "$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS" || failures=$((failures + 1))

  if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    gradle_version="$("$GRADLE_HOME/bin/gradle" --version 2>/dev/null | awk '/^Gradle / {print $2; exit}')"
    if [ "$gradle_version" = "$GRADLE_VERSION" ]; then
      ok "Gradle $GRADLE_VERSION -> $GRADLE_HOME"
    else
      fail "Gradle $GRADLE_VERSION（检测到：${gradle_version:-unknown}）"
      failures=$((failures + 1))
    fi
  else
    fail "Gradle $GRADLE_VERSION"
    failures=$((failures + 1))
  fi

  check_android_package "Gradle 离线依赖缓存" "$GRADLE_USER_HOME/caches/modules-2" || failures=$((failures + 1))
  check_android_package "Gradle Wrapper 分发缓存" "$GRADLE_USER_HOME/wrapper/dists" || failures=$((failures + 1))

  if [ -n "${DEV777_REPO_ROOT:-}" ] && [ -d "$DEV777_REPO_ROOT/tools/runtime" ]; then
    check_android_package "777 Runtime 构建缓存" "$DEV777_REPO_ROOT/.gradle/runtime-cache" || failures=$((failures + 1))
  fi

  if [ "$PROFILE" = full ]; then
    local node_bin="$TOOLS_ROOT/node-current/bin/node" node_path="" installed_node_major=""
    if [ -x "$node_bin" ]; then
      node_path="$node_bin"
    elif command -v node >/dev/null 2>&1; then
      node_path="$(command -v node)"
    fi
    if [ -n "$node_path" ]; then
      installed_node_major="$(node_major "$node_path" || true)"
    fi
    if [[ "$installed_node_major" =~ ^[0-9]+$ ]] && [ "$installed_node_major" -ge "$NODE_MIN_MAJOR" ]; then
      ok "Node.js $("$node_path" -p 'process.versions.node')（要求 >= $NODE_MIN_MAJOR）-> $node_path"
    else
      fail "Node.js >= $NODE_MIN_MAJOR"
      failures=$((failures + 1))
    fi

    if [ -x "$TOOLS_ROOT/bin/actionlint" ] && "$TOOLS_ROOT/bin/actionlint" -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION"; then
      ok "actionlint $ACTIONLINT_VERSION"
    else
      fail "actionlint $ACTIONLINT_VERSION"
      failures=$((failures + 1))
    fi

    if [ -x "$SDK_ROOT/emulator/emulator" ] && \
      LD_LIBRARY_PATH="$SDK_ROOT/emulator/host-libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$SDK_ROOT/emulator/emulator" -version >/dev/null 2>&1; then
      ok "Android emulator 可执行"
    else
      fail "Android emulator 可执行性（含宿主动态库）"
      failures=$((failures + 1))
    fi
    if find "$SDK_ROOT/system-images" -type d -path '*android-36*google_apis*x86_64' -print -quit 2>/dev/null | grep -q .; then
      ok "Android 16 x86_64 system image"
    else
      fail "Android 16 x86_64 system image"
      failures=$((failures + 1))
    fi
    if find "$SDK_ROOT/system-images" -type d -path '*android-37.0*google_apis_ps16k*x86_64' -print -quit 2>/dev/null | grep -q .; then
      ok "Android 17 16 KiB x86_64 system image"
    else
      fail "Android 17 16 KiB x86_64 system image"
      failures=$((failures + 1))
    fi

    if [ -e /dev/kvm ]; then
      ok "KVM -> /dev/kvm"
    else
      manual "KVM：未检测到 /dev/kvm；这是宿主虚拟化能力，构建产物无法代替宿主开启。"
    fi
  fi

  if [ "$failures" -ne 0 ]; then
    fail "共 $failures 项必备能力未满足"
    say "本地工具链只允许从 GitHub Actions 组件 Artifact 补齐。"
    say "当前仍缺少："
    while IFS= read -r component; do
      [ -n "$component" ] || continue
      say "  $(artifact_name_for_component "$component")"
    done < <(missing_components "$PROFILE")
    return 1
  fi

  ok "profile=$PROFILE 环境检查通过"
}
