#!/usr/bin/env python3
"""E8.17 UI boundary: reject retired V6 visuals and enforce independent pack wiring."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RETIRED = (
    "ui-pack-v6",
    "app/src/main/java/com/labteto/dshmobile/ui/screens",
    "app/src/main/java/com/labteto/dshmobile/ui/components",
    "app/src/main/java/com/labteto/dshmobile/ui/theme",
    "app/src/main/java/com/labteto/dshmobile/ui/shenyan",
    "app/src/main/res/font",
    "app/src/main/res/drawable",
    "app/src/main/res/drawable-nodpi",
    "app/src/main/res/mipmap",
    "app/src/main/res/raw",
    "app/src/main/res/anim",
    "app/src/main/res/animator",
    "app/src/main/res/color",
    "app/src/main/res/layout",
    "app/src/main/res/values/strings_capability_center.xml",
    "app/src/main/res/values/strings_persona_gallery_v3.xml",
    "app/src/main/res/values/persona_ai_strings.xml",
    "app/src/main/res/values/audit_repair_strings.xml",
    "docs/design/v6",
    "app/src/debug/AndroidManifest.xml",
)
REQUIRED = (
    "ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/UiPackManifest.kt",
    "ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiApplicationScenePack.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalFeatureExecutionPortModule.kt",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/host/LocalApplicationFeaturePortsOwner.kt",
)
def violations() -> list[str]:
    errors = []
    for path in RETIRED:
        if (ROOT / path).exists(): errors.append("retired UI restored: " + path)
    for path in REQUIRED:
        if not (ROOT / path).is_file(): errors.append("backend/interface missing: " + path)
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    app = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    if "ui-pack-v6" in settings + app: errors.append("V6 module still participates in production build")
    if 'include(":ui-contract")' not in settings or 'include(":ui-host")' not in settings:
        errors.append("neutral UI Contract/Host missing")
    for name in ("ui-contract", "ui-host"):
        build = (ROOT / name / "build.gradle.kts").read_text(encoding="utf-8")
        if re.search(r'project\s*\(\s*"(?::)(?:app|ui-pack-v6)"',build):
            errors.append(name + " depends on app or V6")
    owner = (ROOT / "app/src/main/java/com/labteto/dshmobile/ui/host/LocalApplicationFeaturePortsOwner.kt").read_text(encoding="utf-8")
    if any(x in owner for x in ("V6ApplicationScenePack", "V6SecondaryPorts", "V6VisualFrame")):
        errors.append("neutral Feature Port owner references deleted V6 UI")
    for symbol in ("ChatUiPort", "WorkConversationUiPort", "ToolsUiPort", "SessionUiPort"):
        if symbol not in owner: errors.append("neutral owner lost port: " + symbol)
    activity = (ROOT / "app/src/main/java/com/labteto/dshmobile/MainActivity.kt").read_text(encoding="utf-8")
    if any(s in activity for s in ("AppRoot(", "DshTheme(", "V6ApplicationScenePack(")):
        errors.append("MainActivity renders retired V6 interface")
    if "setContent {" not in activity or "ZhaoxiApplicationRoot(" not in activity:
        errors.append("E8.17 UI must be composed only from the app entrypoint")
    if 'include(":ui-pack-zhaoxi")' not in settings or 'project(":ui-pack-zhaoxi")' not in app:
        errors.append("New Zhaoxi UI pack not registered in application build")
    if not (ROOT / "ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt").is_file():
        errors.append("New Zhaoxi UI scene pack is missing")
    resources = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
    if resources.count('<string name=') > 40:
        errors.append("old UI copy has returned to base Android resources")
    generator = (ROOT / "tools/ui/new-pack.py").read_text(encoding="utf-8")
    if 'ui-pack-next/build.gradle.kts' in generator or 'ConversationUiPack' in generator:
        errors.append("UI Pack scaffold still uses a deleted or old contract")
    for dir in ("app/src/main/java", "ui-contract/src/main", "ui-host/src/main"):
        for file in (ROOT / dir).rglob("*.kt"):
            source = file.read_text(encoding="utf-8")
            if re.search(r'^\s*import\s+com\.labteto\.dshmobile\.uipack\.v6\.', source, re.MULTILINE):
                errors.append("live V6 import: " + str(file.relative_to(ROOT)))
    return errors

def self_test():
    assert "ui-pack-v6" in RETIRED
    assert "ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/UiPackManifest.kt" in REQUIRED
    assert re.search(r'project\s*\(\s*"(?::)ui-pack-v6"', 'implementation(project(":ui-pack-v6"))')

if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        print("Backend-only boundary negative checks passed")
    else:
        errors = violations()
        for error in errors: print("::error::" + error)
        if errors: raise SystemExit(1)
        print("Backend-only boundary passed; no retired UI, Feature Ports and neutral Host preserved")
