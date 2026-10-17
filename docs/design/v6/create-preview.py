#!/usr/bin/env python3
"""Generate V6 palette/material swatches from the actual Compose SyColors source.

This is an editable SVG palette reference, not wallpaper color sampling or device proof.
Keep file icon SVG originals in assets/file-icons; do not use historic white-glass sheets.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parent
repo = root.parents[2]
source = (repo / "ui-pack-v6/src/main/kotlin/com/shenyan/designsystem/foundation/ShenyanFoundation.kt").read_text(encoding="utf-8")
fields = ("canvas", "topBar", "text", "materialL1", "materialL2", "materialL3", "materialL4", "materialL5", "border")
def read_theme(name):
    m = re.search(r"private val " + name + r" = SyColors\((.*?)\n\)", source, re.S)
    if not m:
        raise RuntimeError("Missing Compose palette " + name)
    return {field: re.search(r"\b" + field + r"\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", m.group(1)).group(1).upper() for field in fields}
def rgba(value):
    return "#" + value[2:], round(int(value[:2], 16) / 255, 4)

lines = [
'<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">',
'<title>神言神语 V6 · 按原生 Compose 同源令牌生成的三主题材质与图标来源预览</title>',
'<rect width="1200" height="630" fill="#181615"/>',
'<text x="22" y="35" fill="#F5F0E8" font-size="22" font-family="sans-serif">V6 · 真实色彩与材质五层 · 2026-10-14</text>',
'<text x="22" y="60" fill="#B8AB9B" font-size="13" font-family="sans-serif">数据取自 ShenyanFoundation.kt；示意层不是 Android 真实模糊 / 壁纸混色截图</text>',
]
for i, name in enumerate(("Light", "Dark", "Matte")):
    f = read_theme(name)
    can, _ = rgba(f["canvas"])
    ink, _ = rgba(f["text"])
    edge, edge_a = rgba(f["border"])
    x = 18 + 395*i
    lines += [
        f'<g transform="translate({x},82)">',
        f'<rect x="0" y="0" width="382" height="531" rx="22" fill="{can}"/>',
        f'<rect x="15" y="15" width="352" height="58" rx="13" fill="none" stroke="{edge}" stroke-opacity="{edge_a}"/>',
        f'<text x="32" y="49" font-family="sans-serif" font-size="19" fill="{ink}" font-weight="600">{name}</text>',
        f'<text x="28" y="101" font-family="sans-serif" font-size="13" fill="{ink}">共用场景 / 无额外不透明顶栏</text>',
    ]
    for level in range(1, 6):
        val = f["materialL" + str(level)]
        fill, alpha = rgba(val)
        y = 113 + level*60
        lines += [
            f'<rect x="22" y="{y}" width="338" height="46" rx="13" fill="{fill}" fill-opacity="{alpha}" stroke="{edge}" stroke-opacity="{edge_a}"/>',
            f'<text x="40" y="{y+29}" font-family="sans-serif" font-size="14" fill="{ink}">L{level} · {round(alpha*100)}% alpha · {val}</text>',
        ]
    lines += [
        f'<text x="23" y="470" font-family="sans-serif" font-size="12" fill="{ink}">文件图标源：assets/file-icons/</text>',
        f'<text x="23" y="495" font-family="sans-serif" font-size="12" fill="{ink}">当前入口图标源：sy-entry-icons.svg</text>',
        '</g>',
    ]
lines += ['</svg>']
(root / "assets/file-icons-preview.svg").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Generated palette/asset-source preview from Compose ShenyanFoundation.kt")
