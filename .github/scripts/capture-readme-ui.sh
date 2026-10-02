#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.sy220284.dshmobile.debug"
ACTIVITY="com.labteto.dshmobile.ui.screens.local.ReadmeScreenshotActivity"
OUTPUT_DIR="docs/images/current"

DSH_RUNTIME_ABIS=x86_64 ./gradlew :app:assembleDebug --stacktrace
adb install -r app/build/outputs/apk/debug/app-debug.apk

mkdir -p "$OUTPUT_DIR"

capture_screen() {
  local screen="$1"
  local output="$2"
  local ready=0

  adb logcat -c
  adb shell am force-stop "$PACKAGE"
  adb shell am start -W \
    -n "$PACKAGE/$ACTIVITY" \
    --es readme_screen "$screen"

  for attempt in $(seq 1 80); do
    if adb logcat -d -s ReadmeScreenshot:I '*:S' | grep -Fq "READY:$screen"; then
      ready=1
      break
    fi
    sleep 0.25
  done

  if [[ "$ready" -ne 1 ]]; then
    echo "::error::README 截图页面未就绪：$screen"
    adb logcat -d -s ReadmeScreenshot:I '*:S' || true
    return 1
  fi

  adb exec-out screencap -p > "$output"
  test -s "$output"
}

capture_screen navigation "$OUTPUT_DIR/navigation.png"
capture_screen chat "$OUTPUT_DIR/chat.png"
capture_screen work "$OUTPUT_DIR/work.png"
capture_screen character-tuning "$OUTPUT_DIR/character-tuning.png"
capture_screen usage "$OUTPUT_DIR/usage.png"

python3 - <<'PY'
from pathlib import Path
import struct

expected = {
    "navigation.png",
    "chat.png",
    "work.png",
    "character-tuning.png",
    "usage.png",
}
files = sorted(Path("docs/images/current").glob("*.png"))
assert {p.name for p in files} == expected

for path in files:
    data = path.read_bytes()
    assert data[:8] == bytes.fromhex("89504e470d0a1a0a"), f"{path} invalid PNG"
    width, height = struct.unpack(">II", data[16:24])
    assert width >= 300, f"{path} width={width}"
    assert height >= 500, f"{path} height={height}"
    assert len(data) > 10_000, f"{path} bytes={len(data)}"
PY
