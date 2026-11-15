#!/usr/bin/env python3
"""Architecture-3 App Server contract and complete interface inventory. Run in CI on each change."""
from pathlib import Path
import json
import re

root = Path(__file__).resolve().parents[2]
source = root / "app/src/main/java/com/labteto/dshmobile"
contracts = source / "uicontract"
local = source / "local"
work = local / "LocalAppServer.kt"
protocol = contracts / "AppServerUiPort.kt"
discovery = local / "tools/LocalCapabilityDiscovery.kt"
coordination = local / "LocalToolExecutionCoordinator.kt"
tools_root = local / "LocalToolCompositionRoot.kt"
tool_ui = local / "presentation/LocalToolsUiFacade.kt"
feature_catalog = local / "feature/LocalFeatureCatalog.kt"

def read(path):
    assert path.is_file(), f"Missing required contract: {path}"
    return path.read_text(encoding="utf-8")

api = read(protocol)
implementation = read(work)
catalog = read(feature_catalog)
tool_logic = read(discovery)
execution = read(coordination)
composition = read(tools_root)
tools_ui = read(tool_ui)

features = ("SHELL", "CHAT", "WORK", "AUTOMATION", "TOOLS", "SETTINGS")
commands = ("Chat", "Work", "Session", "Agent", "Automation", "Tools", "Settings")
endpoints = ("chat", "work", "session", "agents", "automation", "tools", "settings", "workRead")
assert all(re.search(r"\b" + name + r"\b", catalog) for name in features)
assert all(re.search(r"\b" + name + r"\b", api) for name in features)
assert "LocalFeatureCatalog.modules.map" in implementation, "App Server must use the one FeatureCatalog"
assert "UI_CONTRACT_VERSION" in implementation
for name in commands:
    assert "data class " + name + "(val command:" in api, f"Request absent: {name}"
    assert "data class " + name + "(val receipt:" in api, f"Reply absent: {name}"
    assert "is UiAppServerCommand." + name + " ->" in implementation, f"Handler absent: {name}"
for name in endpoints:
    assert re.search(r"\bval " + name + r":", api), f"Missing typed owner: {name}"
    assert ("." + name) in (read(source / "ui/screens/local/LocalHarnessViewModel.kt") +
        read(source / "ui/screens/tools/ToolsScreen.kt") +
        read(source / "ui/screens/tasks/TasksViewModel.kt") + implementation), f"No client access: {name}"

assert implementation.count(": AppServerUiPort") == 1
assert "abstract fun bind(implementation: LocalAppServer): AppServerUiPort" in implementation
assert "LocalToolRouter.search(" in tool_logic
assert "LocalCapabilityDiscovery.resolve(tools, query)" in execution
for method in ("catalogue(", "searchCatalogue(", "deviceHints("):
    assert "LocalCapabilityDiscovery." + method in composition, f"Tool registry path diverges: {method}"
assert "tools.discoverCapabilities(query, limit)" in tools_ui
assert "localTools.discoverCapabilities(query, Int.MAX_VALUE)" in read(source / "ui/screens/tools/ToolsScreen.kt")
assert "appServer.tools" in read(source / "ui/screens/tools/ToolsScreen.kt")
assert "appServer.automation" in read(source / "ui/screens/tasks/TasksViewModel.kt")
assert "appServer.agents" in read(source / "ui/screens/local/LocalHarnessViewModel.kt")

# All actual UI contracts are inspected; potential unused ports are reported, never deleted from names alone.
kotlin = list(source.rglob("*.kt"))
ui_contracts = []
for path in sorted(contracts.glob("*.kt")):
    for match in re.finditer(r"\binterface\s+(\w+)", read(path)):
        name = match.group(1)
        consumers = [
            str(p.relative_to(root)) for p in kotlin
            if p != path and re.search(r"\b" + re.escape(name) + r"\b", read(p))
        ]
        ui_contracts.append({"name": name, "source": str(path.relative_to(root)),
                             "consumer_count": len(consumers), "consumers": consumers})
names = [entry["name"] for entry in ui_contracts]
assert len(names) == len(set(names)), "Duplicate UI contract interface declaration"
assert all(name in names for name in ("AppServerUiPort", "ChatUiPort", "WorkConversationUiPort",
    "SessionUiPort", "AutomationUiPort", "ToolsManagementUiPort", "SettingsUiPort", "WorkAgentUiPort"))
out = root / "build/app-server-audit"
out.mkdir(parents=True, exist_ok=True)
(out / "interface-inventory.json").write_text(json.dumps({
    "features": features, "commands": commands, "typed_endpoints": endpoints,
    "ui_interfaces_count": len(names),
    "ui_interfaces": ui_contracts,
}, indent=2, ensure_ascii=False), encoding="utf-8")
print("APP_SERVER_CONTRACT_OK features=" + str(len(features)) +
      " commands=" + str(len(commands)) + " ui_interfaces=" + str(len(names)))
