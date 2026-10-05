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
COMPONENTS_FILE="$SCRIPT_DIR/toolchain-components.env"
if [ ! -f "$COMPONENTS_FILE" ]; then
  echo "[777-toolchain] 缺少组件清单：$COMPONENTS_FILE" >&2
  exit 2
fi
# shellcheck disable=SC1090
source "$COMPONENTS_FILE"
for library in common android optional components check; do
  # shellcheck disable=SC1090
  source "$SCRIPT_DIR/lib/$library.sh"
done

PROFILE=build
TOOLS_ROOT="${DEV777_TOOLS_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/777-dev}"
SDK_ROOT="${DEV777_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$TOOLS_ROOT/android-sdk}}}"
GRADLE_HOME="${DEV777_GRADLE_HOME:-${GRADLE_HOME:-$TOOLS_ROOT/gradle}}"
GRADLE_USER_HOME="${DEV777_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-$TOOLS_ROOT/gradle-user-home}}"
ENV_FILE="${DEV777_TOOLCHAIN_ENV_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env}"
SELF_TEST=false
CREATE_AVDS=false
PLAN_ONLY=false

usage() {
  cat <<'USAGE'
777 本地开发工具链检查器（Linux / WSL）

本脚本只做检查，不再负责联网安装。

用法：
  bash tools/dev/setup-toolchain.sh --check [选项]

选项：
  --check                       检查当前环境（默认）
  --plan                        输出当前档位缺失组件对应的 Artifact 名称
  --profile build|full          build=APK 构建；full=再含 Node/actionlint/Android 16/17 模拟器
  --tools-root PATH             工具安装根目录
  --sdk-root PATH               Android SDK 目录
  --gradle-home PATH            构建产物内 Gradle 目录
  --gradle-user-home PATH       构建产物内 Gradle 离线缓存目录
  --env-file PATH               环境变量文件
  --self-test                   只验证工具链版本清单与仓库基线一致性
  --create-avds                 full 档使用产物内 system image 创建 AVD
  -h, --help                    显示帮助

安装必须使用 GitHub Actions 工具链构建产物中的 install.sh。
本地禁止通过 apt、curl、sdkmanager、Gradle/Maven 或其他渠道补齐依赖。
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) ;;
    --plan) PLAN_ONLY=true ;;
    --profile) shift; PROFILE="${1:-}" ;;
    --tools-root) shift; TOOLS_ROOT="${1:-}" ;;
    --sdk-root) shift; SDK_ROOT="${1:-}" ;;
    --gradle-home) shift; GRADLE_HOME="${1:-}" ;;
    --gradle-user-home) shift; GRADLE_USER_HOME="${1:-}" ;;
    --env-file) shift; ENV_FILE="${1:-}" ;;
    --self-test) SELF_TEST=true ;;
    --create-avds) CREATE_AVDS=true ;;
    -h|--help) usage; exit 0 ;;
    --auto|--accept-android-licenses|--skip-system-packages|--configure-shell|--non-interactive)
      echo "[777-toolchain] 已禁用本地联网自动安装参数：$1；请使用 GitHub Actions 构建产物 install.sh。" >&2
      exit 2
      ;;
    *)
      echo "[777-toolchain] 未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [ "$PROFILE" != build ] && [ "$PROFILE" != full ]; then
  echo "[777-toolchain] --profile 只支持 build 或 full：$PROFILE" >&2
  exit 2
fi
if [ -z "$TOOLS_ROOT" ] || [ -z "$SDK_ROOT" ] || [ -z "$GRADLE_HOME" ] || [ -z "$GRADLE_USER_HOME" ] || [ -z "$ENV_FILE" ]; then
  echo "[777-toolchain] tools/sdk/gradle/env 路径不能为空" >&2
  exit 2
fi

detect_host_arch

if [ "$SELF_TEST" = true ]; then
  self_test
  exit 0
fi

if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

if [ "$PLAN_ONLY" = true ]; then
  while IFS= read -r component; do
    [ -n "$component" ] || continue
    artifact_name_for_component "$component"
  done < <(missing_components "$PROFILE")
  exit 0
fi

check_environment
create_avds
