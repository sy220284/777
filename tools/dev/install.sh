#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/toolchain-versions.env" ]; then
  DEV_DIR="$SCRIPT_DIR"
elif [ -f "$SCRIPT_DIR/tools/dev/toolchain-versions.env" ]; then
  DEV_DIR="$SCRIPT_DIR/tools/dev"
else
  echo "[777-install] 找不到 tools/dev/toolchain-versions.env。" >&2
  exit 2
fi

VERSIONS_FILE="$DEV_DIR/toolchain-versions.env"
COMPONENTS_FILE="$DEV_DIR/toolchain-components.env"
# shellcheck disable=SC1090
source "$VERSIONS_FILE"
# shellcheck disable=SC1090
source "$COMPONENTS_FILE"
for library in common android optional components check; do
  # shellcheck disable=SC1090
  source "$DEV_DIR/lib/$library.sh"
done

COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then shift; fi
PROFILE=build
ARTIFACTS_DIR="${DEV777_ARTIFACTS_DIR:-}"
CONFIGURE_SHELL=false
CREATE_AVDS=false

TOOLS_ROOT="${DEV777_TOOLS_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/777-dev}"
SDK_ROOT="${DEV777_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$TOOLS_ROOT/android-sdk}}}"
GRADLE_HOME="${DEV777_GRADLE_HOME:-${GRADLE_HOME:-$TOOLS_ROOT/gradle}}"
GRADLE_USER_HOME="${DEV777_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-$TOOLS_ROOT/gradle-user-home}}"
ENV_FILE="${DEV777_TOOLCHAIN_ENV_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env}"
REPO_ROOT="${DEV777_REPO_ROOT:-}"
if [ -z "$REPO_ROOT" ] && [ -x "$PWD/gradlew" ] && [ -f "$PWD/app/build.gradle.kts" ]; then
  REPO_ROOT="$PWD"
fi
if [ -n "$REPO_ROOT" ]; then
  export DEV777_REPO_ROOT="$REPO_ROOT"
fi

usage() {
  cat <<'USAGE'
777 开发工具链按需安装器

用法：
  bash tools/dev/install.sh plan [build|full]
  bash tools/dev/install.sh install [build|full] --artifacts-dir PATH
  bash tools/dev/install.sh check [build|full]

选项：
  --artifacts-dir PATH   已下载并解压的 GitHub Actions 组件 Artifact 根目录
  --configure-shell     将环境文件接入 shell rc
  --create-avds         full 档使用已安装镜像创建 Android 16 / 17 AVD

规则：
  - plan 只检查本机，输出真正缺失的 Artifact 名称。
  - install 只安装缺失组件；已经满足版本要求的组件直接复用。
  - 组件来源只允许 GitHub Actions Artifact。
  - 组件 Artifact 名称固定为 777-toolchain-<component>-latest。
  - 本地不会从 apt、Google SDK、Gradle/Maven、Node 等其他渠道补齐。
USAGE
}

case "$COMMAND" in
  plan|install|check)
    if [ "$#" -gt 0 ] && { [ "$1" = build ] || [ "$1" = full ]; }; then
      PROFILE="$1"
      shift
    fi
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    echo "[777-install] 未知命令：$COMMAND" >&2
    usage >&2
    exit 2
    ;;
esac

while [ "$#" -gt 0 ]; do
  case "$1" in
    --artifacts-dir)
      shift
      ARTIFACTS_DIR="${1:-}"
      ;;
    --configure-shell)
      CONFIGURE_SHELL=true
      ;;
    --create-avds)
      CREATE_AVDS=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[777-install] 未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

detect_host_arch
if [ "$ARCH_KIND" != x64 ]; then
  echo "[777-install] 当前组件 Artifact 只发布 x64，检测到：$ARCH_KIND" >&2
  exit 2
fi

if [ "$COMMAND" = plan ]; then
  missing_components "$PROFILE" | while IFS= read -r component; do
    [ -n "$component" ] || continue
    artifact_name_for_component "$component"
  done
  exit 0
fi

if [ "$COMMAND" = check ]; then
  exec bash "$DEV_DIR/setup-toolchain.sh" --check --profile "$PROFILE" \
    --tools-root "$TOOLS_ROOT" \
    --sdk-root "$SDK_ROOT" \
    --gradle-home "$GRADLE_HOME" \
    --gradle-user-home "$GRADLE_USER_HOME" \
    --env-file "$ENV_FILE"
