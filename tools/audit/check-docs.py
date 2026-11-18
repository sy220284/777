#!/usr/bin/env python3
"""核查现行 777 文档链接与 V6 能力中心设计源。"""
from pathlib import Path
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
CURRENT = sorted(
    {*(ROOT.glob("*.md")),
     *(ROOT / "docs").rglob("*.md"),
     *(ROOT / "app/src/main/assets").rglob("*.md"),
     *(ROOT / "tools").rglob("README.md"),
     ROOT / "harness/README.md"}
)
LINK = re.compile(r"!?\[[^\]]+\]\(([^)]+)\)")
errors = []
for source in CURRENT:
    if not source.is_file():
        continue
    relative = source.relative_to(ROOT).as_posix()
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
    "docs/design/v6/V6.5-UI-UX-VIDEO-REMEDIATION-20261116.md",
):
    if (ROOT / retired_file).exists():
        errors.append(f"历史文件仍在当前文档目录: {retired_file}")

for path in (
    ROOT / "docs/design/v6/STATUS.zh-CN.md",
    ROOT / "docs/design/v6/V6-CURRENT-IMPLEMENTATION.zh-CN.md",
):
    if "## 历史附录：2026-10-15" in path.read_text(encoding="utf-8"):
        errors.append(f"{path.relative_to(ROOT)}: 旧审计附录重新进入当前规范")

if errors:
    raise SystemExit("\n".join(errors))
print("现行文档链接与能力中心设计源检查通过")
