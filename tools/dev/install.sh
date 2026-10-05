#!/usr/bin/env bash
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SELF_DIR/payload/manifest.env" ]; then
  PACKAGE_ROOT="$SELF_DIR"
elif [ -f "$SELF_DIR/../../payload/manifest.env" ]; then
  PACKAGE_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
else
  echo "[777-install] 当前目录没有工具链构建产物 payload。" >&2
  echo "[777-install] 本地安装只允许使用 GitHub Actions 的 777-dev-toolchain-*-latest Artifact。" >&2
  exit 2
fi

DEV_DIR="$PACKAGE_ROOT/tools/dev"
VERSIONS_FILE="$DEV_DIR/toolchain-versions.env"
MANIFEST_FILE="$PACKAGE_ROOT/payload/manifest.env"
ARCHIVE="$PACKAGE_ROOT/payload/toolchain.tar.gz"
ENV_FILE="${DEV777_TOOLCHAIN_ENV_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env}"
TOOLS_ROOT="${DEV777_TOOLS_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/777-dev}"
SDK_ROOT="${DEV777_ANDROID_SDK_ROOT:-$TOOLS_ROOT/android-sdk}"
GRADLE_HOME="${DEV777_GRADLE_HOME:-$TOOLS_ROOT/gradle}"
GRADLE_USER_HOME="${DEV777_GRADLE_USER_HOME:-$TOOLS_ROOT/gradle-user-home}"
REPO_ROOT="${DEV777_REPO_ROOT:-}"

# shellcheck disable=SC1090
source "$VERSIONS_FILE"
# shellcheck disable=SC1090
source "$MANIFEST_FILE"
# shellcheck disable=SC1090
source "$DEV_DIR/lib/common.sh"
# shellcheck disable=SC1090
source "$DEV_DIR/lib/android.sh"
# shellcheck disable=SC1090
source "$DEV_DIR/lib/optional.sh"

usage() {
  cat <<'USAGE'
777 开发环境构建产物安装器

用法：
  bash install.sh build
  bash install.sh full
  bash install.sh check [build|full]

选项：
  --configure-shell   将环境文件接入 shell rc
  --create-avds       full 档使用产物内镜像创建 Android 16 / 17 AVD

规则：
  - 安装只读取当前 Artifact 内 payload。
  - 禁止通过 apt、curl、sdkmanager、Gradle/Maven、Node 或其他外部渠道补齐。
  - build 产物含 JDK、Android SDK、Gradle、Kotlin/AGP/项目依赖离线缓存和 Runtime 构建缓存。
  - full 产物额外含 Node.js、actionlint、Android Emulator 与 Android 16 / 17 system image。
USAGE
}

PROFILE="${1:-build}"
if [ "$#" -gt 0 ]; then shift; fi
CONFIGURE_SHELL=false
CREATE_AVDS=false

if [ "$PROFILE" = check ]; then
  PROFILE="${1:-build}"
  if [ "$#" -gt 0 ]; then shift; fi
  exec bash "$DEV_DIR/setup-toolchain.sh" --check --profile "$PROFILE" \
    --tools-root "$TOOLS_ROOT" \
    --sdk-root "$SDK_ROOT" \
    --gradle-home "$GRADLE_HOME" \
    --gradle-user-home "$GRADLE_USER_HOME" \
    --env-file "$ENV_FILE"
fi

case "$PROFILE" in
  build|full) ;;
  -h|--help|help) usage; exit 0 ;;
  *)
    echo "[777-install] 只支持 build 或 full：$PROFILE" >&2
    usage >&2
    exit 2
    ;;
esac

while [ "$#" -gt 0 ]; do
  case "$1" in
    --configure-shell) CONFIGURE_SHELL=true ;;
    --create-avds) CREATE_AVDS=true ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "[777-install] 未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [ ! -f "$ARCHIVE" ] || [ ! -f "$PACKAGE_ROOT/SHA256SUMS" ]; then
  echo "[777-install] Artifact 不完整：缺少 payload/toolchain.tar.gz 或 SHA256SUMS。" >&2
  exit 2
fi

for command_name in sha256sum tar gzip cp rm mkdir mktemp uname; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "[777-install] 宿主缺少基础命令：$command_name；按离线规则不会从其他渠道安装。" >&2
    exit 1
  }
done

(
  cd "$PACKAGE_ROOT"
  sha256sum -c SHA256SUMS
)

detect_host_arch
if [ "${ARTIFACT_ARCH:-}" != "$ARCH_KIND" ]; then
  echo "[777-install] Artifact 架构为 ${ARTIFACT_ARCH:-unknown}，当前宿主为 $ARCH_KIND。" >&2
  exit 1
fi
if [ "$PROFILE" = full ] && [ "${ARTIFACT_PROFILE:-}" != full ]; then
  echo "[777-install] 当前是 build Artifact；full 环境必须下载 777-dev-toolchain-full-latest。" >&2
  exit 1
