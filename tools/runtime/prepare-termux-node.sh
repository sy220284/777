#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="${1:?usage: prepare-termux-node.sh <generated-output-dir>}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE_DIR="${TERMUX_RUNTIME_CACHE:-$ROOT_DIR/.runtime-cache/termux-node}"
TERMUX_REPO="${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
TERMUX_KEY_COMMIT="93c8e0b136bf39e2eb1735f9187f43d7e029bb2e"
TERMUX_KEY_FINGERPRINT="CC72CF8BA7DBFA0182877D045A897D96E57CF20C"
NODE_PACKAGE="nodejs-lts"
NODE_PACKAGE_VERSION="24.18.0-1"
NODE_RUNTIME_VERSION="24.18.0"
RUNTIME_PACKAGES=(nodejs-lts libc++ openssl c-ares libicu libsqlite zlib)
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
mkdir -p "$OUT_ROOT/jniLibs" "$OUT_ROOT/assets/runtime/node/notices" "$CACHE_DIR"
WORK_DIR="$(mktemp -d)"
GNUPGHOME="$WORK_DIR/gnupg"
mkdir -m 700 "$GNUPGHOME"
trap 'rm -rf "$WORK_DIR"' EXIT

KEY_FILE="$CACHE_DIR/termux-autobuilds.gpg"
if [ ! -s "$KEY_FILE" ]; then
  curl -fL --retry 3 --connect-timeout 15 \
    "https://raw.githubusercontent.com/termux/termux-packages/$TERMUX_KEY_COMMIT/packages/termux-keyring/termux-autobuilds.gpg" \
    -o "$KEY_FILE"
fi

gpg --batch --homedir "$GNUPGHOME" --import "$KEY_FILE" >/dev/null 2>&1
if ! gpg --batch --homedir "$GNUPGHOME" --with-colons --fingerprint \
  | awk -F: '$1 == "fpr" { print $10 }' \
  | grep -Fxq "$TERMUX_KEY_FINGERPRINT"; then
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
    for raw in fh:
        line = raw.rstrip("\n")
        if not line:
            if stanza.get("Package") == wanted:
                print(stanza.get(field, ""))
                raise SystemExit(0)
            stanza = {}
            continue
        if line[:1].isspace():
            continue
        key, sep, value = line.partition(": ")
        if sep:
            stanza[key] = value
    if stanza.get("Package") == wanted:
        print(stanza.get(field, ""))
        raise SystemExit(0)
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

