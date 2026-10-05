#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$SCRIPT_DIR/setup-toolchain.sh"
DEFAULT_REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -x "$PWD/gradlew" ] && [ -f "$PWD/app/build.gradle.kts" ]; then
  DEFAULT_REPO_ROOT="$PWD"
fi
REPO_ROOT="${DEV777_REPO_ROOT:-$DEFAULT_REPO_ROOT}"

COMMAND="${1:-help}"
if [ "$#" -gt 0 ]; then shift; fi

PROFILE=build
SKIP_SYSTEM_PACKAGES=false
ENV_FILE="${DEV777_TOOLCHAIN_ENV_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/777/dev-toolchain.env}"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi
TOOLS_ROOT="${DEV777_TOOLS_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/777-dev}"
SDK_ROOT="${DEV777_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$TOOLS_ROOT/android-sdk}}}"
STATE_DIR="${DEV777_TOOLCHAIN_STATE_DIR:-$REPO_ROOT/.777/toolchain}"
STATUS_FILE="$STATE_DIR/status.json"
CHECK_LOG="$STATE_DIR/check.log"
BOOTSTRAP_LOG="$STATE_DIR/bootstrap.log"

usage() {
  cat <<'USAGE'
777 AI 开发工具链入口

AI / Agent 推荐只记这个脚本：

  bash tools/dev/ai-toolchain.sh bootstrap
  bash tools/dev/ai-toolchain.sh check
  bash tools/dev/ai-toolchain.sh status
  bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug
  bash tools/dev/ai-toolchain.sh run -- <command> [args...]

命令：
  bootstrap                 非交互检查；缺什么自动配置什么；成功后输出 JSON
  check                     只检查，不修改机器；输出 JSON，失败时退出码为 1
  status                    只输出最近一次机器可读状态 JSON
  gradle [tasks...]         自动确保 build 环境就绪后执行仓库 Gradle Wrapper
  run -- <command> [...]    自动确保 build 环境就绪后，在工具链环境中执行任意命令
  env                       只输出环境文件路径
  help                      显示帮助

选项：
  --profile build|full      默认 build
  --skip-system-packages    不调用 apt-get；系统依赖必须已经存在

环境变量（用于 CI / 沙箱覆盖默认目录）：
  DEV777_TOOLS_ROOT
  DEV777_ANDROID_SDK_ROOT
  DEV777_TOOLCHAIN_ENV_FILE
  DEV777_TOOLCHAIN_STATE_DIR
  DEV777_REPO_ROOT

约定：
- 全程非交互，不修改 shell rc。
- Android licenses 由 AI 入口显式接受。
- sudo 只使用非交互模式；需要密码时立即失败，不等待输入。
- bootstrap / check / status 的 stdout 是 JSON；诊断日志写 stderr 和 STATE_DIR。
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile)
      shift
      PROFILE="${1:-}"
      ;;
    --skip-system-packages)
      SKIP_SYSTEM_PACKAGES=true
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
    *)
      break
      ;;
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
  local status="$1" exit_code="$2" log_file="$3" next_action="$4"
  local tmp="$STATUS_FILE.tmp"
  cat > "$tmp" <<EOF
{
  "schema": 1,
  "status": "$(json_escape "$status")",
  "profile": "$(json_escape "$PROFILE")",
  "exit_code": $exit_code,
  "env_file": "$(json_escape "$ENV_FILE")",
  "sdk_root": "$(json_escape "$SDK_ROOT")",
  "tools_root": "$(json_escape "$TOOLS_ROOT")",
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
    --profile "$PROFILE"
    --tools-root "$TOOLS_ROOT"
    --sdk-root "$SDK_ROOT"
    --env-file "$ENV_FILE"
  )
}

run_check() {
  setup_args
  if bash "$SETUP" --check "${SETUP_ARGS[@]}" >"$CHECK_LOG" 2>&1; then
    write_status ready 0 "$CHECK_LOG" "bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug"
    return 0
  fi
  write_status needs_bootstrap 1 "$CHECK_LOG" "bash tools/dev/ai-toolchain.sh bootstrap --profile $PROFILE"
  return 1
}

load_env() {
  if [ ! -f "$ENV_FILE" ]; then
    echo "[777-ai] 环境文件不存在：$ENV_FILE" >&2
    return 1
  fi
  # shellcheck disable=SC1090
  source "$ENV_FILE"
}

bootstrap_toolchain() {
  local emit_json="${1:-true}"
  if run_check; then
    echo "[777-ai] 环境已就绪，跳过安装。" >&2
    [ "$emit_json" = true ] && emit_status
    return 0
  fi

  echo "[777-ai] 环境未就绪，开始非交互自动配置（profile=$PROFILE）。" >&2
  cat "$CHECK_LOG" >&2

  setup_args
  local args=(
    --auto
    "${SETUP_ARGS[@]}"
    --accept-android-licenses
    --non-interactive
  )
  if [ "$SKIP_SYSTEM_PACKAGES" = true ]; then
    args+=(--skip-system-packages)
  fi

  if ! bash "$SETUP" "${args[@]}" > >(tee "$BOOTSTRAP_LOG" >&2) 2> >(tee -a "$BOOTSTRAP_LOG" >&2); then
    write_status error 1 "$BOOTSTRAP_LOG" "查看日志并修复宿主权限/网络/平台问题后重新执行 bootstrap"
    [ "$emit_json" = true ] && emit_status
    return 1
  fi

  load_env || {
    write_status error 1 "$BOOTSTRAP_LOG" "检查环境文件生成失败"
    [ "$emit_json" = true ] && emit_status
    return 1
  }

  if ! run_check; then
    cat "$CHECK_LOG" >&2
    write_status error 1 "$CHECK_LOG" "自动配置后检查仍失败；查看 check.log"
    [ "$emit_json" = true ] && emit_status
    return 1
  fi

  echo "[777-ai] 工具链已就绪。" >&2
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
      write_status unknown 3 "$CHECK_LOG" "bash tools/dev/ai-toolchain.sh check"
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
    if [ ! -x "$REPO_ROOT/gradlew" ]; then
      echo "[777-ai] 未找到仓库 Gradle Wrapper：$REPO_ROOT/gradlew" >&2
      exit 2
    fi
    cd "$REPO_ROOT"
    exec ./gradlew "$@"
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
