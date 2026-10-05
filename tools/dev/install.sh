#!/usr/bin/env bash
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SELF_DIR/tools/dev/setup-toolchain.sh" ]; then
  DEV_DIR="$SELF_DIR/tools/dev"
  PACKAGE_ROOT="$SELF_DIR"
elif [ -f "$SELF_DIR/setup-toolchain.sh" ]; then
  DEV_DIR="$SELF_DIR"
  PACKAGE_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
else
  echo "[777-install] 找不到 tools/dev/setup-toolchain.sh；请确认工具链包完整。" >&2
  exit 2
fi

SETUP="$DEV_DIR/setup-toolchain.sh"
ENV_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env"

usage() {
  cat <<'USAGE'
777 开发环境安装器

用法：
  ./install.sh
  ./install.sh build
  ./install.sh full
  ./install.sh check [build|full]

快捷命令：
  build       自动配置日常 APK 构建环境
  full        自动配置完整 CI 验证环境，并创建 Android 16/17 AVD
  check       只检查，不修改机器

无参数时进入交互向导。
AI / Agent 请使用 tools/dev/ai-toolchain.sh。
USAGE
}

verify_package() {
  if [ -f "$PACKAGE_ROOT/SHA256SUMS" ]; then
    echo "[777-install] 校验工具链包完整性..."
    (
      cd "$PACKAGE_ROOT"
      sha256sum -c SHA256SUMS
    )
  fi
}

ask_yes_no() {
  local prompt="$1" default_yes="${2:-true}" answer
  if [ "$default_yes" = true ]; then
    read -r -p "$prompt [Y/n] " answer || true
    case "${answer:-Y}" in y|Y|yes|YES|Yes) return 0 ;; *) return 1 ;; esac
  else
    read -r -p "$prompt [y/N] " answer || true
    case "${answer:-N}" in y|Y|yes|YES|Yes) return 0 ;; *) return 1 ;; esac
  fi
}

show_success() {
  local profile="$1"
  cat <<EOF

✅ 777 开发环境配置完成（$profile）。

当前终端立即启用：
  source "$ENV_FILE"

验证构建：
  ./gradlew :app:assembleDebug

以后检查环境：
  ./install.sh check $profile

AI / Agent：
  bash tools/dev/ai-toolchain.sh status
EOF
}

run_install() {
  local profile="$1" configure_shell="${2:-true}"
  local args=(--auto --profile "$profile")
  if [ "$profile" = full ]; then
    args+=(--create-avds)
  fi
  if [ "$configure_shell" = true ]; then
    args+=(--configure-shell)
  fi

  echo
  echo "777 开发环境配置"
  echo "----------------"
  if [ "$profile" = build ]; then
    echo "档位：日常开发 / APK 构建"
  else
    echo "档位：完整 CI 验证环境"
  fi
  echo
  echo "Android SDK license 将由 sdkmanager 在终端中显示并由你确认。"
  echo

  bash "$SETUP" "${args[@]}"
  show_success "$profile"
}

verify_package

command="${1:-}"
case "$command" in
  -h|--help|help)
    usage
    exit 0
    ;;
  build)
    run_install build true
    exit 0
    ;;
  full)
    run_install full true
    exit 0
    ;;
  check)
    profile="${2:-build}"
    if [ "$profile" != build ] && [ "$profile" != full ]; then
      echo "[777-install] check 只支持 build 或 full。" >&2
      exit 2
    fi
    exec bash "$SETUP" --check --profile "$profile"
    ;;
  "")
    ;;
  *)
    echo "[777-install] 未知命令：$command" >&2
    usage >&2
    exit 2
    ;;
esac

if [ ! -t 0 ]; then
  echo "[777-install] 无参数交互模式需要终端。非交互环境请显式使用 build/full，AI 请使用 ai-toolchain.sh。" >&2
  exit 2
fi

cat <<'MENU'
777 开发环境配置

请选择：
  1. 日常开发 / APK 构建（推荐）
  2. 完整 CI 验证环境
  3. 只检查当前环境
MENU

read -r -p "选择 [1]: " choice
choice="${choice:-1}"

case "$choice" in
  1) profile=build ;;
  2) profile=full ;;
  3)
    read -r -p "检查档位 build/full [build]: " profile
    profile="${profile:-build}"
    exec bash "$SETUP" --check --profile "$profile"
    ;;
  *)
    echo "[777-install] 无效选择：$choice" >&2
    exit 2
    ;;
esac

if ! ask_yes_no "是否自动安装缺失工具？" true; then
  exec bash "$SETUP" --check --profile "$profile"
fi

configure_shell=false
if ask_yes_no "是否让新终端自动加载 777 开发环境？" true; then
  configure_shell=true
fi

run_install "$profile" "$configure_shell"
