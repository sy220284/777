#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="${1:?usage: prepare-termux-git.sh <generated-output-dir>}"
RUNTIME_ABIS="${DSH_RUNTIME_ABIS:-arm64-v8a}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE_DIR="${TERMUX_RUNTIME_CACHE:-$ROOT_DIR/.gradle/runtime-cache/termux-git}"
TERMUX_REPO="${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
TERMUX_KEY_COMMIT="93c8e0b136bf39e2eb1735f9187f43d7e029bb2e"
TERMUX_KEY_FINGERPRINT="CC72CF8BA7DBFA0182877D045A897D96E57CF20C"
GIT_PACKAGE="git"
GIT_PACKAGE_VERSION="2.55.0"
GIT_RUNTIME_VERSION="2.55.0"
TERMUX_PREFIX="/data/data/com.termux/files/usr"
SYSTEM_LIBS=(
  libc.so libdl.so libm.so liblog.so libandroid.so libpthread.so librt.so
)

for tool in curl gpg python3 dpkg-deb readelf sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "缺少构建工具：$tool" >&2
    exit 1
  }
done

rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT/jniLibs" "$OUT_ROOT/assets/runtime/git/notices" "$CACHE_DIR"
WORK_DIR="$(mktemp -d)"
GNUPGHOME="$WORK_DIR/gnupg"
mkdir -m 700 "$GNUPGHOME"
trap 'rm -rf "$WORK_DIR"' EXIT

KEY_FILE="$CACHE_DIR/termux-autobuilds.gpg"
if [ ! -s "$KEY_FILE" ]; then
  curl -fL --retry 3 --connect-timeout 15     "https://raw.githubusercontent.com/termux/termux-packages/$TERMUX_KEY_COMMIT/packages/termux-keyring/termux-autobuilds.gpg"     -o "$KEY_FILE"
fi

gpg --batch --homedir "$GNUPGHOME" --import "$KEY_FILE" >/dev/null 2>&1
if ! gpg --batch --homedir "$GNUPGHOME" --with-colons --fingerprint   | awk -F: '$1 == "fpr" { print $10 }'   | grep -Fxq "$TERMUX_KEY_FINGERPRINT"; then
  echo "Termux 仓库公钥指纹不匹配" >&2
  exit 1
fi

fetch() {
  local url="$1"
  local dest="$2"
  mkdir -p "$(dirname "$dest")"
  if [ ! -s "$dest" ]; then
    curl -fL --retry 3 --connect-timeout 20 "$url" -o "$dest"
  fi
}

package_field() {
  local packages_file="$1"
  local package_name="$2"
  local field="$3"
  python3 - "$packages_file" "$package_name" "$field" <<'PY'
import sys
path, wanted, field = sys.argv[1:4]
with open(path, encoding="utf-8", errors="strict") as fh:
    stanza = {}
    continuation = None
    for raw in list(fh) + ["\n"]:
        line = raw.rstrip("\n")
        if not line:
            if stanza.get("Package") == wanted:
                print(stanza.get(field, ""))
                raise SystemExit(0)
            stanza = {}
            continuation = None
            continue
        if line[:1].isspace() and continuation:
            stanza[continuation] = stanza.get(continuation, "") + " " + line.strip()
            continue
        key, sep, value = line.partition(": ")
        if sep:
            stanza[key] = value
            continuation = key
raise SystemExit(f"package not found: {wanted}")
PY
}

verify_packages_index() {
  local inrelease="$1"
  local packages="$2"
  local apt_arch="$3"
  gpg --batch --homedir "$GNUPGHOME" --verify "$inrelease" >/dev/null 2>&1 || {
    echo "Termux InRelease 签名验证失败：$apt_arch" >&2
    exit 1
  }
  grep -Fxq "Origin: termux-main stable" "$inrelease" || {
    echo "Termux InRelease Origin 不匹配：$apt_arch" >&2
    exit 1
  }
  local entry="main/binary-$apt_arch/Packages"
  local expected
  expected="$(python3 - "$inrelease" "$entry" <<'PY'
import sys
path, wanted = sys.argv[1:3]
inside = False
for raw in open(path, encoding="utf-8", errors="strict"):
    line = raw.rstrip("\n")
    if line == "SHA256:":
        inside = True
        continue
    if inside:
        if line and not line.startswith(" "):
            break
        parts = line.split()
        if len(parts) == 3 and parts[2] == wanted:
            print(parts[0])
            raise SystemExit(0)
raise SystemExit(f"SHA256 entry not found: {wanted}")
PY
)"
  printf '%s  %s\n' "$expected" "$packages" | sha256sum -c - >/dev/null
}

