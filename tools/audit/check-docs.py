#!/usr/bin/env python3
"""核查现行 777 文档链接与 V6 能力中心设计源。"""
from pathlib import Path
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
CURRENT = (
    "README.md",
    "docs/README.md",
    "docs/UI-UX.zh-CN.md",
    "docs/design/mobile-ui.md",
    "docs/design/v6/README.zh-CN.md",
    "docs/design/v6/STATUS.zh-CN.md",
    "docs/design/v6/V6-CURRENT-IMPLEMENTATION.zh-CN.md",
    "docs/design/v6/V6-IMPLEMENTED-INTERACTION-CONTRACT.zh-CN.md",
)
LINK = re.compile(r"!?\[[^\]]+\]\(([^)]+)\)")
errors = []
for relative in CURRENT:
    source = ROOT / relative
    text = source.read_text(encoding="utf-8")
    for raw in LINK.findall(text):
        target = raw.strip().split(" ", 1)[0].strip("<>")
        if target.startswith(("http:", "https:", "mailto:", "#")):
            continue
        target = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not target:
            continue
        resolved = (source.parent / target).resolve()
        if not resolved.is_relative_to(ROOT):
            errors.append(f"{relative}: 链接超出仓库: {raw}")
        elif not resolved.exists():
            errors.append(f"{relative}: 相对链接不存在: {raw}")

ui = (ROOT / "docs/design/v6/mobile/v6.js").read_text(encoding="utf-8")
standalone = (ROOT / "docs/design/v6/mobile/app.html").read_text(encoding="utf-8")
for retired in ("tool-search", "tool-tab:", "toolTab", "CodexOfficialPluginCatalog"):
    if retired in ui or retired in standalone:
        errors.append(f"V6 能力中心仍包含已退出的控件: {retired}")
for expected in ("['installed','已添加']", "['discover','发现']"):
    if expected not in ui:
        errors.append(f"V6 能力中心缺少当前视图: {expected}")
for retired_file in (
    "docs/audits/pr895-capability-current-20261114.md",
    "docs/design/777-extension-market-v24-codex-20261114.md",
):
    if (ROOT / retired_file).exists():
        errors.append(f"历史文件仍在当前文档目录: {retired_file}")

if errors:
    raise SystemExit("\n".join(errors))
print("现行文档链接与能力中心设计源检查通过")
