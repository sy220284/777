#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${1:?Expected generated resource directory}"
PY_DIR="$REPO_ROOT/.gradle/ui-fonttools-python"
VERSION='4.59.2'
if ! PYTHONPATH="$PY_DIR${PYTHONPATH:+:$PYTHONPATH}" python3 -c "import fontTools; assert fontTools.__version__ == '$VERSION'" 2>/dev/null; then
  mkdir -p "$PY_DIR"
  python3 -m pip install --disable-pip-version-check --no-input --target "$PY_DIR" "fonttools==$VERSION"
fi
PYTHONPATH="$PY_DIR${PYTHONPATH:+:$PYTHONPATH}" python3 \
  "$REPO_ROOT/tools/fonts/build_ui_font.py" \
  --font "$REPO_ROOT/tools/fonts/source/NotoSansSC-wght.ttf" \
  --resources "$REPO_ROOT/app/src/main/res" \
  --ui "$REPO_ROOT/app/src/main/java/com/labteto/dshmobile/ui" \
  --out "$TARGET/font"
