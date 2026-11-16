#!/usr/bin/env python3
"""V8 令牌解析与校验。

用法：
    python3 docs/design/v8/resolve-tokens.py           # 校验并打印对比度矩阵
    python3 docs/design/v8/resolve-tokens.py --css     # 输出解析后的 CSS 变量
    python3 docs/design/v8/resolve-tokens.py --json    # 机器可读结果

校验项：
1. semantic.color.<mode>.<role> 必须指向 primitives.color.<mode> 中存在的键
2. 所有引用型取值（"space.4" / "radius.card" / "shadow.card" / "typography.body" / "color.rule"）必须能解析
3. 三主题下正文类组合的 WCAG 对比度 ≥ 4.5:1
4. 强调色在正常态与按下态上的前景对比度 ≥ 4.5:1
5. 八张强调色卡在浅底与深底上的求解前景对比度 ≥ 4.5:1
6. components.* 与 semantic.overlayVariants.* 的浮层外观不得各写一份（互校）
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TOKENS = json.loads((ROOT / "tokens.json").read_text(encoding="utf-8"))

TEXT_ROLES = ("ink", "ink2", "ink3")
SURFACE_ROLES = ("canvas", "surface", "raised")
BODY_MIN = 4.5
PRIMITIVE_ROOTS = ("space", "radius", "fontSize", "fontWeight", "lineHeight", "shadow", "duration", "iconSizes", "blur", "touchTarget")


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


def resolve_path(path: str):
    """解析 "space.4" / "radius.card" / "color.rule" / "typography.body" 这类引用。"""
    parts = path.split(".")
    roots = [TOKENS]
    if parts[0] not in TOKENS:
        roots = [TOKENS["primitives"], TOKENS["semantic"], TOKENS["components"], TOKENS]
    for root in roots:
        node = root
        ok = True
        for part in parts:
            if not isinstance(node, dict) or part not in node:
                ok = False
                break
            node = node[part]
        if ok:
            return node
    return None


def resolve_deep(path: str):
    """解析引用链：color.* 是按主题的模板，其余引用可层层指向 primitives。"""
    if not isinstance(path, str):
        return path
    if path.startswith("color."):
        role = path.split(".", 1)[1]
        return TOKENS["semantic"]["color"]["light"].get(role)
    if "|" in path:
        return [resolve_deep(part) for part in path.split("|")]
    value = resolve_path(path)
    if isinstance(value, str) and value in ("none", "transparent", "inherit"):
        return value
    if isinstance(value, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", value):
        return resolve_deep(value)
    return value


def color_of(mode: str, role: str) -> str:
    primitives = TOKENS["primitives"]["color"][mode]
    key = TOKENS["semantic"]["color"][mode][role]
    if key not in primitives:
        raise KeyError(f"semantic.color.{mode}.{role} -> {key} 不存在")
    return primitives[key]


def check() -> tuple[list[str], list[dict]]:
    errors: list[str] = []
    matrix: list[dict] = []

    # 1) 语义色引用
    for mode in TOKENS["semantic"]["color"]:
        for role in TOKENS["semantic"]["color"][mode]:
            try:
                color_of(mode, role)
            except KeyError as exc:
                errors.append(str(exc))

    # 2) 引用可解析性：遍历所有字符串型取值
    def walk(node, path):
        if isinstance(node, dict):
            for key, value in node.items():
                walk(value, f"{path}.{key}" if path else key)
        elif isinstance(node, str):
            if "." not in node or node.startswith("#") or node.startswith("cubic"):
                return
            if resolve_deep(node) is None:
                errors.append(f"{path} 引用无法解析：{node}")

    for section in ("semantic", "components"):
        walk(TOKENS[section], section)

    # 3) 对比度
    for mode in TOKENS["semantic"]["color"]:
        for fg in TEXT_ROLES:
            for bg in SURFACE_ROLES:
                ratio = contrast(color_of(mode, fg), color_of(mode, bg))
                matrix.append({"mode": mode, "fg": fg, "bg": bg, "ratio": round(ratio, 2)})
                if ratio < BODY_MIN:
                    errors.append(f"{mode}: {fg} on {bg} 对比度 {ratio:.2f} < {BODY_MIN}")
        for bg in SURFACE_ROLES:
            ratio = contrast(color_of(mode, "accent"), color_of(mode, bg))
            matrix.append({"mode": mode, "fg": "accent", "bg": bg, "ratio": round(ratio, 2)})
            if ratio < BODY_MIN:
                errors.append(f"{mode}: accent on {bg} 对比度 {ratio:.2f} < {BODY_MIN}")
        for fg in ("ink", "ink2"):
            ratio = contrast(color_of(mode, fg), color_of(mode, "accentSoft"))
            matrix.append({"mode": mode, "fg": fg, "bg": "accentSoft", "ratio": round(ratio, 2)})
            if ratio < BODY_MIN:
                errors.append(f"{mode}: {fg} on accentSoft 对比度 {ratio:.2f} < {BODY_MIN}")
        for state in ("accent", "accentPressed"):
            ratio = contrast(color_of(mode, "onAccent"), color_of(mode, state))
            matrix.append({"mode": mode, "fg": "onAccent", "bg": state, "ratio": round(ratio, 2)})
            if ratio < BODY_MIN:
                errors.append(f"{mode}: onAccent on {state} 对比度 {ratio:.2f} < {BODY_MIN}")

    # 4) 八张强调色卡
    poles = TOKENS["primitives"]["color"]["poles"]
    for card in TOKENS["primitives"]["accentCards"]:
        for variant in ("light", "dark"):
            base = card[variant]
            best = max(((poles["light"], contrast(poles["light"], base)), (poles["dark"], contrast(poles["dark"], base))),
                       key=lambda pair: pair[1])
            matrix.append({"mode": f"card-{card['key']}-{variant}", "fg": best[0], "bg": base, "ratio": round(best[1], 2)})
            if best[1] < BODY_MIN:
                errors.append(f"色卡 {card['key']}({variant}) 前景 {best[0]} 对比度 {best[1]:.2f} < {BODY_MIN}")

    # 5) 浮层外观互校：components 必须复用 semantic.overlayVariants，不得各写一份
    for name in ("menu", "sheet", "dialog"):
        variant = TOKENS["semantic"]["overlayVariants"][name]
        component = TOKENS["components"][name]
        for prop in ("radius", "shadow"):
            if resolve_deep(component[prop]) != resolve_deep(variant[prop]):
                errors.append(f"components.{name}.{prop} 与 semantic.overlayVariants.{name}.{prop} 不一致")
    return errors, matrix


def css_variables() -> str:
    lines = [":root{"]
    for mode in ("light", "dark", "matte"):
        prefix = "" if mode == "light" else f"[data-theme='{mode}'] "
        for role in TOKENS["semantic"]["color"][mode]:
            lines.append(f"{prefix}--{role}:{color_of(mode, role)};")
        for role, value in TOKENS["primitives"]["persona"].items():
            hexv = value["light"] if mode == "light" else value["dark"]
            lines.append(f"{prefix}--persona-{role}:{hexv};")
    for key, value in TOKENS["primitives"]["space"].items():
        lines.append(f"--space-{key}:{value}px;")
    for key, value in TOKENS["primitives"]["radius"].items():
        lines.append(f"--radius-{key}:{value}px;")
    for key, value in TOKENS["primitives"]["fontSize"].items():
        lines.append(f"--fs-{key}:{value}px;")
    for key, value in TOKENS["primitives"]["lineHeight"].items():
        lines.append(f"--lh-{key}:{value}px;")
    for key, value in TOKENS["primitives"]["shadow"].items():
        lines.append(f"--shadow-{key}:{value};")
    for key, value in TOKENS["primitives"]["duration"].items():
        lines.append(f"--dur-{key}:{value}ms;")
    for key, value in TOKENS["primitives"]["iconSizes"].items():
        lines.append(f"--icon-{key}:{value}px;")
    lines.append(f"--touch:{TOKENS['primitives']['touchTarget']}px;")
    lines.append(f"--ease:{TOKENS['semantic']['motion']['easing']};")
    lines.append(f"--pressed-scale:{TOKENS['semantic']['motion']['pressedScale']};")
    lines.append("}")
    return "\n".join(lines)


def main() -> int:
    errors, matrix = check()
    if "--css" in sys.argv:
        print(css_variables())
        return 1 if errors else 0
    if "--json" in sys.argv:
        print(json.dumps({"ok": not errors, "errors": errors, "matrix": matrix}, ensure_ascii=False, indent=2))
    else:
        for row in matrix:
            print(f"{row['mode']:>24} | {row['fg']:>9} on {row['bg']:<10} {row['ratio']:>6}")
        if errors:
            print("\n失败：")
            for error in errors:
                print("  -", error)
        else:
            print(f"\n通过：{len(matrix)} 项对比度与结构校验全部达标")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
