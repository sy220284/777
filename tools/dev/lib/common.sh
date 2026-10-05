say() { printf '[777-toolchain] %s\n' "$*"; }
ok() { printf '[777-toolchain] [OK] %s\n' "$*"; }
warn() { printf '[777-toolchain] [WARN] %s\n' "$*" >&2; }
fail() { printf '[777-toolchain] [MISSING] %s\n' "$*" >&2; }

repo_root() {
  local candidate="$SCRIPT_DIR/../.."
  if [ -f "$candidate/app/build.gradle.kts" ] && [ -f "$candidate/.github/workflows/ci.yml" ]; then
    (cd "$candidate" && pwd)
  fi
}

self_test() {
  local root
  root="$(repo_root || true)"
  if [ -z "$root" ]; then
    say "版本清单格式通过；当前脚本不在仓库内，跳过仓库基线一致性检查"
    return 0
  fi
  grep -Fq "minSdk = $ANDROID_MIN_API" "$root/app/build.gradle.kts" || { echo "ANDROID_MIN_API 与 app/build.gradle.kts 不一致" >&2; return 1; }
  grep -Fq "compileSdk = $ANDROID_COMPILE_API" "$root/app/build.gradle.kts" || { echo "ANDROID_COMPILE_API 与 app/build.gradle.kts 不一致" >&2; return 1; }
  grep -Fq "JavaVersion.VERSION_$JDK_MAJOR" "$root/app/build.gradle.kts" || { echo "JDK_MAJOR 与 app/build.gradle.kts 不一致" >&2; return 1; }
  grep -Fq "$ANDROID_PLATFORM_PACKAGE" "$root/.github/workflows/ci.yml" || { echo "Android platform package 与 CI 不一致" >&2; return 1; }
  grep -Fq "build-tools;$ANDROID_BUILD_TOOLS" "$root/.github/workflows/ci.yml" || { echo "Android build-tools 与 CI 不一致" >&2; return 1; }
  grep -Fq "node-version: 22" "$root/.github/workflows/ci.yml" || { echo "Node 主版本与 CI 不一致" >&2; return 1; }
  grep -Fq "VERSION=$ACTIONLINT_VERSION" "$root/.github/workflows/ci.yml" || { echo "actionlint 版本与 CI 不一致" >&2; return 1; }
  grep -Fq "SHA256=\"$ACTIONLINT_LINUX_X64_SHA256\"" "$root/.github/workflows/ci.yml" || { echo "actionlint SHA-256 与 CI 不一致" >&2; return 1; }
  ok "工具链版本清单与当前 app / CI 基线一致"
}

detect_host_arch() {
  if [ "$(uname -s)" != Linux ]; then
    echo "[777-toolchain] 自动配置只支持 Linux / WSL；Windows 请使用 WSL2 Ubuntu。" >&2
    return 2
  fi
  case "$(uname -m)" in
    x86_64|amd64) ARCH_KIND=x64 ;;
    aarch64|arm64) ARCH_KIND=arm64 ;;
    *) echo "[777-toolchain] 暂不支持 CPU 架构：$(uname -m)" >&2; return 2 ;;
  esac
}

run_root() {
  if [ "${EUID:-$(id -u)}" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    if [ "${NON_INTERACTIVE:-false}" = true ]; then
      if ! sudo -n "$@"; then
        echo "[777-toolchain] 非交互模式无法取得 sudo 权限；请预装系统依赖，或为当前环境提供无需输入密码的 sudo。" >&2
        return 1
      fi
    else
      sudo "$@"
    fi
  else
    echo "[777-toolchain] 自动安装系统包需要 root 或 sudo：$*" >&2
    return 1
  fi
}

install_system_packages() {
  if [ "$SKIP_SYSTEM_PACKAGES" = true ]; then
    say "已按参数跳过系统包安装"
    return 0
  fi
  if [ ! -r /etc/os-release ]; then
    echo "[777-toolchain] 无法识别发行版；自动安装仅支持 Debian / Ubuntu。" >&2
    return 1
  fi
  # shellcheck disable=SC1091
  source /etc/os-release
  case "${ID:-}" in ubuntu|debian) ;; *) echo "[777-toolchain] 自动安装系统包仅支持 Debian / Ubuntu，检测到：${ID:-unknown}" >&2; return 1 ;; esac
  say "安装 / 校验系统依赖（JDK 17、Python、GPG、dpkg、binutils 等）"
  run_root env DEBIAN_FRONTEND=noninteractive apt-get update
  run_root env DEBIAN_FRONTEND=noninteractive apt-get install -y \
    bash ca-certificates curl gnupg python3 dpkg binutils coreutils findutils \
    gawk grep sed unzip xz-utils tar git openjdk-17-jdk-headless
}

java_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

find_java17_home() {
  local candidates=() home major
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then candidates+=("$JAVA_HOME"); fi
  if [ -d /usr/lib/jvm ]; then
    while IFS= read -r path; do candidates+=("$path"); done < <(find /usr/lib/jvm -mindepth 1 -maxdepth 1 -type d -name '*17*' | sort)
  fi
  for home in "${candidates[@]}"; do
    [ -x "$home/bin/java" ] && [ -x "$home/bin/javac" ] || continue
    major="$(java_major "$home")"
    if [ "$major" = "$JDK_MAJOR" ]; then printf '%s\n' "$home"; return 0; fi
  done
  if command -v javac >/dev/null 2>&1; then
    home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    if [ "$(java_major "$home")" = "$JDK_MAJOR" ]; then printf '%s\n' "$home"; return 0; fi
  fi
  return 1
}

download_verified() {
  local url="$1" dest="$2" sha256="$3"
  mkdir -p "$(dirname "$dest")"
  if [ -f "$dest" ] && printf '%s  %s\n' "$sha256" "$dest" | sha256sum -c - >/dev/null 2>&1; then
    ok "缓存校验通过：$(basename "$dest")"
    return 0
  fi
  rm -f "$dest"
  say "下载：$url"
  curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 "$url" -o "$dest"
  printf '%s  %s\n' "$sha256" "$dest" | sha256sum -c -
}
