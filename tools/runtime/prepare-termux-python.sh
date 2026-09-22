#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="${1:?usage: prepare-termux-python.sh <generated-output-dir>}"
RUNTIME_ABIS="${DSH_RUNTIME_ABIS:-arm64-v8a}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE_DIR="${TERMUX_RUNTIME_CACHE:-$ROOT_DIR/.gradle/runtime-cache/termux-python}"
TERMUX_REPO="${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
TERMUX_KEY_COMMIT="93c8e0b136bf39e2eb1735f9187f43d7e029bb2e"
TERMUX_KEY_FINGERPRINT="CC72CF8BA7DBFA0182877D045A897D96E57CF20C"
PYTHON_PACKAGE="python"
PYTHON_PACKAGE_VERSION="3.14.6-1"
PYTHON_RUNTIME_VERSION="3.14.6"
PYTHON_MAJOR_MINOR="3.14"
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
mkdir -p "$OUT_ROOT/jniLibs" "$OUT_ROOT/assets/runtime/python/notices" "$CACHE_DIR"
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

resolve_runtime_packages() {
  local packages_file="$1"
  python3 - "$packages_file" "$PYTHON_PACKAGE" <<'PY'
import re, sys
path, root = sys.argv[1:3]
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

queue = [root]
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

patch_python_runtime() {
  local stdlib="$1"
  local subprocess_file="$stdlib/subprocess.py"
  local posixpath_file="$stdlib/posixpath.py"
  python3 - "$subprocess_file" "$posixpath_file" "$TERMUX_PREFIX" <<'PY'
import pathlib, sys
subprocess_path = pathlib.Path(sys.argv[1])
posixpath_path = pathlib.Path(sys.argv[2])
prefix = sys.argv[3]
subprocess_text = subprocess_path.read_text(encoding="utf-8")
needle = prefix + "/bin/sh"
if needle not in subprocess_text:
    raise SystemExit("Termux Python subprocess 默认 shell 路径未找到")
subprocess_path.write_text(
    subprocess_text.replace(needle, "/system/bin/sh"),
    encoding="utf-8",
)
if posixpath_path.is_file():
    text = posixpath_path.read_text(encoding="utf-8")
    text = text.replace(prefix + "/bin", "/system/bin")
    posixpath_path.write_text(text, encoding="utf-8")
print("patched Python subprocess shell and default path")
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

verify_dependency_closure() {
  local executable="$1"
  local lib_dir="$2"
  local stdlib_dir="$3"
  local available
  available="$(find "$lib_dir" -maxdepth 1 -type f -printf '%f\n' | sort -u)"
  local missing=0

  while IFS= read -r -d '' elf; do
    if ! head -c 4 "$elf" | grep -q $'\x7fELF'; then
      continue
    fi
    verify_elf_alignment "$elf"
    while IFS= read -r needed; do
      [ -z "$needed" ] && continue
      if printf '%s\n' "${SYSTEM_LIBS[@]}" | grep -Fxq "$needed"; then
        continue
      fi
      if ! printf '%s\n' "$available" | grep -Fxq "$needed"; then
        echo "Python ELF 缺少运行库：$needed（来源：$elf）" >&2
        missing=1
      fi
    done < <(readelf -dW "$elf" 2>/dev/null | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  done < <(
    printf '%s\0' "$executable"
    find "$lib_dir" -maxdepth 1 -type f -print0
    find "$stdlib_dir" -type f -name '*.so' -print0
  )
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

  local actual_python_version
  actual_python_version="$(package_field "$packages_file" "$PYTHON_PACKAGE" Version)"
  if [ "$actual_python_version" != "$PYTHON_PACKAGE_VERSION" ]; then
    echo "Python 版本漂移：期望 $PYTHON_PACKAGE_VERSION，实际 $actual_python_version" >&2
    exit 1
  fi

  mapfile -t runtime_packages < <(resolve_runtime_packages "$packages_file")
  [ "${#runtime_packages[@]}" -gt 0 ] || {
    echo "Python 运行时依赖解析为空：$apt_arch" >&2
    exit 1
  }

  local jni_dir="$OUT_ROOT/jniLibs/$android_abi"
  local asset_root="$OUT_ROOT/assets/runtime/python/$android_abi"
  local lib_dir="$asset_root/lib"
  local home_dir="$asset_root/home"
  local stdlib_dir="$home_dir/lib/python$PYTHON_MAJOR_MINOR"
  local manifest="$asset_root/packages.tsv"
  mkdir -p "$jni_dir" "$lib_dir" "$home_dir/lib"
  : > "$manifest"

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

    if [ "$pkg" = "$PYTHON_PACKAGE" ]; then
      local python_bin="$prefix/bin/python$PYTHON_MAJOR_MINOR"
      [ -f "$python_bin" ] || {
        echo "Termux Python 缺少 bin/python$PYTHON_MAJOR_MINOR" >&2
        exit 1
      }
      [ -d "$prefix/lib/python$PYTHON_MAJOR_MINOR" ] || {
        echo "Termux Python 缺少标准库 python$PYTHON_MAJOR_MINOR" >&2
        exit 1
      }
      cp -L "$python_bin" "$jni_dir/libdsh_python.so"
      chmod 0755 "$jni_dir/libdsh_python.so"
      cp -aL "$prefix/lib/python$PYTHON_MAJOR_MINOR" "$home_dir/lib/"

      # Termux may place the unversioned libpython link under the Python config
      # directory instead of directly under prefix/lib (notably on x86_64).
      # Extension modules DT_NEEDED that exact basename, while LD_LIBRARY_PATH
      # only contains our flat runtime lib directory, so collect every packaged
      # libpython variant there before dependency-closure verification.
      while IFS= read -r -d '' python_library; do
        local python_library_name
        python_library_name="$(basename "$python_library")"
        cp -L "$python_library" "$lib_dir/$python_library_name"
        chmod 0644 "$lib_dir/$python_library_name"
      done < <(
        find "$prefix/lib" \( -type f -o -type l \) \
          -name "libpython$PYTHON_MAJOR_MINOR.so*" -print0
      )

      patch_python_runtime "$stdlib_dir"
      # Patched stdlib source must be authoritative. Precompiled bytecode from the
      # Termux package can otherwise keep compiled-in Termux paths even after the
      # .py source has been rewritten for this app.
      find "$stdlib_dir" -type d -name "__pycache__" -prune -exec rm -rf {} +
      find "$stdlib_dir" -type f -name '*.pyc' -delete
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
    if [ -f "$prefix/etc/tls/openssl.cnf" ]; then
      mkdir -p "$home_dir/etc/tls"
      cp "$prefix/etc/tls/openssl.cnf" "$home_dir/etc/tls/openssl.cnf"
      chmod 0644 "$home_dir/etc/tls/openssl.cnf"
    fi

    if [ "$apt_arch" = "aarch64" ] && [ -f "$prefix/share/doc/$pkg/copyright" ]; then
      cp "$prefix/share/doc/$pkg/copyright" "$OUT_ROOT/assets/runtime/python/notices/$pkg.txt"
    fi
    printf '%s\t%s\t%s\t%s\n' "$pkg" "$version" "$filename" "$digest" >> "$manifest"
  done

  [ -f "$jni_dir/libdsh_python.so" ] || {
    echo "Python 可执行文件未生成：$android_abi" >&2
    exit 1
  }
  [ -f "$stdlib_dir/os.py" ] || {
    echo "Python 标准库未生成：$android_abi" >&2
    exit 1
  }
  [ -s "$home_dir/etc/tls/cert.pem" ] || {
    echo "Python CA 证书未生成：$android_abi" >&2
    exit 1
  }
  [ -s "$home_dir/etc/tls/openssl.cnf" ] || {
    echo "Python OpenSSL 配置未生成：$android_abi" >&2
    exit 1
  }
  if find "$stdlib_dir" -type f -name '*.pyc' -print -quit | grep -q .; then
    echo "Python 标准库仍残留预编译字节码：$android_abi" >&2
    exit 1
  fi

  verify_dependency_closure "$jni_dir/libdsh_python.so" "$lib_dir" "$stdlib_dir"
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

printf '%s\n' "$PYTHON_RUNTIME_VERSION" > "$OUT_ROOT/assets/runtime/python/python-version.txt"
cat > "$OUT_ROOT/assets/runtime/python/README.txt" <<EOF
Bundled Python runtime
Python: $PYTHON_RUNTIME_VERSION
ABIs: $RUNTIME_ABIS
Source packages: Termux termux-main, signature verified with $TERMUX_KEY_FINGERPRINT
Executable: APK native library libdsh_python.so
Standard library: assets/runtime/python/<abi>/home/lib/python$PYTHON_MAJOR_MINOR
Runtime shared libraries: assets/runtime/python/<abi>/lib
Default subprocess shell patched to /system/bin/sh for Android app execution
EOF

echo "Python runtime prepared: $PYTHON_RUNTIME_VERSION"
