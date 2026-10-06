#!/usr/bin/env python3
"""Validate Architecture 3.0 structural boundaries and one-way migration exits.

This guard intentionally checks ownership, dependency direction, writable-state boundaries,
composition roots, and retired paths. It does not freeze implementation spelling, method-body
shape, constructor size, file size, or business behavior; those belong to tests and execution
invariant guards.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
APP_SOURCE_ROOT = ROOT / "app/src/main/java/com/labteto/dshmobile"
LOCAL_SOURCE_ROOT = APP_SOURCE_ROOT / "local"
UI_SOURCE_ROOT = APP_SOURCE_ROOT / "ui"

ENGINE_PATH = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
COMPOSITION_PATH = "app/src/main/java/com/labteto/dshmobile/local/LocalFeatureExecutionPortModule.kt"

# Transitional bridges are exact, shrink-only migration debt.
ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST: set[str] = set()
ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST = {
    "automationChatCoordinator",
    "automationWorkCoordinator",
}
ENGINE_COMPOSITION_BRIDGE_ALLOWLIST = (
    ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST
    | ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST
)

# Outside the app composition root, Architecture 3.0 code must not depend on the legacy Engine.
ENGINE_CONSUMER_ALLOWLIST = {COMPOSITION_PATH}
UI_AGGREGATE_STATE_ALLOWLIST: set[str] = set()

# Feature-owned runtime surfaces that have already exited Engine are permanent exits.
RUNTIME_ENGINE_FORBIDDEN_PATHS = (
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt",
)

REMOVED_ENGINE_STAGE3_FEATURE_ROOTS = {
    "sendChat",
    "queueHumanTurn",
    "queueWorkTurnLocked",
    "queueExistingWorkTurnLocked",
    "queueTurn",
    "queueTurnLocked",
    "runTurn",
    "runAgentTurn",
    "runWorkAgentTurn",
    "runChatTurn",
    "runGroupChatTurn",
    "regenerateReplyForMode",
    "regenerateWorkReply",
    "editAndResendUserMessage",
    "workSubagents",
    "runWorkflow",
    "exitPlanMode",
    "scheduleChatPostTurn",
}
REMOVED_ENGINE_STAGE3_WORK_BUILTINS = {
    "update_plan",
    "exit_plan_mode",
    "todo_write",
    "create_goal",
    "get_goal",
    "update_goal",
    "ask_user_question",
    "subagent",
    "spawn_subagent",
    "subagent_fork",
    "fork_subagent",
    "list_subagent_models",
    "list_agents",
    "send_message",
    "interrupt_agent",
    "workflow",
}
REMOVED_ENGINE_PRIVATE_BUSINESS_METHODS = {
    "chatStreamFilterPhrases",
    "compactHistoryIfNeeded",
    "enforceChatStyle",
    "ensureSystemMessage",
    "executeToolBatch",
    "modelRequestMarker",
    "modelToolSchemas",
    "persistChatBranchState",
    "persistNow",
    "rebuildGroupModelHistoryFromTranscript",
    "sessionFileFor",
}

# Chat/Work are provider Features. Their internals may depend on Shared capabilities, never siblings.
FEATURE_FORBIDDEN_IMPORT_PREFIXES = {
    "chat": (
        "com.labteto.dshmobile.local.work.",
        "com.labteto.dshmobile.local.automation.",
        "com.labteto.dshmobile.local.settings.",
    ),
    "work": (
        "com.labteto.dshmobile.local.chat.",
        "com.labteto.dshmobile.local.automation.",
        "com.labteto.dshmobile.local.settings.",
    ),
}

# These Shared packages have completed the reverse-dependency cleanup. Keep them Feature-agnostic.
FROZEN_SHARED_PACKAGES = (
    "agent",
    "attachment",
    "context",
    "files",
    "interaction",
    "jobs",
    "lsp",
    "model",
    "runtime",
    "security",
    "send",
    "usage",
    "web",
    "tools",
)
FEATURE_INTERNAL_IMPORT_PREFIXES = (
    "com.labteto.dshmobile.local.chat.",
    "com.labteto.dshmobile.local.work.",
    "com.labteto.dshmobile.local.automation.",
    "com.labteto.dshmobile.local.settings.",
)

# Remaining reverse/cross-Feature edges are explicit migration debt.
# Every allowance is an exact (consumer file, imported symbol) edge so debt cannot be replaced
# with a different internal dependency inside the same file.
SHARED_REVERSE_DEPENDENCY_MIGRATION_ALLOWLIST = {
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatDiaryStore"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatMemorySelector"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.PersonaProfile"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.chatLongTermMemoryBudget"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.chatRelationshipSubjectKey"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.diaryRecallItemLimit"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.diaryRecallUsageInstruction"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.isUnboundChatPersona"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.relationshipMemoryMatchesSubject"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.shouldSearchDiary"),
    ("app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt", "com.labteto.dshmobile.local.chat.takeWithinModelTokenBudget"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.chat.ChatCharacterState"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.chat.ChatContextState"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.chat.ChatReplySuggestion"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.chat.LocalChatBranchState"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.chat.LocalGroupChatState"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.chat.PersonaProfile"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.work.LocalGoal"),
    ("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt", "com.labteto.dshmobile.local.work.LocalTodoItem"),
}

AUTOMATION_INTERNAL_IMPORT_MIGRATION_ALLOWLIST = {
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatPendingTurn"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatPersonaStore"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.LocalChatState"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.appendMaterializedChatBranchMessage"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.applySceneTurn"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.characterProactiveDirective"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.enqueuePending"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.evaluateChatProactivePolicy"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.evaluateChatSilenceTrigger"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.isNearDuplicateProactive"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.proactiveConversationFocus"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.recentProactiveAvoidanceContext"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.chat.withoutLegacyConversationContext"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt", "com.labteto.dshmobile.local.work.LocalWorkState"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatCharacterState"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt", "com.labteto.dshmobile.local.chat.LocalChatState"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt", "com.labteto.dshmobile.local.chat.PersonaProfile"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt", "com.labteto.dshmobile.local.work.LocalWorkRecoveryContextPolicy"),
    ("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt", "com.labteto.dshmobile.local.work.LocalWorkState"),
}
AUTOMATION_ALLOWED_CROSS_FEATURE_API_SYMBOLS = {
    "LocalChatExecutionPort",
    "LocalChatTurnPort",
    "LocalChatUserActivityPort",
    "LocalWorkExecutionPort",
    "LocalWorkTurnPort",
}

SETTINGS_INTERNAL_IMPORT_MIGRATION_ALLOWLIST = {
    ("app/src/main/java/com/labteto/dshmobile/local/settings/LocalHarnessSettingsCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatStyleGuard"),
    ("app/src/main/java/com/labteto/dshmobile/local/settings/LocalHarnessSettingsCoordinator.kt", "com.labteto.dshmobile.local.chat.PersonaProfile"),
}

# Stage 5 is still migrating. UI may consume presentation facades/projections, but direct Store /
# Coordinator / Service / Runtime / Manager / Repository / Tracker / Executor / Registry / Gateway /
# Port imports are exact migration debt and may only shrink.
UI_ALLOWED_PRESENTATION_IMPORT_PREFIXES = (
    "com.labteto.dshmobile.local.presentation.",
)
UI_INTERNAL_IMPLEMENTATION_SUFFIXES = (
    "Coordinator",
    "Store",
    "Service",
    "Runtime",
    "Manager",
    "Repository",
    "Tracker",
    "Executor",
    "Registry",
    "Gateway",
    "Port",
)
UI_INTERNAL_IMPORT_MIGRATION_ALLOWLIST = {
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalChatModelAssistController.kt", "com.labteto.dshmobile.local.chat.GroupAnnouncementService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalChatModelAssistController.kt", "com.labteto.dshmobile.local.chat.PersonaAutoFillService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt", "com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt", "com.labteto.dshmobile.local.chat.GroupAnnouncementService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt", "com.labteto.dshmobile.local.chat.PersonaAutoFillService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt", "com.labteto.dshmobile.local.chat.PersonaInspectionService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalPersonaGalleryUiController.kt", "com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalPersonaGalleryUiController.kt", "com.labteto.dshmobile.local.chat.PersonaAutoFillService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalPersonaGalleryUiController.kt", "com.labteto.dshmobile.local.chat.PersonaInspectionService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalPersonaTransferCoordinator.kt", "com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/PersonaPresetArtworkInstaller.kt", "com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryController.kt", "com.labteto.dshmobile.local.session.LocalSessionRuntime"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryLoader.kt", "com.labteto.dshmobile.local.session.LocalSessionRuntime"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/AutomationPlannerUiController.kt", "com.labteto.dshmobile.local.automation.AutomationPlanningService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/TasksViewModel.kt", "com.labteto.dshmobile.local.automation.AutomationPlanningService"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/settings/MemorySettingsController.kt", "com.labteto.dshmobile.local.memory.MemoryManager"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/settings/MemorySettingsController.kt", "com.labteto.dshmobile.local.memory.MemoryStore"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsViewModel.kt", "com.labteto.dshmobile.local.memory.MemoryManager"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsViewModel.kt", "com.labteto.dshmobile.local.memory.MemoryStore"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsViewModel.kt", "com.labteto.dshmobile.local.model.DeepSeekPricingRepository"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsViewModel.kt", "com.labteto.dshmobile.local.model.DeepSeekUsageTracker"),
    ("app/src/main/java/com/labteto/dshmobile/ui/screens/tools/ToolsScreen.kt", "com.labteto.dshmobile.local.tools.LocalToolsRuntime"),
}

# Session is still being horizontally migrated; these neutral slices are already closed.
FROZEN_SHARED_FILES = (
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionPersistenceProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionControlProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalRequestContextProjection.kt",
)

# Writable aggregate state is temporary composition debt, not a general Feature API.
FEATURE_AGGREGATE_WRITE_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatStatePort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkStatePort.kt",
}
RUNTIME_PROJECTION_UPDATE_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatStatePort.kt",
}

NARROW_PORT_PATHS = (
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatExecutionPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTurnPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkExecutionPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionLifecyclePort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsManagementPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalDiagnosticsPort.kt",
)

LEGACY_MOVED_IMPORTS = {
    "com.labteto.dshmobile.local.runtime.structuredWorkState":
        "com.labteto.dshmobile.local.work.structuredWorkState",
    "com.labteto.dshmobile.local.runtime.LocalWorkCueKind":
        "com.labteto.dshmobile.local.work.LocalWorkCueKind",
    "com.labteto.dshmobile.local.runtime.extractLocalWorkCueSnippet":
        "com.labteto.dshmobile.local.work.extractLocalWorkCueSnippet",
    "com.labteto.dshmobile.local.LocalChatContextRefreshCoordinator":
        "com.labteto.dshmobile.local.chat.LocalChatContextRefreshCoordinator",
    "com.labteto.dshmobile.local.settings.LocalAgentRuntimeLimits":
        "com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits",
    "com.labteto.dshmobile.local.chat.ChatStreamFilter":
        "com.labteto.dshmobile.local.model.LocalStreamPhraseFilter",
}

RETIRED_SETTINGS_RUNTIME_PATHS = (
    "app/src/main/java/com/labteto/dshmobile/local/settings/LocalAgentRuntimeLimits.kt",
)

RETIRED_SHARED_WORK_SEMANTIC_PATHS = (
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalStructuredWorkStateProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalWorkStateCueExtractor.kt",
)


def die(message: str) -> None:
    print(f"[architecture-3] {message}", file=sys.stderr)
    raise SystemExit(1)


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.exists():
        die(f"required Architecture 3.0 boundary file is missing: {relative}")
    return path.read_text(encoding="utf-8")


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//.*$", "", source, flags=re.MULTILINE)


def imports(source: str) -> set[str]:
    return set(re.findall(r"^import\s+([^\s]+)\s*$", strip_comments(source), re.MULTILINE))


def kotlin_sources_under(base: Path):
    if not base.exists():
        return
    for path in base.rglob("*.kt"):
        yield path, strip_comments(path.read_text(encoding="utf-8"))


def data_class_fields(source: str, class_name: str) -> set[str]:
    match = re.search(
        rf"\bdata\s+class\s+{re.escape(class_name)}\s*\((.*?)\n\)",
        strip_comments(source),
        re.DOTALL,
    )
    if match is None:
        die(f"unable to locate data class {class_name}")
    return set(re.findall(r"^\s*val\s+([A-Za-z0-9_]+)\s*:", match.group(1), re.MULTILINE))


def declared_top_level_types(source: str) -> set[str]:
    return set(re.findall(
        r"^\s*(?:(?:data|enum|sealed|annotation|value|internal|public)\s+)*"
        r"(?:class|interface|object|typealias)\s+([A-Za-z0-9_]+)",
        strip_comments(source),
        re.MULTILINE,
    ))


def references_type(source: str, type_name: str) -> bool:
    return re.search(rf"\b{re.escape(type_name)}\b", strip_comments(source)) is not None


# ---- Authority -------------------------------------------------------------

architecture_doc = read("docs/ARCHITECTURE.md")
for contract in (
    "# 架构 3.0",
    "本文是 777 当前唯一系统架构权威文档",
    "模块化单体 + 层级化 Feature 组合 + 共享能力契约 + 极薄运行内核",
    "UI\n↓\nFeature API\n↓\nFeature internal\n↓\nShared Capability\n↓\nKernel / Platform",
):
    if contract not in architecture_doc:
        die("docs/ARCHITECTURE.md lost Architecture 3.0 authority: " + contract)

for retired_claim in (
    "LocalHarnessEngine\n  cross-capability turn/session orchestration",
    "`LocalHarnessEngine` owns consistency across a local turn/session",
):
    if retired_claim in architecture_doc:
        die("architecture authority regressed to retired Engine-centered ownership: " + retired_claim)


# ---- Physical Kotlin boundaries -------------------------------------------

for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT):
    relative = path.relative_to(LOCAL_SOURCE_ROOT)
    expected_package = "com.labteto.dshmobile.local"
    if relative.parent.parts:
        expected_package += "." + ".".join(relative.parent.parts)
    declared = re.search(r"^package\s+([A-Za-z0-9_.]+)\s*$", source, re.MULTILINE)
    if declared is None or declared.group(1) != expected_package:
        die(f"directory/package mismatch: {relative}; expected {expected_package}")
    if re.search(r"^import\s+[^\n]+\.\*\s*$", source, re.MULTILINE):
        die(f"architecture-sensitive source must use explicit imports: {path.relative_to(ROOT)}")

# Removed package paths are permanent exits.
for kotlin_root in (
    ROOT / "app/src/main",
    ROOT / "app/src/test",
    ROOT / "app/src/androidTest",
):
    if not kotlin_root.exists():
        continue
    for path, source in kotlin_sources_under(kotlin_root):
        for legacy_import, replacement in LEGACY_MOVED_IMPORTS.items():
            if re.search(rf"^import\s+{re.escape(legacy_import)}\s*$", source, re.MULTILINE):
                die(
                    f"{path.relative_to(ROOT)} imports retired path {legacy_import}; "
                    f"use {replacement}"
                )

for retired in RETIRED_SETTINGS_RUNTIME_PATHS:
    if (ROOT / retired).exists():
        die("shared Agent runtime limit contract returned to SettingsFeature: " + retired)

for retired in RETIRED_SHARED_WORK_SEMANTIC_PATHS:
    if (ROOT / retired).exists():
        die("Work semantic interpretation returned to Shared Runtime: " + retired)


# ---- Dependency direction --------------------------------------------------

for feature_name, forbidden_prefixes in FEATURE_FORBIDDEN_IMPORT_PREFIXES.items():
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / feature_name):
        source_imports = imports(source)
        for prefix in forbidden_prefixes:
            if any(item.startswith(prefix) for item in source_imports):
                die(
                    f"{path.relative_to(ROOT)} imports sibling Feature internals through {prefix}; "
                    "use provider-owned API/Port or a Shared Capability"
                )

for package_name in FROZEN_SHARED_PACKAGES:
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / package_name):
        source_imports = imports(source)
        for prefix in FEATURE_INTERNAL_IMPORT_PREFIXES:
            if any(item.startswith(prefix) for item in source_imports):
                die(
                    f"{path.relative_to(ROOT)} makes Shared Capability depend on Feature internal {prefix}; "
                    "invert the dependency through a neutral Shared contract"
                )
        if references_type(source, "LocalHarnessEngine"):
            die(f"{path.relative_to(ROOT)} makes Shared Capability depend on legacy LocalHarnessEngine")

for relative in FROZEN_SHARED_FILES:
    source_imports = imports(read(relative))
    for prefix in FEATURE_INTERNAL_IMPORT_PREFIXES:
        if any(item.startswith(prefix) for item in source_imports):
            die(
                f"{relative} is a closed Shared boundary but imports Feature internal {prefix}"
            )

# Session/Memory still contain known reverse Feature dependencies. Track exact import edges so
# the remaining migration debt cannot spread or be substituted inside an already-allowed file.
def format_edges(edges: set[tuple[str, str]]) -> str:
    return ", ".join(f"{path} -> {imported}" for path, imported in sorted(edges))


shared_reverse_dependency_edges: set[tuple[str, str]] = set()
for package_name in ("session", "memory"):
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / package_name):
        relative = path.relative_to(ROOT).as_posix()
        for imported in imports(source):
            if imported.startswith(FEATURE_INTERNAL_IMPORT_PREFIXES):
                shared_reverse_dependency_edges.add((relative, imported))

unexpected_shared_reverse = (
    shared_reverse_dependency_edges - SHARED_REVERSE_DEPENDENCY_MIGRATION_ALLOWLIST
)
if unexpected_shared_reverse:
    die(
        "new Shared→Feature migration edge(s): "
        + format_edges(unexpected_shared_reverse)
        + "; invert them through neutral Shared contracts"
    )
stale_shared_reverse = (
    SHARED_REVERSE_DEPENDENCY_MIGRATION_ALLOWLIST - shared_reverse_dependency_edges
)
if stale_shared_reverse:
    die(
        "stale Shared→Feature migration edge(s): "
        + format_edges(stale_shared_reverse)
        + "; shrink the exact ratchet with the migration"
    )

# Automation may consume provider-owned execution Ports, but all remaining internal imports are
# Stage-4 debt tracked by exact edge.
automation_internal_edges: set[tuple[str, str]] = set()
for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / "automation"):
    relative = path.relative_to(ROOT).as_posix()
    for imported in imports(source):
        if not imported.startswith((
            "com.labteto.dshmobile.local.chat.",
            "com.labteto.dshmobile.local.work.",
        )):
            continue
        symbol = imported.rsplit(".", 1)[-1]
        if symbol in AUTOMATION_ALLOWED_CROSS_FEATURE_API_SYMBOLS:
            continue
        automation_internal_edges.add((relative, imported))

unexpected_automation_internal = (
    automation_internal_edges - AUTOMATION_INTERNAL_IMPORT_MIGRATION_ALLOWLIST
)
if unexpected_automation_internal:
    die(
        "new Automation→Feature-internal migration edge(s): "
        + format_edges(unexpected_automation_internal)
        + "; consume provider-owned execution Ports instead"
    )
stale_automation_internal = (
    AUTOMATION_INTERNAL_IMPORT_MIGRATION_ALLOWLIST - automation_internal_edges
)
if stale_automation_internal:
    die(
        "stale Automation internal-import migration edge(s): "
        + format_edges(stale_automation_internal)
        + "; shrink Stage 4 debt with the Port migration"
    )

# Settings is a product Feature. Its remaining Chat coupling is explicit Stage-5 migration debt.
settings_internal_edges: set[tuple[str, str]] = set()
for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / "settings"):
    relative = path.relative_to(ROOT).as_posix()
    for imported in imports(source):
        if imported.startswith((
            "com.labteto.dshmobile.local.chat.",
            "com.labteto.dshmobile.local.work.",
            "com.labteto.dshmobile.local.automation.",
        )):
            settings_internal_edges.add((relative, imported))

unexpected_settings_internal = (
    settings_internal_edges - SETTINGS_INTERNAL_IMPORT_MIGRATION_ALLOWLIST
)
if unexpected_settings_internal:
    die(
        "new Settings→Feature-internal migration edge(s): "
        + format_edges(unexpected_settings_internal)
        + "; expose a Settings-facing provider API/Port instead"
    )
stale_settings_internal = (
    SETTINGS_INTERNAL_IMPORT_MIGRATION_ALLOWLIST - settings_internal_edges
)
if stale_settings_internal:
    die(
        "stale Settings internal-import migration edge(s): "
        + format_edges(stale_settings_internal)
        + "; shrink Stage 5 debt with the migration"
    )

# UI may consume DTOs and presentation facades/projections. Direct implementation imports are
# migration debt until Feature UI contribution is complete.
ui_internal_edges: set[tuple[str, str]] = set()
for path, source in kotlin_sources_under(UI_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    for imported in imports(source):
        if not imported.startswith("com.labteto.dshmobile.local."):
            continue
        if imported.startswith(UI_ALLOWED_PRESENTATION_IMPORT_PREFIXES):
            continue
        symbol = imported.rsplit(".", 1)[-1]
        if symbol.endswith(UI_INTERNAL_IMPLEMENTATION_SUFFIXES):
            ui_internal_edges.add((relative, imported))

unexpected_ui_internal = ui_internal_edges - UI_INTERNAL_IMPORT_MIGRATION_ALLOWLIST
if unexpected_ui_internal:
    die(
        "new UI→internal implementation migration edge(s): "
        + format_edges(unexpected_ui_internal)
        + "; UI must consume a Feature API/projection"
    )
stale_ui_internal = UI_INTERNAL_IMPORT_MIGRATION_ALLOWLIST - ui_internal_edges
if stale_ui_internal:
    die(
        "stale UI internal-import migration edge(s): "
        + format_edges(stale_ui_internal)
        + "; shrink Stage 5 debt with the UI migration"
    )

# Shared recovery must remain semantically neutral even when types are not imported.
agent_recovery = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt")
)
for work_semantic in ("LocalWorkCheckpoint", "<work-checkpoint>", "最近持久工作检查点"):
    if work_semantic in agent_recovery:
        die("Shared Agent recovery interprets WorkFeature semantics: " + work_semantic)


# ---- Legacy Engine containment and migration ratchets ---------------------

engine = strip_comments(read(ENGINE_PATH))
composition = strip_comments(read(COMPOSITION_PATH))

engine_consumers: set[str] = set()
for path, source in kotlin_sources_under(APP_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    if relative == ENGINE_PATH:
        continue
    if references_type(source, "LocalHarnessEngine"):
        engine_consumers.add(relative)

unexpected_engine_consumers = sorted(engine_consumers - ENGINE_CONSUMER_ALLOWLIST)
if unexpected_engine_consumers:
    die(
        "new direct LocalHarnessEngine consumer(s): "
        + ", ".join(unexpected_engine_consumers)
        + "; only the app composition root may bridge the migration Engine"
    )
stale_engine_consumers = sorted(ENGINE_CONSUMER_ALLOWLIST - engine_consumers)
if stale_engine_consumers:
    die(
        "stale LocalHarnessEngine consumer allowlist entries: "
        + ", ".join(stale_engine_consumers)
        + "; shrink the migration allowlist with the ownership move"
    )

for relative in RUNTIME_ENGINE_FORBIDDEN_PATHS:
    if references_type(read(relative), "LocalHarnessEngine"):
        die(relative + " reintroduced a forbidden LocalHarnessEngine dependency")

engine_bridges = set(re.findall(r"\bengine\s*\.\s*([A-Za-z0-9_]+)", composition))
unexpected_bridges = sorted(engine_bridges - ENGINE_COMPOSITION_BRIDGE_ALLOWLIST)
if unexpected_bridges:
    die(
        "new Engine-backed composition bridge(s): "
        + ", ".join(unexpected_bridges)
        + "; create a real Feature/Shared owner instead"
    )
stale_stage3 = sorted(ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST - engine_bridges)
if stale_stage3:
    die(
        "stale Stage-3 Engine bridge allowlist entries: "
        + ", ".join(stale_stage3)
        + "; delete them from the ratchet in the same migration"
    )
stale_stage4 = sorted(ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST - engine_bridges)
if stale_stage4:
    die(
        "stale Stage-4 Automation Engine bridge allowlist entries: "
        + ", ".join(stale_stage4)
        + "; delete them when Automation moves to execution ports"
    )

for method in REMOVED_ENGINE_STAGE3_FEATURE_ROOTS | REMOVED_ENGINE_PRIVATE_BUSINESS_METHODS:
    if re.search(rf"\b(?:private|internal|public)?\s*(?:suspend\s+)?fun\s+{re.escape(method)}\s*\(", engine):
        die("retired Feature business method returned to LocalHarnessEngine: " + method)

for builtin in REMOVED_ENGINE_STAGE3_WORK_BUILTINS:
    if re.search(rf'^\s*"{re.escape(builtin)}"\s*->', engine, re.MULTILINE):
        die("Work-owned builtin dispatch returned to LocalHarnessEngine: " + builtin)


# Session owns transaction ordering; provider Features own domain interpretation.
session_lifecycle = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/LocalSessionLifecycleCoordinator.kt"
))
chat_session_lifecycle = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatSessionLifecyclePlanner.kt"
))
work_session_lifecycle = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkSessionLifecyclePlanner.kt"
))

for required in (
    "private val chatSessionLifecycle: LocalChatSessionLifecyclePlanner",
    "chatSessionLifecycle.prepareCreate(",
    "chatSessionLifecycle.resolveModeCommand(",
    "LocalWorkSessionLifecyclePlanner.initialState(",
    "LocalWorkSessionLifecyclePlanner.handoffState(",
):
    if required not in session_lifecycle:
        die("Session lifecycle lost provider-owned domain planning: " + required)

for forbidden in (
    "ChatPersonaStore",
    "ChatCharacterState",
    "ChatContextState",
    "LocalChatState(",
    "LocalGroupChatState",
    "PersonaGalleryEntry",
    "PersonaProfile",
    "resolveLocalGroupChatMembers",
    "MIN_GROUP_CHAT_MEMBERS",
    "MAX_GROUP_CHAT_MEMBERS",
    "projectExecutionJobs(",
    "LocalWorkState(",
    "HandoffGoal",
    "HandoffTodo",
    "groupTranscriptLine(",
):
    if forbidden in session_lifecycle:
        die(
            "Shared Session lifecycle reinterprets provider Feature domain state: "
            + forbidden
        )

for required in (
    "class LocalChatSessionLifecyclePlanner",
    "resolveLocalGroupChatMembers(",
    "LocalChatState(",
    "findEstablishedGroupChatSession(",
    "continuePendingInSession(",
):
    if required not in chat_session_lifecycle:
        die("ChatFeature Session lifecycle planning is incomplete: " + required)

for required in (
    "object LocalWorkSessionLifecyclePlanner",
    "projectExecutionJobs(",
    "HandoffState(",
):
    if required not in work_session_lifecycle:
        die("WorkFeature Session lifecycle projection is incomplete: " + required)


# ---- State ownership and single-writer seams ------------------------------

aggregate_models = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessModels.kt"
))
allowed_root_types = {"LocalHarnessState", "LocalUsageMode"}
root_types = declared_top_level_types(aggregate_models)
if root_types != allowed_root_types:
    die(
        "root aggregate file may only declare LocalHarnessState/LocalUsageMode; found extra types: "
        + ", ".join(sorted(root_types - allowed_root_types))
    )

aggregate_fields = data_class_fields(aggregate_models, "LocalHarnessState")
domain_sources = (
    ("chat", "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatState.kt", "LocalChatState"),
    ("work", "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkState.kt", "LocalWorkState"),
    ("kernel", "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalKernelState.kt", "LocalKernelState"),
    ("modelState", "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelState.kt", "LocalModelState"),
)
for aggregate_container, relative, class_name in domain_sources:
    if aggregate_container not in aggregate_fields:
        die(f"LocalHarnessState lost the {aggregate_container} domain projection")
    flattened = aggregate_fields & data_class_fields(read(relative), class_name)
    if flattened:
        die(
            f"LocalHarnessState re-flattened {class_name} field(s): "
            + ", ".join(sorted(flattened))
        )

for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    if "runtimeStateStore.mutableState" in source or "runtime.mutableState" in source:
        die(f"{relative} bypasses the Runtime projection with writable aggregate state")
    if "runtimeStateStore.projection.update(" in source and relative not in RUNTIME_PROJECTION_UPDATE_ALLOWLIST:
        die(
            f"{relative} introduces a new aggregate write seam; "
            "use a domain StatePort or explicit Runtime projection command"
        )

for feature_name in ("chat", "work"):
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / feature_name):
        relative = path.relative_to(ROOT).as_posix()
        if (
            "MutableStateFlow<LocalHarnessState>" in source
            and relative not in FEATURE_AGGREGATE_WRITE_ALLOWLIST
        ):
            die(
                f"{relative} introduces writable aggregate state inside {feature_name} Feature; "
                "use domain-owned state or a narrow StatePort"
            )

aggregate_ui_consumers: set[str] = set()
for path, source in kotlin_sources_under(UI_SOURCE_ROOT):
    if references_type(source, "LocalHarnessState"):
        aggregate_ui_consumers.add(path.relative_to(ROOT).as_posix())
unexpected_ui = sorted(aggregate_ui_consumers - UI_AGGREGATE_STATE_ALLOWLIST)
if unexpected_ui:
    die("UI must consume Feature/Shell projections instead of LocalHarnessState: " + ", ".join(unexpected_ui))
stale_ui = sorted(UI_AGGREGATE_STATE_ALLOWLIST - aggregate_ui_consumers)
if stale_ui:
    die("stale UI aggregate-state allowlist entries: " + ", ".join(stale_ui))

for relative in NARROW_PORT_PATHS:
    source = strip_comments(read(relative))
    expected_name = Path(relative).stem
    if re.search(
        rf"\binternal\s+(?:fun\s+)?interface\s+{re.escape(expected_name)}\b",
        source,
    ) is None:
        die(relative + " must remain a narrow interface contract: " + expected_name)
    if references_type(source, "LocalHarnessEngine"):
        die(relative + " exposes the legacy Engine through a 3.0 Port")
    if "MutableStateFlow<LocalHarnessState>" in source:
        die(relative + " exposes writable aggregate state through a 3.0 Port")

    source_imports = imports(source)
    if "/chat/" in relative:
        forbidden_prefixes = FEATURE_FORBIDDEN_IMPORT_PREFIXES["chat"]
    elif "/work/" in relative:
        forbidden_prefixes = FEATURE_FORBIDDEN_IMPORT_PREFIXES["work"]
    else:
        forbidden_prefixes = FEATURE_INTERNAL_IMPORT_PREFIXES
    for prefix in forbidden_prefixes:
        if any(item.startswith(prefix) for item in source_imports):
            die(
                relative + " leaks Feature internal dependencies through a 3.0 Port: " + prefix
            )


# ---- Product Feature composition vs Runtime plugins -----------------------

feature_catalog_path = "app/src/main/java/com/labteto/dshmobile/local/feature/LocalFeatureCatalog.kt"
feature_catalog = strip_comments(read(feature_catalog_path))
if "object LocalFeatureCatalog" not in feature_catalog:
    die("Architecture 3.0 requires an immutable LocalFeatureCatalog composition root")
for dynamic_feature_api in ("register(", "unregister(", "MutableStateFlow", "mutableStateListOf"):
    if dynamic_feature_api in feature_catalog:
        die("Product Feature catalog must stay startup-immutable: " + dynamic_feature_api)
if "PluginManager" in feature_catalog or "PluginCatalog" in feature_catalog:
    die("Product Feature catalog must remain separate from Runtime plugin lifecycle")

def enum_entries(source: str, enum_name: str) -> set[str]:
    match = re.search(
        rf"\benum\s+class\s+{re.escape(enum_name)}\s*\{{(.*?)\}}",
        source,
        re.DOTALL,
    )
    if match is None:
        die("Feature Catalog lost enum contract: " + enum_name)
    return set(re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$", match.group(1), re.MULTILINE))

module_ids = enum_entries(feature_catalog, "LocalFeatureModuleId")
route_ids = enum_entries(feature_catalog, "LocalFeatureRoute")
modules_block_start = feature_catalog.find("val modules: List<LocalFeatureModule>")
modules_block_end = feature_catalog.find("private val ownerByRoute", modules_block_start)
if modules_block_start < 0 or modules_block_end < 0:
    die("LocalFeatureCatalog lost its startup module composition block")
modules_block = feature_catalog[modules_block_start:modules_block_end]

for module_id in module_ids:
    count = len(re.findall(
        rf"LocalFeatureModule\s*\(\s*LocalFeatureModuleId\.{re.escape(module_id)}\b",
        modules_block,
    ))
    if count != 1:
        die(f"Feature module {module_id} must be composed exactly once, found {count}")

for route_id in route_ids:
    count = len(re.findall(
        rf"LocalFeatureRoute\.{re.escape(route_id)}\b",
        modules_block,
    ))
    if count != 1:
        die(f"Feature route {route_id} must have exactly one startup owner, found {count}")

feature_navigation = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeatureNavigation.kt"
))
if "LocalFeatureCatalog.resolve(" not in feature_navigation:
    die("Feature navigation must resolve product routes through LocalFeatureCatalog")

plugin_composition = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalPluginComposition.kt"
))
if "LocalFeatureCatalog" in plugin_composition:
    die("Runtime plugin composition must not own product Feature registration")

android_device_provider_allowlist = {
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalPluginComposition.kt",
}
for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    if relative not in android_device_provider_allowlist and references_type(source, "AndroidDeviceProvider"):
        die(
            f"{relative} depends directly on AndroidDeviceProvider; "
            "platform provider construction belongs to the plugin composition root"
        )

print("[architecture-3] OK: ownership, dependency direction, state boundaries, and migration exits hold")
