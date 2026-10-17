#!/usr/bin/env python3
"""Verify the current UI 3.0 module dependency boundary and its source imports."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ALLOWED = {
    "ui-contract": set(),
    "ui-host": {"ui-contract"},
    "ui-pack-v6": {"ui-host", "ui-contract"},
}
PROJECT = re.compile(r'project\s*\(\s*"(?::)([A-Za-z0-9_-]+)"\s*\)')
IMPORT = re.compile(r'^\s*import\s+([A-Za-z0-9_.]+)', re.M)
# UI Contract deliberately exports immutable DTOs from historical local.* namespaces.
# Gradle edges forbid Pack -> App; importing exported DTOs is fully legitimate.
# Reject actual App screen imports and internal runtime/owner implementation symbols.
APP_INTERNAL = (
    "com.labteto.dshmobile.ui.screens.",
    "com.labteto.dshmobile.ui.shenyan.",
    "com.labteto.dshmobile.ui.theme.",
)
APP_IMPL_SYMBOLS = {
    "LocalHarnessViewModel", "LocalFeatureUiActions", "LocalRuntimeKernel",
    "LocalSessionRepository", "LocalAgentRunCoordinator",
    "LocalToolExecutionCoordinator",
}

def project_errors(module: str, content: str) -> list[str]:
    actual = set(PROJECT.findall(content))
    allowed = ALLOWED[module]
    return ([f"{module}: invalid project dependency {name}" for name in sorted(actual - allowed)]
            + [f"{module}: missing required dependency {name}" for name in sorted(allowed - actual)])

def import_errors(module: str, content: str) -> list[str]:
    errors = []
    for name in IMPORT.findall(content):
        forbidden = (
            name.startswith(("android.", "androidx.", "com.shenyan.designsystem.", "com.labteto.dshmobile.uipack."))
            if module == "ui-contract" else
            (name.startswith(APP_INTERNAL) or name.rsplit(".", 1)[-1] in APP_IMPL_SYMBOLS) or
            (module == "ui-host" and name.startswith(("com.shenyan.designsystem.", "com.labteto.dshmobile.uipack."))) or
            (module == "ui-pack-v6" and name.startswith("com.labteto.dshmobile.uipack.") and
             not name.startswith("com.labteto.dshmobile.uipack.v6."))
        )
        if forbidden:
            errors.append(f"{module}: forbidden import {name}")
    return errors

def self_test() -> None:
    assert not project_errors("ui-host", 'api(project(":ui-contract"))')
    assert project_errors("ui-host", 'api(project(":ui-contract")); implementation(project(":app"))')
    assert project_errors("ui-host", 'api(project(":ui-pack-v6"))')
    assert project_errors("ui-pack-v6", 'implementation(project(":ui-host"))')
    assert import_errors("ui-pack-v6", "import com.labteto.dshmobile.local.session.LocalSessionRepository")
    assert import_errors("ui-host", "import com.labteto.dshmobile.uipack.v6.V6Scene")
    assert import_errors("ui-contract", "import androidx.compose.runtime.Composable")
    assert not import_errors("ui-contract", "import kotlinx.coroutines.flow.Flow")
    assert not import_errors("ui-pack-v6", "import com.shenyan.designsystem.components.SyEntrance")
    assert not import_errors("ui-pack-v6", "import com.labteto.dshmobile.local.session.LocalConversationMode")
    assert not import_errors("ui-pack-v6", "import com.labteto.dshmobile.local.model.LocalMediaPage")

def main() -> None:
    errors = []
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    app = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    for module in ALLOWED:
        if f'include(":{module}")' not in settings:
            errors.append(f"{module}: not declared in settings.gradle.kts")
        if module not in PROJECT.findall(app):
            errors.append(f"{module}: app does not assemble this UI module")
        build = ROOT / module / "build.gradle.kts"
        if not build.is_file():
            errors.append(f"{module}: missing build.gradle.kts")
            continue
        errors += project_errors(module, build.read_text(encoding="utf-8"))
        source_root = ROOT / module / "src/main"
        if not source_root.is_dir():
            errors.append(f"{module}: missing production sources")
            continue
        for source in source_root.rglob("*.kt"):
            errors += [f"{source.relative_to(ROOT)}: {error}"
                       for error in import_errors(module, source.read_text(encoding="utf-8"))]
    if errors:
        for issue in errors:
            print("::error::" + issue)
        raise SystemExit(1)
    print("UI 3.0 independent module/Owner dependency gate passed")

if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        print("UI 3.0 dependency negative cases passed")
    else:
        main()
