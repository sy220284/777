#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$SCRIPT_DIR/setup-toolchain.sh"
INSTALL="$SCRIPT_DIR/install.sh"
PACKAGE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEFAULT_REPO_ROOT="$PACKAGE_ROOT"
if [ -x "$PWD/gradlew" ] && [ -f "$PWD/app/build.gradle.kts" ]; then
  DEFAULT_REPO_ROOT="$PWD"
fi
REPO_ROOT="${DEV777_REPO_ROOT:-$DEFAULT_REPO_ROOT}"

COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then shift; fi

PROFILE=build
ENV_FILE="${DEV777_TOOLCHAIN_ENV_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env}"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi
TOOLS_ROOT="${DEV777_TOOLS_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/777-dev}"
SDK_ROOT="${DEV777_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-$TOOLS_ROOT/android-sdk}}"
GRADLE_HOME="${DEV777_GRADLE_HOME:-${GRADLE_HOME:-$TOOLS_ROOT/gradle}}"
GRADLE_USER_HOME="${DEV777_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-$TOOLS_ROOT/gradle-user-home}}"
STATE_DIR="${DEV777_TOOLCHAIN_STATE_DIR:-$REPO_ROOT/.777/toolchain}"
STATUS_FILE="$STATE_DIR/status.json"
CHECK_LOG="$STATE_DIR/check.log"
BOOTSTRAP_LOG="$STATE_DIR/bootstrap.log"

usage() {
  cat <<'USAGE'
777 AI 开发工具链入口

  bash tools/dev/ai-toolchain.sh bootstrap
  bash tools/dev/ai-toolchain.sh check
  bash tools/dev/ai-toolchain.sh status
  bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug
  bash tools/dev/ai-toolchain.sh run -- <command> [args...]

命令：
  bootstrap                 只从当前 GitHub Actions Artifact payload 安装/修复；输出 JSON
  check                     只检查，不修改机器；输出 JSON
  status                    输出最近一次机器可读状态
  gradle [tasks...]         使用产物内 Gradle + --offline 执行
  run -- <command> [...]    在已安装的工具链环境中执行任意命令
  env                       输出环境文件路径
  help                      显示帮助

选项：
  --profile build|full      默认 build

约定：
- 禁止通过 apt、curl、sdkmanager、Gradle/Maven、Node 或其他外部渠道补齐。
- bootstrap 缺少 payload 时直接失败，并要求先下载 GitHub Actions 工具链构建产物。
- Gradle 命令固定使用产物内 Gradle 和离线依赖缓存。
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile)
      shift
      PROFILE="${1:-}"
      ;;
    --)
      shift
      break
      ;;
    -*)
      case "$COMMAND" in
        gradle|run) break ;;
        *)
          echo "[777-ai] 未知参数：$1" >&2
          usage >&2
          exit 2
          ;;
      esac
      ;;
    *) break ;;
  esac
  shift
done

if [ "$PROFILE" != build ] && [ "$PROFILE" != full ]; then
  echo "[777-ai] --profile 只支持 build 或 full：$PROFILE" >&2
  exit 2
fi

mkdir -p "$STATE_DIR"

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  printf '%s' "$value"
}

write_status() {
  local status="$1" reason="$2" exit_code="$3" log_file="$4" next_action="$5"
  local tmp="$STATUS_FILE.tmp"
  cat > "$tmp" <<EOF
{
  "schema": 2,
  "status": "$(json_escape "$status")",
  "reason": "$(json_escape "$reason")",
  "profile": "$(json_escape "$PROFILE")",
  "exit_code": $exit_code,
  "repo_root": "$(json_escape "$REPO_ROOT")",
  "env_file": "$(json_escape "$ENV_FILE")",
  "sdk_root": "$(json_escape "$SDK_ROOT")",
  "tools_root": "$(json_escape "$TOOLS_ROOT")",
  "gradle_home": "$(json_escape "$GRADLE_HOME")",
  "gradle_user_home": "$(json_escape "$GRADLE_USER_HOME")",
  "state_dir": "$(json_escape "$STATE_DIR")",
  "log_file": "$(json_escape "$log_file")",
  "next_action": "$(json_escape "$next_action")"
}
EOF
  mv "$tmp" "$STATUS_FILE"
}

emit_status() {
  cat "$STATUS_FILE"
}

setup_args() {
  SETUP_ARGS=(
    --check
    --profile "$PROFILE"
    --tools-root "$TOOLS_ROOT"
    --sdk-root "$SDK_ROOT"
    --gradle-home "$GRADLE_HOME"
    --gradle-user-home "$GRADLE_USER_HOME"
    --env-file "$ENV_FILE"
  )
}

