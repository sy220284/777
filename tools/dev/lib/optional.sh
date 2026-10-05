write_env_file() {
  local java_home="$1"
  mkdir -p "$(dirname "$ENV_FILE")"
  {
    printf '# 由 777 构建产物安装器生成；本地工具链禁止联网补齐依赖。\n'
    printf 'export JAVA_HOME=%q\n' "$java_home"
    printf 'export ANDROID_SDK_ROOT=%q\n' "$SDK_ROOT"
    printf 'export ANDROID_HOME=%q\n' "$SDK_ROOT"
    printf 'export GRADLE_HOME=%q\n' "$GRADLE_HOME"
    printf 'export GRADLE_USER_HOME=%q\n' "$GRADLE_USER_HOME"
    printf 'export DEV777_TOOLS_ROOT=%q\n' "$TOOLS_ROOT"
    printf 'export DEV777_ANDROID_SDK_ROOT=%q\n' "$SDK_ROOT"
    printf 'export DEV777_GRADLE_HOME=%q\n' "$GRADLE_HOME"
    printf 'export DEV777_GRADLE_USER_HOME=%q\n' "$GRADLE_USER_HOME"
    printf 'export DEV777_TOOLCHAIN_OFFLINE=1\n'
    if [ "$PROFILE" = full ]; then
      printf 'export DEV777_EMULATOR_HOST_LIB_DIR="%s/emulator/host-libs"\n' "$SDK_ROOT"
      printf 'export LD_LIBRARY_PATH="$DEV777_EMULATOR_HOST_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\n'
      printf 'export PATH=%q:%q/bin:"$ANDROID_SDK_ROOT/platform-tools":"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin":"$ANDROID_SDK_ROOT/emulator":"$GRADLE_HOME/bin":"$JAVA_HOME/bin":"$PATH"\n' "$TOOLS_ROOT/bin" "$TOOLS_ROOT/node-current"
    else
      printf 'export PATH=%q:"$ANDROID_SDK_ROOT/platform-tools":"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin":"$GRADLE_HOME/bin":"$JAVA_HOME/bin":"$PATH"\n' "$TOOLS_ROOT/bin"
    fi
  } > "$ENV_FILE"
  ok "环境文件已写入：$ENV_FILE"
}

configure_shell() {
  [ "${CONFIGURE_SHELL:-false}" = true ] || return 0
  local rc begin='# >>> 777 dev toolchain >>>'
  case "${SHELL##*/}" in
    zsh) rc="$HOME/.zshrc" ;;
    bash) rc="$HOME/.bashrc" ;;
    *) rc="$HOME/.profile" ;;
  esac
  touch "$rc"
  if grep -Fq "$begin" "$rc"; then
    ok "shell 已存在 777 工具链入口：$rc"
    return 0
  fi
  {
    printf '\n%s\n' "$begin"
    printf '[ -f %q ] && . %q\n' "$ENV_FILE" "$ENV_FILE"
    printf '%s\n' '# <<< 777 dev toolchain <<<'
  } >> "$rc"
  ok "已接入 shell：$rc（重新打开终端后生效）"
}
