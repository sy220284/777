#!/usr/bin/env python3
"""校验 V7 设计令牌：三层结构一致性 + 对比度门槛。

用法：
    python3 docs/design/v7/check-tokens.py            # 校验并打印结果
    python3 docs/design/v7/check-tokens.py --json     # 机器可读输出（CI 使用）

校验项：
1. semantic.color.<mode>.<role> 指向的 primitives.color.<mode>.<key> 必须存在
2. themes.<mode>.<role> 的解析值必须与 primitives 一致（单一事实源）
3. 三主题下正文类组合的 WCAG 对比度 ≥ 4.5:1
4. 强调色在正常态与按下态上的前景对比度 ≥ 4.5:1
5. 八色卡在浅色底与深色底上的求解前景对比度 ≥ 4.5:1
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TOKENS = json.loads((ROOT / "tokens.json").read_text(encoding="utf-8"))

TEXT_ROLES = ("ink", "ink2", "ink3")
SURFACE_ROLES = ("canvas", "surface", "bar", "overlay", "solid")
BODY_MIN_RATIO = 4.5
NON_TEXT_MIN_RATIO = 3.0


def luminance(hex_color: str) -> float:
    value = hex_color.lstrip("#")
    channels = [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]

    def linear(c: float) -> float:
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (linear(c) for c in channels)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def resolve(mode: str, role: str) -> str:
    primitives = TOKENS["primitives"]["color"][mode]
    key = TOKENS["semantic"]["color"][mode][role]
    if key not in primitives:
        raise KeyError(f"semantic.color.{mode}.{role} -> {key} 在 primitives.color.{mode} 中不存在")
    return primitives[key]


def check() -> tuple[list[str], list[dict]]:
    errors: list[str] = []
    matrix: list[dict] = []

    for mode in TOKENS["semantic"]["color"]:
        resolved = {role: resolve(mode, role) for role in TOKENS["semantic"]["color"][mode]}
        for role, value in resolved.items():
            declared = TOKENS["themes"][mode].get(role)
            if declared is None:
                errors.append(f"themes.{mode}.{role} 缺失")
            elif declared.upper() != value.upper():
                errors.append(f"themes.{mode}.{role}={declared} 与 primitives 解析值 {value} 不一致")
        for fg in TEXT_ROLES:
            for bg in SURFACE_ROLES:
                ratio = contrast(resolved[fg], resolved[bg])
                matrix.append({"mode": mode, "fg": fg, "bg": bg, "ratio": round(ratio, 2)})
                if ratio < BODY_MIN_RATIO:
                    errors.append(f"{mode}: {fg} on {bg} 对比度 {ratio:.2f} < {BODY_MIN_RATIO}")
        for bg in SURFACE_ROLES:
            ratio = contrast(resolved["accent"], resolved[bg])
            matrix.append({"mode": mode, "fg": "accent", "bg": bg, "ratio": round(ratio, 2)})
            if ratio < BODY_MIN_RATIO:
                errors.append(f"{mode}: accent on {bg} 对比度 {ratio:.2f} < {BODY_MIN_RATIO}")
        for fg in ("ink", "ink2"):
            ratio = contrast(resolved[fg], resolved["accentSoft"])
            matrix.append({"mode": mode, "fg": fg, "bg": "accentSoft", "ratio": round(ratio, 2)})
            if ratio < BODY_MIN_RATIO:
                errors.append(f"{mode}: {fg} on accentSoft 对比度 {ratio:.2f} < {BODY_MIN_RATIO}")
        for state in ("accent", "accentPressed"):
            ratio = contrast(resolved["onAccent"], resolved[state])
            matrix.append({"mode": mode, "fg": "onAccent", "bg": state, "ratio": round(ratio, 2)})
            if ratio < BODY_MIN_RATIO:
                errors.append(f"{mode}: onAccent on {state} 对比度 {ratio:.2f} < {BODY_MIN_RATIO}")

    for card in TOKENS["primitives"]["accentCards"]:
        light_best = max(("#FFFFFF", contrast("#FFFFFF", card["light"])),
                         ("#1A1613", contrast("#1A1613", card["light"])), key=lambda p: p[1])
        dark_best = max(("#FFFFFF", contrast("#FFFFFF", card["dark"])),
                        ("#1A1613", contrast("#1A1613", card["dark"])), key=lambda p: p[1])
        for label, best in (("light", light_best), ("dark", dark_best)):
            matrix.append({"mode": f"card-{card['id']}-{label}", "fg": best[0], "bg": card[label],
                           "ratio": round(best[1], 2)})
            if best[1] < BODY_MIN_RATIO:
                errors.append(f"色卡 {card['id']}({label}) 前景 {best[0]} 对比度 {best[1]:.2f} < {BODY_MIN_RATIO}")
    return errors, matrix


def main() -> int:
    errors, matrix = check()
    if "--json" in sys.argv:
        print(json.dumps({"ok": not errors, "errors": errors, "matrix": matrix}, ensure_ascii=False, indent=2))
    else:
        for row in matrix:
            print(f"{row['mode']:>18} | {row['fg']:>9} on {row['bg']:<9} {row['ratio']:>6}")
        if errors:
            print("\n失败：")
            for error in errors:
                print("  -", error)
        else:
            print(f"\n通过：{len(matrix)} 项对比度与结构校验全部达标")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
