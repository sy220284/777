#!/usr/bin/env python3
"""Create an independently compiling UI Pack scaffold from validated semantic design tokens.
No scene is declared implemented until its developer adds a real renderer.
"""
import argparse
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def literal(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def token(value):
    if not isinstance(value, dict) or set(value) != {"kind", "value"}:
        raise ValueError("token requires exactly kind and value")
    kind, raw = value["kind"], value["value"]
    if kind == "color" and isinstance(raw, str) and re.fullmatch(r"#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?", raw):
        return f"UiDesignToken.Color({literal(raw)})"
    if isinstance(raw, bool):
        raise ValueError("numeric tokens cannot be booleans")
    if kind in ("dp", "sp") and isinstance(raw, (int, float)) and math.isfinite(raw) and 0 <= raw <= 3.4028235e38:
        return f"UiDesignToken.Dimension({float(raw)}f, UiDesignToken.Dimension.Unit.{kind.upper()})"
    if kind == "duration" and isinstance(raw, int) and 0 <= raw <= 9223372036854775807:
        return f"UiDesignToken.Duration({raw}L)"
    if kind == "font_weight" and isinstance(raw, int) and 1 <= raw <= 1000:
        return f"UiDesignToken.FontWeight({raw})"
    raise ValueError(f"unsupported or invalid token kind: {kind}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="lowercase pack identifier")
    parser.add_argument("--tokens", type=Path, required=True)
    parser.add_argument("--register", action="store_true", help="include the new module in settings.gradle.kts")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9_]*", args.name):
        parser.error("name must match [a-z][a-z0-9_]*")
    data = json.loads(args.tokens.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data) != {"version", "themes"} or data["version"] != 1:
        parser.error("tokens require version=1 and themes")
    themes = data["themes"]
    if not isinstance(themes, dict) or not themes:
        parser.error("themes must be a nonempty object")
    declarations = []
    for theme, roles in themes.items():
        if not isinstance(theme, str) or not theme.strip() or not isinstance(roles, dict) or not roles:
            parser.error("each named theme requires semantic roles")
        if any(not isinstance(role, str) or not role.strip() for role in roles):
            parser.error("roles cannot be blank")
        entries = ",\n".join(f"                {literal(role)} to {token(value)}" for role, value in roles.items())
        declarations.append(f"        {literal(theme)} to mapOf(\n{entries}\n        )")
    module = "ui-pack-" + args.name
    target = ROOT / module
    if target.exists():
        parser.error(f"module already exists: {module}")
    package = "com.labteto.dshmobile.uipack." + args.name
    class_name = "".join(part.capitalize() for part in args.name.split("_")) + "UiPack"
    kotlin = f'''package {package}

import com.labteto.dshmobile.uihost.ConversationUiPack
import com.labteto.dshmobile.uicontract.*

/** Implement Render and add only scenes backed by actual native code. */
abstract class {class_name} : ConversationUiPack {{
    final override val manifest = UiPackManifest(
        id = {literal(args.name)}, version = 1, minContractVersion = UI_CONTRACT_VERSION,
        maxContractVersion = UI_CONTRACT_VERSION, scenes = emptySet(), requiredCapabilities = emptySet(),
        windowClasses = setOf(UiWindowClass.COMPACT), accessibility = emptySet(), supportsDark = false,
    )
    val tokens = UiDesignTokenManifest(1, mapOf(
{",\n".join(declarations)}
    ))
}}
'''
    build = (ROOT / "ui-pack-next/build.gradle.kts").read_text(encoding="utf-8")
    build = build.replace("com.labteto.dshmobile.uipack.next", package)
    source = target / "src/main/kotlin" / Path(package.replace(".", "/"))
    source.mkdir(parents=True)
    (target / "build.gradle.kts").write_text(build, encoding="utf-8")
    (source / (class_name + ".kt")).write_text(kotlin, encoding="utf-8")
    (target / "README.md").write_text(
        "本模块仅生成 SDK 和设计 Token 入口，尚未实现任何 Scene。\n"
        "实现原生 Render、声明真实 Scene/能力后，才可接入 Host 兼容检查。\n"
        "缺失场景必须拒绝选择，不能调用 V6 页面兜底。\n", encoding="utf-8")
    if args.register:
        settings = ROOT / "settings.gradle.kts"
        settings.write_text(settings.read_text(encoding="utf-8").rstrip() + f'\ninclude(":{module}")\n', encoding="utf-8")
    print(target)


if __name__ == "__main__":
    main()
