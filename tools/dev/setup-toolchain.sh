#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSIONS_FILE="$SCRIPT_DIR/toolchain-versions.env"
if [ ! -f "$VERSIONS_FILE" ]; then
  echo "[777-toolchain] 缺少版本清单：$VERSIONS_FILE" >&2
  exit 2
fi
# shellcheck disable=SC1090
source "$VERSIONS_FILE"
for library in common android optional check; do
  # shellcheck disable=SC1090
  source "$SCRIPT_DIR/lib/$library.sh"
done

MODE=check
PROFILE=build
TOOLS_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/777-dev"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$TOOLS_ROOT/android-sdk}}"
ENV_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env"
CONFIGURE_SHELL=false
ACCEPT_ANDROID_LICENSES=false
CREATE_AVDS=false
SKIP_SYSTEM_PACKAGES=false
SELF_TEST=false

usage() {
  cat <<'USAGE'
777 本地开发工具链检查 / 自动配置（Linux / WSL）

用法：
  bash tools/dev/setup-toolchain.sh [选项]

默认只检查，不修改本机。

选项：
  --check                       仅检查当前环境（默认）
  --auto                        自动安装 / 配置缺失工具
  --profile build|full          build=APK 构建；full=再含 Node 22、actionlint、Android 16/17 模拟器
  --tools-root PATH             可移植工具目录（默认 ~/.local/share/777-dev）
  --sdk-root PATH               Android SDK 目录
  --env-file PATH               生成的环境变量文件
  --configure-shell             将 env-file 以幂等方式接入当前 shell rc
  --accept-android-licenses     非交互接受 Android SDK licenses；必须显式传入
  --create-avds                 full 档创建 777-android16 / 777-android17 AVD
  --skip-system-packages        不调用 apt-get，仅配置可移植工具 / Android SDK
  --self-test                   只验证脚本清单与仓库基线一致性
  -h, --help                    显示帮助
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) MODE=check ;;
    --auto) MODE=auto ;;
    --profile) shift; PROFILE="${1:-}" ;;
    --tools-root) shift; TOOLS_ROOT="${1:-}" ;;
    --sdk-root) shift; SDK_ROOT="${1:-}" ;;
    --env-file) shift; ENV_FILE="${1:-}" ;;
    --configure-shell) CONFIGURE_SHELL=true ;;
    --accept-android-licenses) ACCEPT_ANDROID_LICENSES=true ;;
    --create-avds) CREATE_AVDS=true ;;
    --skip-system-packages) SKIP_SYSTEM_PACKAGES=true ;;
    --self-test) SELF_TEST=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[777-toolchain] 未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [ "$PROFILE" != build ] && [ "$PROFILE" != full ]; then
  echo "[777-toolchain] --profile 只支持 build 或 full：$PROFILE" >&2
  exit 2
fi
if [ -z "$TOOLS_ROOT" ] || [ -z "$SDK_ROOT" ] || [ -z "$ENV_FILE" ]; then
  echo "[777-toolchain] tools/sdk/env 路径不能为空" >&2
  exit 2
fi

if [ "$SELF_TEST" = true ]; then
  self_test
  exit 0
fi

detect_host_arch

if [ "$MODE" = auto ]; then
  install_system_packages
  java_home="$(find_java17_home || true)"
  if [ -z "$java_home" ]; then
    echo "[777-toolchain] 自动安装后仍未找到 JDK $JDK_MAJOR" >&2
    exit 1
  fi
  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$PATH"

  mkdir -p "$TOOLS_ROOT" "$SDK_ROOT"
  install_android_commandline_tools
  export ANDROID_SDK_ROOT="$SDK_ROOT"
  export ANDROID_HOME="$SDK_ROOT"
  export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/emulator:$PATH"
  install_android_packages
  install_node22
  install_actionlint
  if [ "$PROFILE" = full ]; then
    export PATH="$TOOLS_ROOT/bin:$TOOLS_ROOT/node-current/bin:$PATH"
  else
    export PATH="$TOOLS_ROOT/bin:$PATH"
  fi
  create_avds
  write_env_file "$java_home"
  configure_shell
fi

check_environment
