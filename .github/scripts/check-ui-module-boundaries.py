#!/usr/bin/env python3
"""V6.3 原生 Android 页面边界：禁止恢复朝夕、UI 3.0 可插拔架构。"""
from pathlib import Path
import sys
import json

ROOT = Path(__file__).resolve().parents[2]
NATIVE_REQUIRED = (
    "app/src/main/java/com/labteto/dshmobile/ui/AppRoot.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/shenyan/ShenyanTheme.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/components/FeatherIcons.kt",
    "docs/design/v6/V6.3-ICON-SYSTEM.zh-CN.md",
)
RETIRED = (
    "ui-pack-zhaoxi",
    "ui-host",
    "ui-contract",
    "app/src/main/java/com/labteto/dshmobile/uicontract/UiPackManifest.kt",
    "app/src/main/java/com/labteto/dshmobile/uicontract/UiDesignTokens.kt",
    "ui-pack-v6",
    "app/src/main/java/com/labteto/dshmobile/ui/host",
    "docs/design/zhaoxi",
    "docs/design/ui3",
    "tools/ui/new-pack.py",
)
def violations() -> list[str]:
    errors = []
    for p in NATIVE_REQUIRED:
        if not (ROOT / p).is_file():
            errors.append("V6.3 native source missing: " + p)
    for p in RETIRED:
        if (ROOT / p).exists():
            errors.append("UI3/Zhaoxi architecture must not return: " + p)
    activity = (ROOT / "app/src/main/java/com/labteto/dshmobile/MainActivity.kt").read_text(encoding="utf-8")
    if "setContent {" not in activity or "AppRoot(" not in activity or "ZhaoxiApplicationRoot(" in activity:
        errors.append("Android entry must install the native V6 AppRoot")
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    app = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    if any(p in settings + app for p in ('include(":ui-', 'project(":ui-')):
        errors.append("UI3 Pack/Host/Contract module still registered")
    # Native V6 document handling must stay reachable, not merely leave its data DTOs around.
    documents = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local"
    screen = (documents / "LocalHarnessScreen.kt").read_text(encoding="utf-8")
    surface = (documents / "LocalConversationSurface.kt").read_text(encoding="utf-8")
    if "NativeV6DocumentsSheet(" not in screen or "NativeV6DocumentMessage(" not in surface:
        errors.append("V6 interactive document entry or inline message card is disconnected")
    if not all((documents / p).is_file() for p in (
        "NativeV6Documents.kt", "NativeV6DocumentCard.kt", "NativeV6DocumentsSheet.kt",
    )):
        errors.append("V6 native interactive document implementation is missing")
    # The auto index may retain removed files as a *historical delta* but must not
    # refer to deleted UI3 code as evidence for current, functional entries.
    source_index = json.loads((ROOT / "docs/FEATURE-SOURCE-INDEX.auto.json").read_text(encoding="utf-8"))
    if source_index.get("current_tree_outdated_source_count") != 0:
        errors.append("Current feature source index still has invalid production paths")
    retired_prefixes = ("ui-pack-zhaoxi/", "ui-host/", "ui-contract/")
    for item in source_index.get("production_file_fingerprints", []):
        if item.get("path", "").startswith(retired_prefixes):
            errors.append("Retired UI3 module incorrectly listed as current production source")
            break
    for p in ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionStorageManager.kt",
              "app/src/main/java/com/labteto/dshmobile/local/work/LocalAgentTeamRuntime.kt"):
        if not (ROOT / p).is_file(): errors.append("Recent business fix missing: " + p)
    return errors

if __name__ == "__main__":
    if "--self-test" in sys.argv:
        assert "ui-pack-zhaoxi" in RETIRED
        assert "ui-contract" in RETIRED
        assert any("AppRoot.kt" in p for p in NATIVE_REQUIRED)
        print("V6.3 native ownership self-test passed")
    else:
        errors = violations()
        for err in errors: print("::error::" + err)
        if errors: raise SystemExit(1)
        print("V6.3 native UI ownership and business source boundary passed")
