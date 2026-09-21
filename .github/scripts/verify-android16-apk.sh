#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-android16-apk.sh path/to/app.apk}"
test -f "$apk"

build_tools_dir="$(find "${ANDROID_SDK_ROOT:?}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
badging="$("$build_tools_dir/aapt" dump badging "$apk")"
case "$badging" in
  *"sdkVersion:'36'"*) ;;
  *) echo "APK does not declare minSdk 36" >&2; exit 1 ;;
esac

"$build_tools_dir/zipalign" -c -P 16 -v 4 "$apk"

native_dir="$(mktemp -d)"
trap 'rm -rf "$native_dir"' EXIT
unzip -qq "$apk" 'lib/*/*.so' -d "$native_dir" || true
while IFS= read -r -d '' library; do
  if ! readelf -lW "$library" | awk '$1 == "LOAD" && ($NF + 0) < 16384 { bad = 1 } END { exit bad }'; then
    echo "Native library is not 16 KB ELF-aligned: $library" >&2
    exit 1
  fi
done < <(find "$native_dir" -type f -name '*.so' -print0)