patch_node_shell() {
  local node="$1"
  python3 - "$node" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
needle = b"/data/data/com.termux/files/usr/bin/sh"
replacement = b"/system/bin/sh"
count = data.count(needle)
if count < 1:
    raise SystemExit("Termux Node 默认 shell 字符串未找到，拒绝生成不可预测运行时")
padded = replacement + b"\0" * (len(needle) - len(replacement))
path.write_bytes(data.replace(needle, padded))
print(f"patched default shell occurrences: {count}")
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

verify_dependencies() {
  local node="$1"
  local lib_dir="$2"
  local missing=0
  local available
  available="$(find "$lib_dir" -maxdepth 1 -type f -printf '%f\n' | sort -u)"
  while IFS= read -r needed; do
    [ -z "$needed" ] && continue
    if printf '%s\n' "${SYSTEM_LIBS[@]}" | grep -Fxq "$needed"; then
      continue
    fi
    if ! printf '%s\n' "$available" | grep -Fxq "$needed"; then
      echo "Node 缺少运行库：$needed" >&2
      missing=1
    fi
  done < <(readelf -dW "$node" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  [ "$missing" -eq 0 ]
}

prepare_arch() {
  local apt_arch="$1"
  local android_abi="$2"
  local index_dir="$CACHE_DIR/index-$apt_arch"
  local packages_url="$TERMUX_REPO/dists/stable/main/binary-$apt_arch/Packages"
  local inrelease_url="$TERMUX_REPO/dists/stable/InRelease"
  local packages_file="$index_dir/Packages"
  local inrelease_file="$index_dir/InRelease"

  rm -rf "$index_dir"
  mkdir -p "$index_dir"
  curl -fL --retry 3 --connect-timeout 20 "$packages_url" -o "$packages_file"
  curl -fL --retry 3 --connect-timeout 20 "$inrelease_url" -o "$inrelease_file"
  verify_packages_index "$inrelease_file" "$packages_file" "$apt_arch"

  local jni_dir="$OUT_ROOT/jniLibs/$android_abi"
  local lib_dir="$OUT_ROOT/assets/runtime/node/$android_abi/lib"
  local manifest="$OUT_ROOT/assets/runtime/node/$android_abi/packages.tsv"
  mkdir -p "$jni_dir" "$lib_dir"
  : > "$manifest"

  for pkg in "${RUNTIME_PACKAGES[@]}"; do
    local version filename digest
    version="$(package_field "$packages_file" "$pkg" Version)"
    filename="$(package_field "$packages_file" "$pkg" Filename)"
    digest="$(package_field "$packages_file" "$pkg" SHA256)"
    [ -n "$version" ] && [ -n "$filename" ] && [ -n "$digest" ] || {
      echo "Termux 包索引字段缺失：$pkg ($apt_arch)" >&2
      exit 1
    }
    if [ "$pkg" = "$NODE_PACKAGE" ] && [ "$version" != "$NODE_PACKAGE_VERSION" ]; then
      echo "Node 版本漂移：期望 $NODE_PACKAGE_VERSION，实际 $version" >&2
      exit 1
    fi

    local deb="$CACHE_DIR/debs/$apt_arch/$(basename "$filename")"
    fetch "$TERMUX_REPO/$filename" "$deb"
    printf '%s  %s\n' "$digest" "$deb" | sha256sum -c - >/dev/null

    local extracted="$WORK_DIR/$apt_arch/$pkg"
    rm -rf "$extracted"
    mkdir -p "$extracted"
    dpkg-deb -x "$deb" "$extracted"

    local prefix="$extracted/data/data/com.termux/files/usr"
    if [ "$pkg" = "$NODE_PACKAGE" ]; then
      [ -f "$prefix/bin/node" ] || {
        echo "Termux $pkg 缺少 bin/node" >&2
        exit 1
      }
      cp "$prefix/bin/node" "$jni_dir/libdsh_node.so"
      chmod 0755 "$jni_dir/libdsh_node.so"
      patch_node_shell "$jni_dir/libdsh_node.so"
    else
      if [ -d "$prefix/lib" ]; then
        while IFS= read -r -d '' library; do
          local name
          name="$(basename "$library")"
          cp -L "$library" "$lib_dir/$name"
          chmod 0644 "$lib_dir/$name"
        done < <(find "$prefix/lib" -maxdepth 1 \( -type f -o -type l \) -name 'lib*.so*' -print0)
      fi
    fi

    if [ "$apt_arch" = "aarch64" ] && [ -f "$prefix/share/doc/$pkg/copyright" ]; then
      cp "$prefix/share/doc/$pkg/copyright" "$OUT_ROOT/assets/runtime/node/notices/$pkg.txt"
    fi
    printf '%s\t%s\t%s\t%s\n' "$pkg" "$version" "$filename" "$digest" >> "$manifest"
  done

  verify_elf_alignment "$jni_dir/libdsh_node.so"
  while IFS= read -r -d '' library; do
    if head -c 4 "$library" | grep -q $'\x7fELF'; then
      verify_elf_alignment "$library"
    fi
  done < <(find "$lib_dir" -maxdepth 1 -type f -print0)
  verify_dependencies "$jni_dir/libdsh_node.so" "$lib_dir"
}

prepare_arch aarch64 arm64-v8a
prepare_arch x86_64 x86_64

printf '%s\n' "$NODE_RUNTIME_VERSION" > "$OUT_ROOT/assets/runtime/node/node-version.txt"
cat > "$OUT_ROOT/assets/runtime/node/README.txt" <<EOF
Bundled Node.js runtime
Node.js: $NODE_RUNTIME_VERSION
Source packages: Termux termux-main, signature verified with $TERMUX_KEY_FINGERPRINT
Executable: APK native library libdsh_node.so
Runtime shared libraries: extracted as data and loaded through LD_LIBRARY_PATH
EOF

echo "Node runtime prepared: $NODE_RUNTIME_VERSION"