run_check() {
  setup_args
  DEV777_REPO_ROOT="$REPO_ROOT" bash "$SETUP" "${SETUP_ARGS[@]}" >"$CHECK_LOG" 2>&1
  local status=$?
  if [ "$status" -eq 0 ]; then
    write_status ready environment_ready 0 "$CHECK_LOG" "bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug"
    return 0
  fi
  write_status needs_bootstrap missing_dependencies 1 "$CHECK_LOG" "下载 GitHub Actions 工具链 Artifact 后执行其中的 tools/dev/ai-toolchain.sh bootstrap --profile $PROFILE"
  return 1
}

load_env() {
  if [ ! -f "$ENV_FILE" ]; then
    echo "[777-ai] 环境文件不存在：$ENV_FILE" >&2
    return 1
  fi
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  GRADLE_HOME="${DEV777_GRADLE_HOME:-${GRADLE_HOME:-$TOOLS_ROOT/gradle}}"
  GRADLE_USER_HOME="${DEV777_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-$TOOLS_ROOT/gradle-user-home}}"
}

artifact_payload_available() {
  [ -f "$PACKAGE_ROOT/payload/manifest.env" ] &&
    [ -f "$PACKAGE_ROOT/payload/toolchain.tar.gz" ] &&
    [ -f "$PACKAGE_ROOT/SHA256SUMS" ]
}

bootstrap_toolchain() {
  local emit_json="${1:-true}"
  if run_check; then
    echo "[777-ai] 环境已就绪，跳过安装。" >&2
    [ "$emit_json" = true ] && emit_status
    return 0
  fi

  if ! artifact_payload_available; then
    cat "$CHECK_LOG" >&2
    write_status error artifact_required 2 "$CHECK_LOG" "下载 777-dev-toolchain-$PROFILE-latest Artifact，解压后从产物目录执行 tools/dev/ai-toolchain.sh bootstrap"
    [ "$emit_json" = true ] && emit_status
    return 2
  fi

  echo "[777-ai] 从当前 GitHub Actions Artifact payload 安装 profile=$PROFILE。" >&2
  DEV777_REPO_ROOT="$REPO_ROOT" \
  DEV777_TOOLS_ROOT="$TOOLS_ROOT" \
  DEV777_ANDROID_SDK_ROOT="$SDK_ROOT" \
  DEV777_GRADLE_HOME="$GRADLE_HOME" \
  DEV777_GRADLE_USER_HOME="$GRADLE_USER_HOME" \
  DEV777_TOOLCHAIN_ENV_FILE="$ENV_FILE" \
    bash "$INSTALL" "$PROFILE" > >(tee "$BOOTSTRAP_LOG" >&2) 2> >(tee -a "$BOOTSTRAP_LOG" >&2)
  local install_status=$?
  if [ "$install_status" -ne 0 ]; then
    write_status error artifact_install_failed "$install_status" "$BOOTSTRAP_LOG" "检查 Artifact 完整性、架构和宿主基础命令后重新安装"
    [ "$emit_json" = true ] && emit_status
    return "$install_status"
  fi

  if ! load_env; then
    write_status error env_file_missing 1 "$BOOTSTRAP_LOG" "检查 Artifact 安装后的环境文件生成"
    [ "$emit_json" = true ] && emit_status
    return 1
  fi

  if ! run_check; then
    cat "$CHECK_LOG" >&2
    write_status error post_check_failed 1 "$CHECK_LOG" "Artifact 安装后检查仍失败；查看 check.log"
    [ "$emit_json" = true ] && emit_status
    return 1
  fi

  echo "[777-ai] Artifact 工具链已就绪。" >&2
  [ "$emit_json" = true ] && emit_status
}

ensure_build_ready() {
  local previous_profile="$PROFILE"
  PROFILE=build
  if ! bootstrap_toolchain false; then
    PROFILE="$previous_profile"
    return 1
  fi
  PROFILE="$previous_profile"
  load_env
}

case "$COMMAND" in
  bootstrap)
    bootstrap_toolchain true
    ;;
  check)
    if run_check; then
      emit_status
      exit 0
    fi
    cat "$CHECK_LOG" >&2
    emit_status
    exit 1
    ;;
  status)
    if [ ! -f "$STATUS_FILE" ]; then
      write_status unknown not_checked 3 "$CHECK_LOG" "bash tools/dev/ai-toolchain.sh check"
    fi
    emit_status
    ;;
  env)
    printf '%s\n' "$ENV_FILE"
    ;;
  gradle)
    if ! ensure_build_ready; then
      exit 1
    fi
    if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
      echo "[777-ai] 产物内 Gradle 不存在：$GRADLE_HOME/bin/gradle" >&2
      exit 2
    fi
    cd "$REPO_ROOT"
    exec "$GRADLE_HOME/bin/gradle" --offline "$@"
    ;;
  run)
    if [ "${1:-}" = "--" ]; then shift; fi
    if [ "$#" -eq 0 ]; then
      echo "[777-ai] run 需要命令。" >&2
      exit 2
    fi
    if ! ensure_build_ready; then
      exit 1
    fi
    cd "$REPO_ROOT"
    exec "$@"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    echo "[777-ai] 未知命令：$COMMAND" >&2
    usage >&2
    exit 2
    ;;
esac
