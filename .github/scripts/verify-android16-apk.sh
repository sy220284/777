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

native_dir="$(mktemp -d)"
trap 'rm -rf "$native_dir"' EXIT
unzip -qq "$apk" 'lib/*/*.so' -d "$native_dir" || true
while IFS= read -r -d '' library; do
  while IFS= read -r alignment; do
    case "$alignment" in
      0x4000|0x8000|0x10000|0x20000|0x40000|0x80000|0x100000) ;;
      *) echo "Native library is not 16 KB ELF-aligned: $library ($alignment)" >&2; exit 1 ;;
    esac
  done < <(readelf -lW "$library" | awk '$1 == "LOAD" { print $NF }')
done < <(find "$native_dir" -type f -name '*.so' -print0)
