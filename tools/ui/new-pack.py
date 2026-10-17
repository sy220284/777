#!/usr/bin/env python3
"""Scaffold a new UI Pack against the CURRENT full-application Host contract.

This script intentionally ships no visual theme, component, renderer or default UI.
A newly created pack is never production-admissible until real scenes are implemented.
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

def render(name, themes):
    module = "ui-pack-" + name
    package = "com.labteto.dshmobile.uipack." + name
    class_name = "".join(part.capitalize() for part in name.split("_")) + "UiPack"
    declarations = []
    for theme, roles in themes.items():
        if not isinstance(theme, str) or not theme.strip() or not isinstance(roles, dict) or not roles:
            raise ValueError("each named theme requires semantic roles")
        if any(not isinstance(role, str) or not role.strip() for role in roles):
            raise ValueError("roles cannot be blank")
        entries = ",\n".join(f"                {literal(role)} to {token(value)}" for role, value in roles.items())
        declarations.append(f"        {literal(theme)} to mapOf(\n{entries}\n        )")
    token_lines = ",\n".join(declarations)
    kotlin = f'''package {package}

import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.labteto.dshmobile.uicontract.*

/** Empty scaffold; a real renderer and its verified scene manifest are required for Host admission. */
class {class_name} : UiApplicationScenePack {{
    override val manifest = UiPackManifest(
        id = {literal(name)}, version = 1,
        minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
        scenes = emptySet(), requiredCapabilities = emptySet(),
        windowClasses = setOf(UiWindowClass.COMPACT),
        accessibility = emptySet(), supportsDark = false,
    )
    override val renderers: Map<UiSceneId, UiSceneRenderer> = emptyMap()
    val tokens = UiDesignTokenManifest(1, mapOf(
{token_lines}
    ))
}}
'''
    build = '''plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "''' + package + '''"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 36 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":ui-host"))
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.runtime:runtime")
}
'''
    return module, package, class_name, kotlin, build

def self_test():
    sample = {"default": {"accent": {"kind": "color", "value": "#123456"},
                          "duration": {"kind": "duration", "value": 180}}}
    module, _, _, source, build = render("fresh", sample)
    assert module == "ui-pack-fresh"
    assert ": UiApplicationScenePack" in source
    assert "override val renderers" in source and "emptyMap()" in source
    assert "UiDesignToken.Duration(180L)" in source
    assert 'project(":ui-host")' in build
    assert "ui-pack-next" not in source + build
    print("Fresh UI Pack scaffold matches full-app Host contract")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", nargs="?", help="lowercase pack identifier")
    parser.add_argument("--tokens", type=Path)
    parser.add_argument("--register", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.name or not re.fullmatch(r"[a-z][a-z0-9_]*", args.name):
        parser.error("name must match [a-z][a-z0-9_]*")
    if args.tokens is None:
        parser.error("--tokens is required")
    data = json.loads(args.tokens.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data) != {"version", "themes"} or data["version"] != 1:
        parser.error("tokens require version=1 and themes")
    if not isinstance(data["themes"], dict) or not data["themes"]:
        parser.error("themes must be a nonempty object")
    try:
        module, package, class_name, kotlin, build = render(args.name, data["themes"])
    except ValueError as err:
        parser.error(str(err))
    target = ROOT / module
    if target.exists():
        parser.error(f"module already exists: {module}")
    source = target / "src/main/kotlin" / Path(package.replace(".", "/"))
    source.mkdir(parents=True)
    (target / "build.gradle.kts").write_text(build, encoding="utf-8")
    (source / (class_name + ".kt")).write_text(kotlin, encoding="utf-8")
    (target / "README.md").write_text(
        "此模块仅含设计 Token 与全应用 Pack 接口，不含任何页面、组件或动效。\n"
        "完成全部真实 Scene Renderer 与 Feature Port 连接并通过 Host 完整准入后方可安装。\n",
        encoding="utf-8",
    )
    if args.register:
        settings = ROOT / "settings.gradle.kts"
        content = settings.read_text(encoding="utf-8").rstrip()
        settings.write_text(content + f'\ninclude(":{module}")\n', encoding="utf-8")
    print(target)

if __name__ == "__main__":
    main()