fi

safe_replace_root() {
  local path="$1" label="$2"
  if [ -z "$path" ] || [ "$path" = "/" ] || [ "$path" = "$HOME" ]; then
    echo "[777-install] 拒绝使用危险的 $label 路径：$path" >&2
    exit 2
  fi
}

safe_replace_root "$TOOLS_ROOT" "TOOLS_ROOT"
safe_replace_root "$SDK_ROOT" "SDK_ROOT"
safe_replace_root "$GRADLE_HOME" "GRADLE_HOME"
safe_replace_root "$GRADLE_USER_HOME" "GRADLE_USER_HOME"

tmp="$(mktemp -d)"
cleanup() { rm -rf "$tmp"; }
trap cleanup EXIT
tar -xzf "$ARCHIVE" -C "$tmp"

for required in jdk android-sdk gradle gradle-user-home; do
  [ -d "$tmp/$required" ] || {
    echo "[777-install] Artifact payload 缺少：$required" >&2
    exit 1
  }
done

if [ -z "$REPO_ROOT" ] && [ -x "$PWD/gradlew" ] && [ -f "$PWD/app/build.gradle.kts" ]; then
  REPO_ROOT="$PWD"
fi

mkdir -p "$TOOLS_ROOT" "$(dirname "$SDK_ROOT")" "$(dirname "$ENV_FILE")"

rm -rf "$TOOLS_ROOT/jdk" "$GRADLE_HOME" "$GRADLE_USER_HOME"
cp -a "$tmp/jdk" "$TOOLS_ROOT/jdk"
cp -a "$tmp/gradle" "$GRADLE_HOME"
cp -a "$tmp/gradle-user-home" "$GRADLE_USER_HOME"

if [ "$SDK_ROOT" = "$TOOLS_ROOT/android-sdk" ]; then
  rm -rf "$TOOLS_ROOT/android-sdk"
  cp -a "$tmp/android-sdk" "$TOOLS_ROOT/android-sdk"
else
  rm -rf "$SDK_ROOT"
  cp -a "$tmp/android-sdk" "$SDK_ROOT"
fi

mkdir -p "$TOOLS_ROOT/bin"
if [ -d "$tmp/node-current" ]; then
  rm -rf "$TOOLS_ROOT/node-current"
  cp -a "$tmp/node-current" "$TOOLS_ROOT/node-current"
fi
if [ -x "$tmp/bin/actionlint" ]; then
  cp -a "$tmp/bin/actionlint" "$TOOLS_ROOT/bin/actionlint"
fi

if [ -d "$tmp/runtime-cache" ]; then
  if [ -n "$REPO_ROOT" ]; then
    safe_replace_root "$REPO_ROOT" "REPO_ROOT"
    mkdir -p "$REPO_ROOT/.gradle"
    rm -rf "$REPO_ROOT/.gradle/runtime-cache"
    cp -a "$tmp/runtime-cache" "$REPO_ROOT/.gradle/runtime-cache"
    ok "Runtime 构建缓存已安装到：$REPO_ROOT/.gradle/runtime-cache"
  else
    warn "未识别仓库目录，Runtime 构建缓存暂未落入仓库；在仓库根目录重新执行 install.sh 即可补齐。"
  fi
fi

JAVA_HOME="$TOOLS_ROOT/jdk"
export JAVA_HOME ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT"
export GRADLE_HOME GRADLE_USER_HOME
export DEV777_TOOLS_ROOT="$TOOLS_ROOT"
export DEV777_ANDROID_SDK_ROOT="$SDK_ROOT"
export DEV777_GRADLE_HOME="$GRADLE_HOME"
export DEV777_GRADLE_USER_HOME="$GRADLE_USER_HOME"
if [ -n "$REPO_ROOT" ]; then
  export DEV777_REPO_ROOT="$REPO_ROOT"
fi

write_env_file "$JAVA_HOME"
configure_shell

# shellcheck disable=SC1090
source "$ENV_FILE"

if [ "$PROFILE" = full ] && [ "$CREATE_AVDS" = true ]; then
  create_avds
fi

bash "$DEV_DIR/setup-toolchain.sh" --check --profile "$PROFILE" \
  --tools-root "$TOOLS_ROOT" \
  --sdk-root "$SDK_ROOT" \
  --gradle-home "$GRADLE_HOME" \
  --gradle-user-home "$GRADLE_USER_HOME" \
  --env-file "$ENV_FILE"

cat <<EOF

✅ 777 开发环境已从 GitHub Actions 构建产物安装完成（$PROFILE）。

当前终端启用：
  source "$ENV_FILE"

推荐构建：
  bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug

该入口固定使用产物内 Gradle 与离线依赖缓存，不会联网补齐工具或依赖。
EOF
