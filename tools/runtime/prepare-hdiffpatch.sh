#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="${1:?usage: prepare-hdiffpatch.sh <generated-output-dir>}"
RUNTIME_ABIS="${DSH_RUNTIME_ABIS:-arm64-v8a}"
CACHE_DIR="${HDIFFPATCH_CACHE:-.gradle/runtime-cache/hdiffpatch}"

VERSION="5.1.3"
ARCHIVE="hdiffpatch_v${VERSION}_sdk_android_hpatchz.zip"
URL="https://github.com/sisong/HDiffPatch/releases/download/v${VERSION}/${ARCHIVE}"
SHA256="d7b98bcd3efb05436e08bcf525136c81e7dc6713bc58260770a2793818167fb7"

mkdir -p "$CACHE_DIR"
ZIP_PATH="$CACHE_DIR/$ARCHIVE"

verify_archive() {
  [ -f "$ZIP_PATH" ] &&
    printf '%s  %s\n' "$SHA256" "$ZIP_PATH" | sha256sum -c - >/dev/null 2>&1
}

if ! verify_archive; then
  rm -f "$ZIP_PATH"
  curl --fail --location --retry 3 --retry-delay 2 "$URL" --output "$ZIP_PATH"
  printf '%s  %s\n' "$SHA256" "$ZIP_PATH" | sha256sum -c -
fi

UNPACK_DIR="$CACHE_DIR/unpacked-v$VERSION"
rm -rf "$UNPACK_DIR"
mkdir -p "$UNPACK_DIR"
unzip -q "$ZIP_PATH" -d "$UNPACK_DIR"

rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT/jniLibs"

IFS=',' read -r -a abis <<< "$RUNTIME_ABIS"
for raw_abi in "${abis[@]}"; do
  abi="$(printf '%s' "$raw_abi" | xargs)"
  case "$abi" in
    arm64-v8a|x86_64) ;;
    *)
      echo "unsupported HDiffPatch ABI: $abi" >&2
      exit 1
      ;;
  esac

  src="$(find "$UNPACK_DIR" -type f -name 'libhpatchz.so' -path "*/$abi/*" -print -quit)"
  if [ -z "$src" ]; then
    echo "libhpatchz.so not found for ABI $abi" >&2
    find "$UNPACK_DIR" -type f -name 'libhpatchz.so' -print >&2 || true
    exit 1
  fi

  mkdir -p "$OUT_ROOT/jniLibs/$abi"
  cp "$src" "$OUT_ROOT/jniLibs/$abi/libhpatchz.so"
done