resolve_runtime_packages() {
  local packages_file="$1"
  shift
  python3 - "$packages_file" "$@" <<'PY'
import re, sys
path = sys.argv[1]
roots = sys.argv[2:]
stanzas = {}
with open(path, encoding="utf-8", errors="strict") as fh:
    current = {}
    continuation = None
    for raw in list(fh) + ["\n"]:
        line = raw.rstrip("\n")
        if not line:
            name = current.get("Package")
            if name:
                stanzas[name] = current
            current = {}
            continuation = None
            continue
        if line[:1].isspace() and continuation:
            current[continuation] = current.get(continuation, "") + " " + line.strip()
            continue
        key, sep, value = line.partition(": ")
        if sep:
            current[key] = value
            continuation = key

providers = {}
for name, stanza in stanzas.items():
    for provided in stanza.get("Provides", "").split(","):
        candidate = provided.strip().split()[0] if provided.strip() else ""
        if candidate:
            providers.setdefault(candidate, []).append(name)

def normalize(alt):
    alt = re.sub(r"\([^)]*\)", "", alt).strip()
    alt = alt.split()[0] if alt else ""
    if ":" in alt:
        alt = alt.split(":", 1)[0]
    return alt

queue = list(roots)
seen = set()
ordered = []
while queue:
    name = queue.pop(0)
    if name in seen:
        continue
    stanza = stanzas.get(name)
    if stanza is None:
        raise SystemExit(f"dependency package not found: {name}")
    seen.add(name)
    ordered.append(name)
    dependency_text = ",".join(
        value for key in ("Pre-Depends", "Depends")
        if (value := stanza.get(key))
    )
    for group in dependency_text.split(","):
        alternatives = [normalize(x) for x in group.split("|")]
        selected = None
        for candidate in alternatives:
            if candidate in stanzas:
                selected = candidate
                break
            if candidate in providers:
                selected = sorted(providers[candidate])[0]
                break
        if selected and selected not in seen:
            queue.append(selected)

for name in ordered:
    print(name)
PY
}

verify_elf_alignment() {
  local file="$1"
  while IFS= read -r alignment; do
    case "$alignment" in
      0x4000|0x8000|0x10000|0x20000|0x40000|0x80000|0x100000) ;;
      *)
        echo "ELF 未满足 16KB 页面对齐：$file ($alignment)" >&2
        exit 1
        ;;
    esac
  done < <(readelf -lW "$file" | awk '$1 == "LOAD" { print $NF }')
}

is_system_library() {
  local wanted="$1"
  local system_library
  for system_library in "${SYSTEM_LIBS[@]}"; do
    [ "$system_library" = "$wanted" ] && return 0
  done
  return 1
}

