#!/usr/bin/env python3
"""从可编辑源编译 V9 单文件设计稿。

来源（单一事实源）：
  docs/design/v9/tokens.json          令牌（经 resolve-tokens.py 解析为 CSS 变量）
  docs/design/v9/mobile/index.html    可编辑源页
  docs/design/v8/mobile/v9.css        样式
  docs/design/v8/mobile/v9.js         交互
  docs/design/v6/assets/**.svg        只读复用：品牌纸墨图标 / 动作图标 / 肖像 / 文件图标

输出：
  docs/design/v9/mobile/app.html      单文件离线稿（内联 CSS / JS / 令牌 / 素材）

`--check` 在编译产物与可编辑源不一致时失败，供 CI 校验。
"""
import argparse
import base64
import json
import subprocess
import sys
from pathlib import Path

R = Path(__file__).resolve().parent
S = R / "mobile"
ASSET_ROOT = R.parent / "v6" / "assets"
ASSET_DIRS = ("icons", "icons-actions", "icons-misc", "icons-supplemental", "portraits", "file-icons")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true", help="Fail when the compiled page drifts from the editable source")
args = parser.parse_args()

# --- 令牌：唯一来源 tokens.json，经 resolve-tokens.py 解析 --------------------------------
tokens = json.loads((R / "tokens.json").read_text(encoding="utf-8"))
resolver = R / "resolve-tokens.py"
css_vars = subprocess.run([sys.executable, str(resolver), "--css"], capture_output=True, text=True, check=True).stdout

html = (S / "index.html").read_text(encoding="utf-8")
css = (S / "v9.css").read_text(encoding="utf-8")
js = (S / "v9.js").read_text(encoding="utf-8")

# --- 素材：只读复用 v6 资产，内联为 data URI --------------------------------------------
assets = {}
for sub in ASSET_DIRS:
    folder = ASSET_ROOT / sub
    if not folder.is_dir():
        parser.error(f"missing asset folder {folder}")
    for path in sorted(folder.glob("*.svg")):
        payload = base64.b64encode(path.read_bytes()).decode("ascii")
        assets[f"{sub}/{path.name}"] = "data:image/svg+xml;base64," + payload

boot = (
    "<script>\n"
    "window.V9_TOKENS=" + json.dumps(tokens, ensure_ascii=False) + ";\n"
    "window.V9_ASSETS=" + json.dumps(assets, ensure_ascii=False) + ";\n"
    "</script>"
)

html = html.replace('<link rel="stylesheet" href="v9.css">', "<style>\n" + css + "\n</style>")
html = html.replace("</style>", "\n/* --- 令牌（由 tokens.json 解析） --- */\n" + css_vars + "\n</style>", 1)
html = html.replace('<script src="v9.js" defer></script>', boot + "<script>document.addEventListener('DOMContentLoaded',function(){\n" + js + "\n});</script>")

target = S / "app.html"
if args.check:
    if not target.exists() or target.read_text(encoding="utf-8") != html:
        parser.error("V9 compiled page drifted from editable source; regenerate")
    print("V9 standalone source parity passed")
else:
    target.write_text(html, encoding="utf-8")
    print(f"Generated {target} ({len(html)} characters, {len(assets)} assets)")
