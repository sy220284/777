say() { printf '[777-toolchain] %s\n' "$*"; }
ok() { printf '[777-toolchain] [OK] %s\n' "$*"; }
warn() { printf '[777-toolchain] [WARN] %s\n' "$*" >&2; }
manual() { printf '[777-toolchain] [MANUAL] %s\n' "$*" >&2; }
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
  grep -Fq "JavaVersion.VERSION_$JVM_TARGET" "$root/app/build.gradle.kts" || { echo "JVM_TARGET 与 app/build.gradle.kts 不一致" >&2; return 1; }
  grep -Fq "java-version: $BUILD_JDK_MIN_MAJOR" "$root/.github/workflows/ci.yml" || { echo "BUILD_JDK_MIN_MAJOR 与 CI 不一致" >&2; return 1; }
  grep -Fq "$ANDROID_PLATFORM_PACKAGE" "$root/.github/workflows/ci.yml" || { echo "Android platform package 与 CI 不一致" >&2; return 1; }
  grep -Fq "build-tools;$ANDROID_BUILD_TOOLS" "$root/.github/workflows/ci.yml" || { echo "Android build-tools 与 CI 不一致" >&2; return 1; }
  grep -Fq "gradle-$GRADLE_VERSION-bin.zip" "$root/gradle/wrapper/gradle-wrapper.properties" || { echo "Gradle 版本与 Wrapper 不一致" >&2; return 1; }
  grep -Fq "agp = \"$AGP_VERSION\"" "$root/gradle/libs.versions.toml" || { echo "AGP_VERSION 与版本目录不一致" >&2; return 1; }
  grep -Fq "kotlin = \"$KOTLIN_VERSION\"" "$root/gradle/libs.versions.toml" || { echo "KOTLIN_VERSION 与版本目录不一致" >&2; return 1; }
  grep -Fq "node-version: $NODE_MIN_MAJOR" "$root/.github/workflows/ci.yml" || { echo "NODE_MIN_MAJOR 与 CI 不一致" >&2; return 1; }
  grep -Fq "VERSION=$ACTIONLINT_VERSION" "$root/.github/workflows/ci.yml" || { echo "actionlint 版本与 CI 不一致" >&2; return 1; }
  grep -Fq "SHA256=\"$ACTIONLINT_LINUX_X64_SHA256\"" "$root/.github/workflows/ci.yml" || { echo "actionlint SHA-256 与 CI 不一致" >&2; return 1; }
  ok "工具链版本清单与当前 app / CI / Gradle 基线一致"
}

detect_host_arch() {
  if [ "$(uname -s)" != Linux ]; then
    echo "[777-toolchain] 本地产物只支持 Linux / WSL；Windows 请使用 WSL2 Ubuntu。" >&2
    return 2
  fi
  case "$(uname -m)" in
    x86_64|amd64) ARCH_KIND=x64 ;;
    aarch64|arm64) ARCH_KIND=arm64 ;;
    *) echo "[777-toolchain] 暂不支持 CPU 架构：$(uname -m)" >&2; return 2 ;;
  esac
}

java_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

find_compatible_java_home() {
  local candidates=() home major
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    candidates+=("$JAVA_HOME")
  fi
  if [ -n "${DEV777_TOOLS_ROOT:-}" ] && [ -d "${DEV777_TOOLS_ROOT}/jdk" ]; then
    candidates+=("${DEV777_TOOLS_ROOT}/jdk")
  fi
  if [ -d /usr/lib/jvm ]; then
    while IFS= read -r path; do candidates+=("$path"); done < <(
      find /usr/lib/jvm -mindepth 1 -maxdepth 1 \( -type d -o -type l \) | sort
    )
  fi
  for home in "${candidates[@]}"; do
    [ -x "$home/bin/java" ] && [ -x "$home/bin/javac" ] || continue
    major="$(java_major "$home")"
    if [[ "$major" =~ ^[0-9]+$ ]] && [ "$major" -ge "$BUILD_JDK_MIN_MAJOR" ]; then
      printf '%s\n' "$home"
      return 0
    fi
  done
  if command -v javac >/dev/null 2>&1; then
    home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    major="$(java_major "$home")"
    if [[ "$major" =~ ^[0-9]+$ ]] && [ "$major" -ge "$BUILD_JDK_MIN_MAJOR" ]; then
      printf '%s\n' "$home"
      return 0
    fi
  fi
  return 1
}

node_major() {
  "$1" -p 'process.versions.node.split(".")[0]' 2>/dev/null
}

required_host_commands() {
  printf '%s\n' bash python3 dpkg-deb readelf sha256sum tar gzip git find awk sed grep head tr cat cp rm mkdir
}