verify_dependencies() {
  local elf="$1"
  local lib_dir="$2"
  local missing=0
  while IFS= read -r needed; do
    [ -z "$needed" ] && continue
    if is_system_library "$needed"; then
      continue
    fi
    if [ ! -f "$lib_dir/$needed" ]; then
      echo "Git ELF 缺少运行库：$needed（来源：$elf）" >&2
      missing=1
    fi
  done < <(readelf -dW "$elf" 2>/dev/null | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  [ "$missing" -eq 0 ]
}

prepare_arch() {
  local apt_arch="$1"
  local android_abi="$2"
  local index_dir="$CACHE_DIR/index-$apt_arch"
  local packages_file="$index_dir/Packages"
  local inrelease_file="$index_dir/InRelease"

  rm -rf "$index_dir"
  mkdir -p "$index_dir"
  curl -fL --retry 3 --connect-timeout 20     "$TERMUX_REPO/dists/stable/main/binary-$apt_arch/Packages" -o "$packages_file"
  curl -fL --retry 3 --connect-timeout 20     "$TERMUX_REPO/dists/stable/InRelease" -o "$inrelease_file"
  verify_packages_index "$inrelease_file" "$packages_file" "$apt_arch"

  local actual_git_version
  actual_git_version="$(package_field "$packages_file" "$GIT_PACKAGE" Version)"
  if [ "$actual_git_version" != "$GIT_PACKAGE_VERSION" ]; then
    echo "Git 版本漂移：期望 $GIT_PACKAGE_VERSION，实际 $actual_git_version" >&2
    exit 1
  fi

  mapfile -t runtime_packages < <(
    resolve_runtime_packages "$packages_file" "$GIT_PACKAGE" "ca-certificates"
  )

  local jni_dir="$OUT_ROOT/jniLibs/$android_abi"
  local asset_root="$OUT_ROOT/assets/runtime/git/$android_abi"
  local lib_dir="$asset_root/lib"
  local home_dir="$asset_root/home"
  local helper_manifest="$asset_root/helpers.tsv"
  local package_manifest="$asset_root/packages.tsv"
  mkdir -p "$jni_dir" "$lib_dir" "$home_dir"
  : > "$helper_manifest"
  : > "$package_manifest"
  declare -A helper_binary_by_digest=()

  for pkg in "${runtime_packages[@]}"; do
    local version filename digest
    version="$(package_field "$packages_file" "$pkg" Version)"
    filename="$(package_field "$packages_file" "$pkg" Filename)"
    digest="$(package_field "$packages_file" "$pkg" SHA256)"
    [ -n "$version" ] && [ -n "$filename" ] && [ -n "$digest" ] || {
      echo "Termux 包索引字段缺失：$pkg ($apt_arch)" >&2
      exit 1
    }

    local deb="$CACHE_DIR/debs/$apt_arch/$(basename "$filename")"
    fetch "$TERMUX_REPO/$filename" "$deb"
    printf '%s  %s\n' "$digest" "$deb" | sha256sum -c - >/dev/null

    local extracted="$WORK_DIR/$apt_arch/$pkg"
    rm -rf "$extracted"
    mkdir -p "$extracted"
    dpkg-deb -x "$deb" "$extracted"
    local prefix="$extracted$TERMUX_PREFIX"

    if [ "$pkg" = "$GIT_PACKAGE" ]; then
      [ -f "$prefix/bin/git" ] || {
        echo "Termux Git 缺少 bin/git" >&2
        exit 1
      }
      cp -L "$prefix/bin/git" "$jni_dir/libdsh_git.so"
      chmod 0755 "$jni_dir/libdsh_git.so"

      local main_git_digest
      main_git_digest="$(sha256sum "$prefix/bin/git" | awk '{print $1}')"
      helper_binary_by_digest["$main_git_digest"]="libdsh_git.so"

      if [ -d "$prefix/libexec/git-core" ]; then
        while IFS= read -r -d '' helper; do
          local resolved="$helper"
          if [ -L "$helper" ]; then
            resolved="$(readlink -f "$helper")"
          fi
          [ -f "$resolved" ] || continue
          if ! readelf -h "$resolved" >/dev/null 2>&1; then
            continue
          fi

          local helper_name helper_digest native_name
          helper_name="$(basename "$helper")"
          helper_digest="$(sha256sum "$resolved" | awk '{print $1}')"
          native_name="${helper_binary_by_digest[$helper_digest]:-}"
          if [ -z "$native_name" ]; then
            native_name="libdsh_git_helper_${helper_digest:0:20}.so"
            cp -L "$resolved" "$jni_dir/$native_name"
            chmod 0755 "$jni_dir/$native_name"
            helper_binary_by_digest["$helper_digest"]="$native_name"
          fi
          printf '%s\t%s\n' "$helper_name" "$native_name" >> "$helper_manifest"
        done < <(find "$prefix/libexec/git-core" -maxdepth 1 \( -type f -o -type l \) -print0)
      fi

      if [ -d "$prefix/share/git-core/templates" ]; then
        mkdir -p "$home_dir/share/git-core"
        cp -aL "$prefix/share/git-core/templates" "$home_dir/share/git-core/"
      fi
    fi

    if [ -d "$prefix/lib" ]; then
      while IFS= read -r -d '' library; do
        local name
        name="$(basename "$library")"
        cp -L "$library" "$lib_dir/$name"
        chmod 0644 "$lib_dir/$name"
      done < <(find "$prefix/lib" -maxdepth 1 \( -type f -o -type l \) -name 'lib*.so*' -print0)
    fi

    if [ -f "$prefix/etc/tls/cert.pem" ]; then
      mkdir -p "$home_dir/etc/tls"
      cp "$prefix/etc/tls/cert.pem" "$home_dir/etc/tls/cert.pem"
      chmod 0644 "$home_dir/etc/tls/cert.pem"
    fi

    if [ "$apt_arch" = "aarch64" ] && [ -f "$prefix/share/doc/$pkg/copyright" ]; then
      cp "$prefix/share/doc/$pkg/copyright" "$OUT_ROOT/assets/runtime/git/notices/$pkg.txt"
    fi
    printf '%s\t%s\t%s\t%s\n' "$pkg" "$version" "$filename" "$digest" >> "$package_manifest"
  done

  [ -f "$jni_dir/libdsh_git.so" ] || {
    echo "Git 主程序未生成：$android_abi" >&2
    exit 1
  }
  grep -q '^git-remote-http[[:space:]]' "$helper_manifest" || {
    echo "Git HTTPS 远端 helper 未打包：git-remote-http" >&2
    exit 1
  }
  grep -q '^git-remote-https[[:space:]]' "$helper_manifest" || {
    echo "Git HTTPS 远端 helper 未打包：git-remote-https" >&2
    exit 1
  }
  [ -s "$home_dir/etc/tls/cert.pem" ] || {
    echo "Git CA 证书未生成：$android_abi" >&2
    exit 1
  }

  verify_elf_alignment "$jni_dir/libdsh_git.so"
  verify_dependencies "$jni_dir/libdsh_git.so" "$lib_dir"
  while IFS= read -r -d '' elf; do
    verify_elf_alignment "$elf"
    verify_dependencies "$elf" "$lib_dir"
  done < <(find "$jni_dir" -maxdepth 1 -type f -name 'libdsh_git_helper_*.so' -print0)
  while IFS= read -r -d '' library; do
    if readelf -h "$library" >/dev/null 2>&1; then
      verify_elf_alignment "$library"
    fi
  done < <(find "$lib_dir" -maxdepth 1 -type f -print0)
}

IFS=',' read -r -a requested_abis <<< "$RUNTIME_ABIS"
for android_abi in "${requested_abis[@]}"; do
  case "$android_abi" in
    arm64-v8a) prepare_arch aarch64 arm64-v8a ;;
    x86_64) prepare_arch x86_64 x86_64 ;;
    *)
      echo "不支持的运行时 ABI：$android_abi" >&2
      exit 1
      ;;
  esac
done

printf '%s\n' "$GIT_RUNTIME_VERSION" > "$OUT_ROOT/assets/runtime/git/git-version.txt"
cat > "$OUT_ROOT/assets/runtime/git/README.txt" <<EOF
Bundled Git runtime
Git: $GIT_RUNTIME_VERSION
ABIs: $RUNTIME_ABIS
Source packages: Termux termux-main, signature verified with $TERMUX_KEY_FINGERPRINT
Executable: APK native library libdsh_git.so
Native helpers: APK native libraries linked through GIT_EXEC_PATH
Writable Git hooks are disabled because Android 16 does not permit executing app-written binaries.
EOF

echo "Git runtime prepared: $GIT_RUNTIME_VERSION"
