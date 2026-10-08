#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-android16-apk.sh path/to/app.apk [expected-abi]}"
expected_abi="${2:-arm64-v8a}"
test -f "$apk"

build_tools_dir="$(find "${ANDROID_SDK_ROOT:?}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
badging="$("$build_tools_dir/aapt" dump badging "$apk")"
case "$badging" in
  *"sdkVersion:'36'"*) ;;
  *) echo "APK does not declare minSdk 36" >&2; exit 1 ;;
esac

"$build_tools_dir/zipalign" -c -P 16 -v 4 "$apk"

# Android refuses to install unsigned APKs. Treat signature validity as part of
# installability instead of allowing a release to look healthy while shipping
# app-release-unsigned.apk.
"$build_tools_dir/apksigner" verify --verbose --print-certs "$apk"

# Shipping APKs must contain exactly one architecture. Debug CI may pass x86_64
# explicitly, while optimized and release APKs default to arm64-v8a.
mapfile -t native_abis < <(
  unzip -Z1 "$apk" | awk -F/ '$1 == "lib" && NF > 2 { print $2 }' | sort -u
)
if [ "${#native_abis[@]}" -ne 1 ] || [ "${native_abis[0]}" != "$expected_abi" ]; then
  echo "APK ABI 不符合预期：期望仅有 $expected_abi，实际为 ${native_abis[*]:-无}" >&2
  exit 1
fi

for runtime in node python git; do
  mapfile -t runtime_abis < <(
    unzip -Z1 "$apk" | awk -F/ -v runtime="$runtime" '
      $1 == "assets" && $2 == "runtime" && $3 == runtime &&
      ($4 == "arm64-v8a" || $4 == "x86_64") { print $4 }
    ' | sort -u
  )
  if [ "${#runtime_abis[@]}" -ne 1 ] || [ "${runtime_abis[0]}" != "$expected_abi" ]; then
    echo "$runtime 运行时 ABI 不符合预期：期望仅有 $expected_abi，实际为 ${runtime_abis[*]:-无}" >&2
    exit 1
  fi
done

python3 .github/scripts/check-apk-runtime-layout.py "$apk" "$expected_abi"

# 检查最终 APK，避免 Termux 上游包升级时重新带入闲置资源。
python3 - "$apk" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    stale = [
        name for name in archive.namelist()
        if (name.startswith("assets/runtime/python/")
            and "/lib-dynload/_test" in name and name.endswith(".so"))
        or (name.startswith("assets/runtime/git/")
            and "/share/git-core/templates/hooks/" in name and name.endswith(".sample"))
        or name in {
            "assets/runtime/python/README.txt",
            "assets/runtime/git/README.txt",
            "assets/runtime/node/README.txt",
            "assets/persona-presets/README.md",
        }
    ]
if stale:
    raise SystemExit("APK 遗留无用测试/示例/说明文件：\n" + "\n".join(stale))
print("APK 闲置资源校验通过")
PY

# Python 3.14 contains real standard-library packages whose directory names begin
# with an underscore. AAPT normally strips <dir>_* from assets silently, which
# leaves bz2/gzip/lzma present while their shared compression._common package is
# absent. Assert on the final APK, after AAPT, rather than only on generated input.
if unzip -Z1 "$apk" | grep -qx 'assets/runtime/python/python-version.txt'; then
  python_version="$(unzip -p "$apk" assets/runtime/python/python-version.txt | tr -d '\r\n')"
  python_major_minor="$(printf '%s\n' "$python_version" | awk -F. '{print $1"."$2}')"
  base="assets/runtime/python/$expected_abi/home/lib/python$python_major_minor/compression/_common"
  unzip -Z1 "$apk" | grep -qx "$base/__init__.py" || {
    echo "APK lost Python underscore package: $base/__init__.py" >&2
    exit 1
  }
  unzip -Z1 "$apk" | grep -qx "$base/_streams.py" || {
    echo "APK lost Python underscore package: $base/_streams.py" >&2
    exit 1
  }
fi

case "$expected_abi" in
  arm64-v8a) expected_machine="AArch64" ;;
  x86_64) expected_machine="Advanced Micro Devices X86-64" ;;
  *) echo "Unsupported expected ABI: $expected_abi" >&2; exit 1 ;;
esac

elf_dir="$(mktemp -d)"
trap 'rm -rf "$elf_dir"' EXIT
unzip -qq "$apk" \
  "lib/$expected_abi/*" \
  "assets/runtime/*/$expected_abi/*" \
  "assets/runtime/shared/$expected_abi/lib/*" \
  -d "$elf_dir" || true

elf_count=0
while IFS= read -r -d '' library; do
  elf_magic="$(head -c 4 "$library" | od -An -tx1 | tr -d ' \n')"
  if [ "$elf_magic" != "7f454c46" ]; then
    continue
  fi
  elf_count=$((elf_count + 1))
  machine="$(readelf -hW "$library" | awk -F: '/Machine:/ { sub(/^[[:space:]]+/, "", $2); print $2; exit }')"
  if [ "$machine" != "$expected_machine" ]; then
    echo "ELF machine mismatch for $library: expected $expected_machine, got $machine" >&2
    exit 1
  fi
  while IFS= read -r alignment; do
    case "$alignment" in
      0x4000|0x8000|0x10000|0x20000|0x40000|0x80000|0x100000) ;;
      *) echo "Native library is not 16 KB ELF-aligned: $library ($alignment)" >&2; exit 1 ;;
    esac
  done < <(readelf -lW "$library" | awk '$1 == "LOAD" { print $NF }')
done < <(find "$elf_dir" -type f -print0)

if [ "$elf_count" -eq 0 ]; then
  echo "APK does not contain verifiable ELF payloads for $expected_abi" >&2
  exit 1
fi