fi

if [ -z "$ARTIFACTS_DIR" ] || [ ! -d "$ARTIFACTS_DIR" ]; then
  echo "[777-install] install 需要 --artifacts-dir，目录中放置按 plan 下载并解压的组件 Artifact。" >&2
  exit 2
fi

for command_name in sha256sum tar gzip cp rm mkdir mktemp uname; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "[777-install] 宿主缺少基础命令：$command_name；不会从其他渠道安装。" >&2
    exit 1
  }
done

safe_root() {
  local path="$1" label="$2"
  if [ -z "$path" ] || [ "$path" = / ] || [ "$path" = "$HOME" ]; then
    echo "[777-install] 拒绝危险的 $label 路径：$path" >&2
    exit 2
  fi
}
safe_root "$TOOLS_ROOT" TOOLS_ROOT
safe_root "$SDK_ROOT" SDK_ROOT
safe_root "$GRADLE_HOME" GRADLE_HOME
safe_root "$GRADLE_USER_HOME" GRADLE_USER_HOME
if [ -n "$REPO_ROOT" ]; then safe_root "$REPO_ROOT" REPO_ROOT; fi

find_component_dir() {
  local component="$1" artifact direct declared_component
  artifact="$(artifact_name_for_component "$component")"
  for direct in "$ARTIFACTS_DIR/$artifact" "$ARTIFACTS_DIR/$component" "$ARTIFACTS_DIR"; do
    if [ -f "$direct/component.env" ]; then
      # 校验前绝不 source 下载内容；只把 ARTIFACT_COMPONENT 当纯文本读取，
      # 等 SHA-256 通过后再加载完整元数据。
      declared_component="$(sed -n 's/^ARTIFACT_COMPONENT=//p' "$direct/component.env" | head -n 1)"
      if [ "$declared_component" = "$component" ]; then
        printf '%s\n' "$direct"
        return 0
      fi
    fi
  done
  return 1
}

