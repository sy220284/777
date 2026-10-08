#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${1:?Expected generated resource directory}"
# fontTools is provisioned by GitHub Actions and shipped in the verified runtime-cache Artifact.
# Local offline builds must never resolve a missing Python dependency on the network.
PY_DIR="$REPO_ROOT/.gradle/runtime-cache/ui-fonttools-python"
VERSION='4.59.2'
fonttools_available() {
  PYTHONPATH="$PY_DIR" python3 -c '
import os, sys, fontTools
assert fontTools.__version__ == sys.argv[2]
assert os.path.realpath(fontTools.__file__).startswith(os.path.realpath(sys.argv[1]) + os.sep)
' "$PY_DIR" "$VERSION" >/dev/null 2>&1
}
if ! fonttools_available; then
  if [ "${GITHUB_ACTIONS:-false}" != "true" ] || [ "${DSH_FONT_OFFLINE:-false}" = "true" ]; then
    echo "缺少字体生成依赖 fontTools $VERSION；请从 main 的 GitHub Actions 下载并安装最新 runtime-cache Artifact。离线构建禁止外部下载。" >&2
    exit 1
  fi
  # Download is permitted only in the GitHub Actions artifact-generation/build environment.
  mkdir -p "$PY_DIR"
  python3 -m pip install --disable-pip-version-check --no-input --target "$PY_DIR" "fonttools==$VERSION"
  fonttools_available || { echo "GitHub Actions 字体工具准备失败" >&2; exit 1; }
fi
PYTHONPATH="$PY_DIR${PYTHONPATH:+:$PYTHONPATH}" python3 \
  "$REPO_ROOT/tools/fonts/build_ui_font.py" \
  --font "$REPO_ROOT/tools/fonts/source/NotoSansSC-wght.ttf" \
  --resources "$REPO_ROOT/app/src/main/res" \
  --ui "$REPO_ROOT/app/src/main/java/com/labteto/dshmobile/ui" \
  --out "$TARGET/font"
