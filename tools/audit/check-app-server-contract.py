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
commands = ("Chat", "Work", "Session", "Agent", "Automation", "Tools", "Settings", "Queue", "Media", "Memory")
endpoints = ("chat", "work", "session", "agents", "automation", "tools", "settings", "workRead",
             "files", "attachments", "queuedUsers", "media", "usage", "memory", "pricing")
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
        read(source / "ui/screens/tasks/TasksViewModel.kt") +
        read(source / "ui/screens/settings/SettingsViewModel.kt") +
        implementation), f"No client access: {name}"

assert len(re.findall(r"\)\s*:\s*AppServerUiPort\s*\{", implementation)) == 1, "Exactly one App Server implementation"
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
assert "appServer.files" in read(source / "ui/screens/local/LocalHarnessViewModel.kt")
assert "appServer.attachments.import" in read(source / "ui/screens/local/LocalHarnessViewModel.kt")
assert "appServer.queuedUsers" in read(source / "ui/screens/local/LocalHarnessViewModel.kt")
assert "appServer.media" in read(source / "ui/screens/local/LocalHarnessViewModel.kt")
assert "appServer.usage" in read(source / "ui/screens/local/LocalHarnessViewModel.kt")
assert "appServer.memory" in read(source / "ui/screens/settings/SettingsViewModel.kt")
assert "appServer.pricing" in read(source / "ui/screens/settings/SettingsViewModel.kt")
assert "usage.revision.map" in implementation and "pricing.snapshots.map" in implementation, (
    "Settings notifications must reflect Usage and Pricing owner changes"
)
media_contract = read(contracts / "MediaUiPort.kt")
for prohibited in (
    "Boolean = false", "UiMediaAsset? = null",
    'MediaUiReceipt.Failed("Image download recovery is unavailable")',
    'MediaUiReceipt.Failed("Export is not available")',
):
    assert prohibited not in media_contract, f"Fake fallback in required Media owner contract: {prohibited}"
work_feature = read(source / "ui/screens/local/LocalFeatureUiActions.kt")
for required in (
    "saveWorkspaceFileToDownloads", "publishedWorkspaceFile", "workspaceDirectory",
    "workspaceDirectoryPage", "searchWorkspaceFiles", "searchWorkspacePage",
    "stopWorkspaceSearch", "observeWorkspaceFiles", "previewWorkspaceFilePage",
):
    match = re.search(r"val " + required + r": [^\n]+", work_feature)
    assert match, f"Missing Work UI action: {required}"
    assert " = " not in match.group(0), f"Silent default Work UI adapter: {required}"
assert "composition.queuedUsers" in read(local / "LocalFeatureExecutionPortModule.kt")

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
unused_ports = [entry for entry in ui_contracts
                if entry["name"].endswith("UiPort") and entry["consumer_count"] == 0]
assert not unused_ports, "Unconnected public UI ports: " + ", ".join(row["name"] for row in unused_ports)
assert all(name in names for name in ("AppServerUiPort", "ChatUiPort", "WorkConversationUiPort",
    "SessionUiPort", "AutomationUiPort", "ToolsManagementUiPort", "SettingsUiPort", "WorkAgentUiPort",
    "FilesUiPort", "AttachmentUiPort", "UiQueuedUserPort", "MediaUiPort", "UsageUiPort",
    "MemoryUiPort", "PricingUiPort"))
out = root / "build/app-server-audit"
out.mkdir(parents=True, exist_ok=True)
(out / "interface-inventory.json").write_text(json.dumps({
    "features": features, "commands": commands, "typed_endpoints": endpoints,
    "ui_interfaces_count": len(names),
    "ui_interfaces": ui_contracts,
}, indent=2, ensure_ascii=False), encoding="utf-8")
print("APP_SERVER_CONTRACT_OK features=" + str(len(features)) +
      " commands=" + str(len(commands)) + " ui_interfaces=" + str(len(names)))