install_component() {
  local component="$1" dir archive tmp artifact_component artifact_arch
  dir="$(find_component_dir "$component" || true)"
  if [ -z "$dir" ]; then
    echo "[777-install] 缺少组件 Artifact：$(artifact_name_for_component "$component")" >&2
    return 3
  fi
  if [ ! -f "$dir/SHA256SUMS" ] || [ ! -f "$dir/payload.tar.gz" ]; then
    echo "[777-install] 组件 Artifact 不完整：$dir" >&2
    return 3
  fi

  (
    cd "$dir"
    sha256sum -c SHA256SUMS
  )

  # shellcheck disable=SC1090
  source "$dir/component.env"
  artifact_component="${ARTIFACT_COMPONENT:-}"
  artifact_arch="${ARTIFACT_ARCH:-}"
  [ "$artifact_component" = "$component" ] || {
    echo "[777-install] 组件身份不匹配：期望 $component，实际 $artifact_component" >&2
    return 3
  }
  [ "$artifact_arch" = "$ARCH_KIND" ] || {
    echo "[777-install] 组件架构不匹配：$artifact_arch / $ARCH_KIND" >&2
    return 3
  }

  tmp="$(mktemp -d)"
  archive="$dir/payload.tar.gz"
  tar -xzf "$archive" -C "$tmp"

  case "$component" in
    jdk)
      test -d "$tmp/jdk"
      rm -rf "$TOOLS_ROOT/jdk"
      mkdir -p "$TOOLS_ROOT"
      cp -a "$tmp/jdk" "$TOOLS_ROOT/jdk"
      ;;
    android-core)
      test -d "$tmp/android-sdk"
      mkdir -p "$SDK_ROOT"
      for part in cmdline-tools platform-tools build-tools platforms licenses; do
        if [ -e "$tmp/android-sdk/$part" ]; then
          rm -rf "$SDK_ROOT/$part"
          cp -a "$tmp/android-sdk/$part" "$SDK_ROOT/$part"
        fi
      done
      ;;
    gradle-runtime)
      test -d "$tmp/gradle"
      rm -rf "$GRADLE_HOME"
      mkdir -p "$(dirname "$GRADLE_HOME")"
      cp -a "$tmp/gradle" "$GRADLE_HOME"
      ;;
    gradle-deps)
      test -d "$tmp/gradle-user-home"
      rm -rf "$GRADLE_USER_HOME"
      mkdir -p "$(dirname "$GRADLE_USER_HOME")"
      cp -a "$tmp/gradle-user-home" "$GRADLE_USER_HOME"
      ;;
    runtime-cache)
      test -d "$tmp/runtime-cache"
      if [ -z "$REPO_ROOT" ]; then
        echo "[777-install] runtime-cache 需要在 777 仓库根目录执行，或设置 DEV777_REPO_ROOT。" >&2
        rm -rf "$tmp"
        return 3
      fi
      mkdir -p "$REPO_ROOT/.gradle"
      rm -rf "$REPO_ROOT/.gradle/runtime-cache"
      cp -a "$tmp/runtime-cache" "$REPO_ROOT/.gradle/runtime-cache"
      ;;
    node)
      test -d "$tmp/node-current"
      rm -rf "$TOOLS_ROOT/node-current"
      mkdir -p "$TOOLS_ROOT"
      cp -a "$tmp/node-current" "$TOOLS_ROOT/node-current"
      ;;
    actionlint)
      test -x "$tmp/bin/actionlint"
      mkdir -p "$TOOLS_ROOT/bin"
      cp -a "$tmp/bin/actionlint" "$TOOLS_ROOT/bin/actionlint"
      chmod +x "$TOOLS_ROOT/bin/actionlint"
      ;;
    emulator)
      test -d "$tmp/android-sdk/emulator"
      rm -rf "$SDK_ROOT/emulator"
      mkdir -p "$SDK_ROOT"
      cp -a "$tmp/android-sdk/emulator" "$SDK_ROOT/emulator"
      ;;
    android-image-16)
      test -d "$tmp/android-sdk/system-images/android-36"
      mkdir -p "$SDK_ROOT/system-images"
      rm -rf "$SDK_ROOT/system-images/android-36"
      cp -a "$tmp/android-sdk/system-images/android-36" "$SDK_ROOT/system-images/android-36"
      ;;
    android-image-17)
      test -d "$tmp/android-sdk/system-images/android-37.0"
      mkdir -p "$SDK_ROOT/system-images"
      rm -rf "$SDK_ROOT/system-images/android-37.0"
      cp -a "$tmp/android-sdk/system-images/android-37.0" "$SDK_ROOT/system-images/android-37.0"
      ;;
    *)
      echo "[777-install] 未知组件：$component" >&2
      rm -rf "$tmp"
      return 3
      ;;
  esac

  rm -rf "$tmp"
  ok "已安装组件：$component"
}

missing_before="$(missing_components "$PROFILE")"
if [ -z "$missing_before" ]; then
  ok "当前 $PROFILE 环境已经满足，无需下载或安装组件"
else
  failed=0
  while IFS= read -r component; do
    [ -n "$component" ] || continue
    if component_ready "$component"; then
      ok "复用现有组件：$component"
      continue
    fi
    install_component "$component" || failed=1
  done <<< "$missing_before"

  if [ "$failed" -ne 0 ]; then
    echo "[777-install] 仍缺少以下 Artifact：" >&2
    missing_components "$PROFILE" | while IFS= read -r component; do
      [ -n "$component" ] || continue
      artifact_name_for_component "$component" >&2
    done
    exit 3
  fi
fi

java_home="$(find_java17_home || true)"
if [ -z "$java_home" ]; then
  echo "[777-install] 安装后仍未找到 JDK $JDK_MAJOR。" >&2
  exit 1
fi

export JAVA_HOME="$java_home"
export ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT"
export GRADLE_HOME GRADLE_USER_HOME
export DEV777_TOOLS_ROOT="$TOOLS_ROOT"
export DEV777_ANDROID_SDK_ROOT="$SDK_ROOT"
export DEV777_GRADLE_HOME="$GRADLE_HOME"
export DEV777_GRADLE_USER_HOME="$GRADLE_USER_HOME"

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

✅ 777 工具链按需安装完成（$PROFILE）。

只安装了当前缺失组件；已有且版本正确的组件已复用。
构建入口：
  bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug
EOF
