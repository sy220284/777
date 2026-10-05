install_node22() {
  [ "$PROFILE" = full ] || return 0
  local node_dir="$TOOLS_ROOT/node-v$NODE_VERSION-linux-$ARCH_KIND" current="$TOOLS_ROOT/node-current"
  if [ -x "$node_dir/bin/node" ] && [ "$($node_dir/bin/node -p 'process.versions.node')" = "$NODE_VERSION" ]; then ln -sfn "$node_dir" "$current"; return 0; fi
  local archive="node-v$NODE_VERSION-linux-$ARCH_KIND.tar.xz" sha cache
  case "$ARCH_KIND" in x64) sha="$NODE_LINUX_X64_SHA256" ;; arm64) sha="$NODE_LINUX_ARM64_SHA256" ;; esac
  cache="$TOOLS_ROOT/downloads/$archive"
  download_verified "https://nodejs.org/download/release/v$NODE_VERSION/$archive" "$cache" "$sha"
  rm -rf "$node_dir"
  mkdir -p "$TOOLS_ROOT"
  tar -xJf "$cache" -C "$TOOLS_ROOT"
  test -x "$node_dir/bin/node"
  ln -sfn "$node_dir" "$current"
  "$current/bin/corepack" enable
  ok "Node.js $NODE_VERSION 已安装"
}

install_actionlint() {
  [ "$PROFILE" = full ] || return 0
  if [ -x "$TOOLS_ROOT/bin/actionlint" ] && "$TOOLS_ROOT/bin/actionlint" -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION"; then
    ok "actionlint $ACTIONLINT_VERSION 已存在，跳过安装"
    return 0
  fi
  local asset_arch sha archive cache tmp
  case "$ARCH_KIND" in
    x64) asset_arch=amd64; sha="$ACTIONLINT_LINUX_X64_SHA256" ;;
    arm64) asset_arch=arm64; sha="$ACTIONLINT_LINUX_ARM64_SHA256" ;;
  esac
  archive="actionlint_${ACTIONLINT_VERSION}_linux_${asset_arch}.tar.gz"
  cache="$TOOLS_ROOT/downloads/$archive"
  download_verified "https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/$archive" "$cache" "$sha"
  tmp="$(mktemp -d)"
  tar -xzf "$cache" -C "$tmp"
  mkdir -p "$TOOLS_ROOT/bin"
  install -m 0755 "$tmp/actionlint" "$TOOLS_ROOT/bin/actionlint"
  rm -rf "$tmp"
  ok "actionlint $ACTIONLINT_VERSION 已安装"
}

write_env_file() {
  local java_home="$1"
  mkdir -p "$(dirname "$ENV_FILE")"
  {
    printf '# 由 777 tools/dev/setup-toolchain.sh 生成。\n'
    printf 'export JAVA_HOME=%q\n' "$java_home"
    printf 'export ANDROID_SDK_ROOT=%q\n' "$SDK_ROOT"
    printf 'export ANDROID_HOME=%q\n' "$SDK_ROOT"
    if [ "$PROFILE" = full ]; then
      printf 'export PATH=%q:%q:"$ANDROID_SDK_ROOT/platform-tools":"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin":"$ANDROID_SDK_ROOT/emulator":"$JAVA_HOME/bin":"$PATH"\n' "$TOOLS_ROOT/bin" "$TOOLS_ROOT/node-current/bin"
    else
      printf 'export PATH=%q:"$ANDROID_SDK_ROOT/platform-tools":"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin":"$ANDROID_SDK_ROOT/emulator":"$JAVA_HOME/bin":"$PATH"\n' "$TOOLS_ROOT/bin"
    fi
  } > "$ENV_FILE"
  ok "环境文件已写入：$ENV_FILE"
}

configure_shell() {
  [ "$CONFIGURE_SHELL" = true ] || return 0
  local rc begin='# >>> 777 dev toolchain >>>'
  case "${SHELL##*/}" in zsh) rc="$HOME/.zshrc" ;; bash) rc="$HOME/.bashrc" ;; *) rc="$HOME/.profile" ;; esac
  touch "$rc"
  if grep -Fq "$begin" "$rc"; then ok "shell 已存在 777 工具链入口：$rc"; return 0; fi
  {
    printf '\n%s\n' "$begin"
    printf '[ -f %q ] && . %q\n' "$ENV_FILE" "$ENV_FILE"
    printf '%s\n' '# <<< 777 dev toolchain <<<'
  } >> "$rc"
  ok "已接入 shell：$rc（重新打开终端后生效）"
}
