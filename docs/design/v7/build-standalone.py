#!/usr/bin/env python3
"""Rebuild the V7 offline mobile design page from the checked-in editable sources.

Sources (single source of truth):
  docs/design/v7/tokens.json          -> 令牌：颜色 / 几何 / 排版 / 动效
  docs/design/v7/mobile/index.html    -> 可编辑源页
  docs/design/v7/mobile/v7.css        -> 样式
  docs/design/v7/mobile/v7.js         -> 页面与交互逻辑
  docs/design/v6/assets/**.svg        -> 只读复用的图标 / 人物形象 / 文件图标（内联进单文件）

Output:
  docs/design/v7/mobile/app.html      -> 单文件离线稿（内联 CSS / JS / 令牌 / 素材）

`--check` fails when the committed app.html drifts from the editable sources, or when
the token snapshot inside v7.js drifts from tokens.json.
"""
import argparse
import base64
import json
import re
from pathlib import Path

R = Path(__file__).resolve().parent
S = R / "mobile"
ASSET_ROOT = R.parent / "v6" / "assets"
ASSET_DIRS = (
    "icons",
    "icons-actions",
    "icons-misc",
    "icons-legacy-redrawn",
    "icons-supplemental",
    "portraits",
    "file-icons",
)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true", help="Fail when the compiled offline page differs from the editable source")
args = parser.parse_args()

# --- 1. 令牌：唯一来源 tokens.json ------------------------------------------------
tokens = json.loads((R / "tokens.json").read_text(encoding="utf-8"))
for key in ("themes", "type", "geometry", "surfaces", "overlayVariants", "motion", "primitives", "semantic", "components"):
    if key not in tokens:
        parser.error(f"tokens.json is missing '{key}'")
if not tokens["primitives"].get("accentCards"):
    parser.error("tokens.json is missing 'primitives.accentCards'")
for theme in ("light", "dark", "matte"):
    if theme not in tokens["themes"]:
        parser.error(f"tokens.json is missing theme '{theme}'")

html = (S / "index.html").read_text(encoding="utf-8")
css = (S / "v7.css").read_text(encoding="utf-8")
js = (S / "v7.js").read_text(encoding="utf-8")

# --- 2. v7.js 内的令牌快照必须与 tokens.json 一致（单一事实源 + CI 校验）------------
snapshot_match = re.search(r"^const V7_TOKEN_SNAPSHOT = (\{.*\});$", js, re.M)
if not snapshot_match:
    parser.error("v7.js has no single-line V7_TOKEN_SNAPSHOT")
if json.loads(snapshot_match.group(1)) != tokens:
    parser.error("v7.js token snapshot drifted from tokens.json; regenerate the snapshot")

# --- 3. 只读复用 v6 素材，内联为 data URI ----------------------------------------
assets = {}
for sub in ASSET_DIRS:
    folder = ASSET_ROOT / sub
    if not folder.is_dir():
        parser.error(f"missing asset folder {folder}")
    for path in sorted(folder.glob("*.svg")):
        payload = base64.b64encode(path.read_bytes()).decode("ascii")
        assets[f"{sub}/{path.name}"] = "data:image/svg+xml;base64," + payload

referenced = set(re.findall(r"'((?:icons|icons-actions|icons-misc|icons-legacy-redrawn|icons-supplemental|portraits|file-icons)/[A-Za-z0-9_\-]+\.svg)'", js))
missing = sorted(ref for ref in referenced if ref not in assets)
if missing:
    parser.error("assets referenced by v7.js are missing: " + ", ".join(missing))
for name in sorted(set(re.findall(r"'(ic_ui_file[a-z]+)'", js))):
    for variant in ("light", "dark"):
        key = f"file-icons/{name}_{variant}.svg"
        if key not in assets:
            parser.error(f"file icon variant missing: {key}")
for index in range(1, 5):
    key = f"portraits/persona-{index}.svg"
    if key not in assets:
        parser.error(f"portrait missing: {key}")

# --- 4. 内联编译 ------------------------------------------------------------------
boot = (
    "<script>\n"
    "window.V7_TOKENS=" + json.dumps(tokens, ensure_ascii=False) + ";\n"
    "window.V7_ASSETS=" + json.dumps(assets, ensure_ascii=False) + ";\n"
    "</script>"
)
html = html.replace(
    '<link rel="stylesheet" href="v7.css">',
    "<style>\n" + css + "\n</style>",
)
html = html.replace(
    '<script src="v7.js" defer></script>',
    boot + '<script>document.addEventListener("DOMContentLoaded",function(){\n' + js + "\n});</script>",
)

target = S / "app.html"
if args.check:
    if not target.exists() or target.read_text(encoding="utf-8") != html:
        parser.error("V7 compiled offline page drifted from editable source; regenerate")
    print("V7 standalone source parity passed")
else:
    target.write_text(html, encoding="utf-8")
    print("Generated", target, len(html), "characters")
