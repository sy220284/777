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

RETIRED_ENGINE_PATH = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
RETIRED_ENGINE_SUPPORT_PATH = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngineSupport.kt"
RUNTIME_KERNEL_PATH = "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt"
APPLICATION_PATH = "app/src/main/java/com/labteto/dshmobile/DshApplication.kt"
COMPOSITION_PATH = "app/src/main/java/com/labteto/dshmobile/local/LocalFeatureExecutionPortModule.kt"


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
    "memory",
    "model",
    "persistence",
    "profile",
    "quality",
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


AUTOMATION_ALLOWED_CROSS_FEATURE_API_SYMBOLS = {
    "LocalChatExecutionPort",
    "LocalChatTurnPort",
    "LocalChatUserActivityPort",
    "LocalWorkAutomationExecutionPort",
    "LocalWorkExecutionPort",
    "LocalWorkTurnPort",
    "LocalChatAutomationExecutionPort",
    "LocalWorkAutomationExecutionPort",
}


# Stage 5 is closed. UI may consume DTOs and presentation facades/projections, but direct Feature
# implementation objects or top-level Feature behavior/values are final architecture violations.
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


# Closed Shared slices must remain independent from product Feature internals.
FROZEN_SHARED_FILES = (
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalCurrentSessionSnapshotProvider.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionControlProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalRequestContextProjection.kt",
)

RUNTIME_PROJECTION_UPDATE_OWNER = "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt"

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
    "模块化单体 + 层级化 Feature 组合 + 共享能力契约 + 极薄进程运行内核",
    "UI\n↓\npresentation / Feature API / projection\n↓\nFeature internal\n↓\nShared Capability / Shared Runtime contract（按需）\n↓\nPlatform / Infrastructure",
    "DshApplication\n↓\nLocalRuntimeKernel\n↓\nLocalRuntimeBootstrapPort\n↓\n应用组合根",
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


for retired in (
    "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt",
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentRunnerFactory.kt",
    "app/src/main/java/com/labteto/dshmobile/local/jobs/LocalPersistentJobRecoveryCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalWorkTurnPromptContext.kt",
):
    if (ROOT / retired).exists():
        die("Work-owned execution/recovery implementation returned to a legacy Shared/root path: " + retired)


for retired in (
    "app/src/main/java/com/labteto/dshmobile/local/LocalChatTurnCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalChatReplyCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalChatHistoryWindow.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalChatEditSupport.kt",
):
    if (ROOT / retired).exists():
        die("retired Chat business owner returned outside ChatFeature: " + retired)

for retired in (
    "app/src/main/java/com/labteto/dshmobile/local/LocalSessionCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalSessionRepository.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionPersistenceProjection.kt",
):
    if (ROOT / retired).exists():
        die("retired Session boundary returned outside neutral Shared Session ownership: " + retired)

# Retired shared implementations must stay deleted after their Feature owner takes over.
if (ROOT / "app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt").exists():
    die("legacy shared Memory coordinator returned; use Chat/Work owned memory runtimes")

foreground_loader = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/LocalForegroundSessionLoader.kt"
))
for forbidden in (
    "reconcileCharacterBehaviorTuning(",
    "reconcileGroupCharacterBehaviorTuning(",
    "restoreMaterializedChatBranchState(",
    "recoverPendingTimelineRewriteProjection(",
    "projectGroupGalleryState(",
):
    if forbidden in foreground_loader:
        die("foreground composition reclaimed Chat domain restore semantics: " + forbidden)

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
        relative = path.relative_to(ROOT).as_posix()
        aggregate_state_owner_paths = {
            "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt",
            "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt",
        }
        if relative not in aggregate_state_owner_paths and references_type(source, "LocalHarnessState"):
            die(
                f"{relative} makes a Shared Capability depend on aggregate Feature state; "
                "pass a neutral Shared DTO/Port instead"
            )

for relative in FROZEN_SHARED_FILES:
    source_imports = imports(read(relative))
    for prefix in FEATURE_INTERNAL_IMPORT_PREFIXES:
        if any(item.startswith(prefix) for item in source_imports):
            die(
                f"{relative} is a closed Shared boundary but imports Feature internal {prefix}"
            )

# Shared Session/Memory are final one-way boundaries; reverse Feature imports fail directly.
def format_edges(edges: set[tuple[str, str]]) -> str:
    return ", ".join(f"{path} -> {imported}" for path, imported in sorted(edges))


shared_reverse_dependency_edges: set[tuple[str, str]] = set()
for package_name in ("session", "memory"):
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / package_name):
        relative = path.relative_to(ROOT).as_posix()
        for imported in imports(source):
            if imported.startswith(FEATURE_INTERNAL_IMPORT_PREFIXES):
                shared_reverse_dependency_edges.add((relative, imported))

if shared_reverse_dependency_edges:
    die(
        "Shared→Feature reverse dependency edge(s): "
        + format_edges(shared_reverse_dependency_edges)
        + "; invert them through neutral Shared contracts"
    )

for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / "session"):
    relative = path.relative_to(ROOT).as_posix()
    if references_type(source, "LocalHarnessState"):
        die(
            f"{relative} makes Shared Session transitively depend on the aggregate Feature state; "
            "use a neutral Session snapshot/provider contract"
        )

# Automation may consume provider-owned execution Ports; all other Chat/Work internals are forbidden.
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

if automation_internal_edges:
    die(
        "Automation→Feature-internal edge(s): "
        + format_edges(automation_internal_edges)
        + "; consume provider-owned execution Ports instead"
    )

# Settings is a product Feature; cross-Feature internals are forbidden in the final graph.
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

if settings_internal_edges:
    die(
        "Settings→Feature-internal edge(s): "
        + format_edges(settings_internal_edges)
        + "; expose a Settings-facing provider API/Port instead"
    )

# UI may consume DTOs and presentation facades/projections. Direct implementation or Feature behavior imports are forbidden.
feature_ui_behavior_symbols: set[str] = set()
for feature_name in ("chat", "work", "automation", "settings", "tools"):
    for _path, feature_source in kotlin_sources_under(LOCAL_SOURCE_ROOT / feature_name):
        package_match = re.search(r"^package\s+([\w.]+)", feature_source, re.MULTILINE)
        if package_match is None:
            continue
        package_name = package_match.group(1)
        for symbol in re.findall(
            r"^(?:internal\s+|public\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s*)?(?:[\w<>?,.]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(",
            feature_source,
            re.MULTILINE,
        ):
            feature_ui_behavior_symbols.add(f"{package_name}.{symbol}")
        for symbol in re.findall(
            r"^(?:internal\s+|public\s+)?object\s+([A-Za-z_][A-Za-z0-9_]*)\b",
            feature_source,
            re.MULTILINE,
        ):
            feature_ui_behavior_symbols.add(f"{package_name}.{symbol}")
        for symbol in re.findall(
            r"^(?:internal\s+|public\s+)?(?:const\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\b",
            feature_source,
            re.MULTILINE,
        ):
            feature_ui_behavior_symbols.add(f"{package_name}.{symbol}")

ui_feature_behavior_edges: set[tuple[str, str]] = set()
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
        if imported in feature_ui_behavior_symbols:
            ui_feature_behavior_edges.add((relative, imported))

if ui_internal_edges:
    die(
        "UI→internal implementation edge(s): "
        + format_edges(ui_internal_edges)
        + "; UI must consume a Feature API/projection"
    )

if ui_feature_behavior_edges:
    die(
        "UI imports Feature top-level behavior/value directly: "
        + format_edges(ui_feature_behavior_edges)
        + "; expose the policy through local.presentation / Feature UI API"
    )

# Shared recovery must remain semantically neutral even when types are not imported.
agent_recovery = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt")
)
for work_semantic in ("LocalWorkCheckpoint", "<work-checkpoint>", "最近持久工作检查点"):
    if work_semantic in agent_recovery:
        die("Shared Agent recovery interprets WorkFeature semantics: " + work_semantic)


# ---- Final Runtime Kernel and retired Engine ------------------------------

if (ROOT / RETIRED_ENGINE_PATH).exists():
    die("retired LocalHarnessEngine source returned; Architecture 3.0 uses LocalRuntimeKernel")
if (ROOT / RETIRED_ENGINE_SUPPORT_PATH).exists():
    die("retired LocalHarnessEngineSupport source returned; helpers must live with their real owner")

for path, source in kotlin_sources_under(APP_SOURCE_ROOT):
    if references_type(source, "LocalHarnessEngine"):
        die(
            f"{path.relative_to(ROOT)} reintroduced retired LocalHarnessEngine; "
            "use a Feature/Shared owner or LocalRuntimeKernel lifecycle API"
        )

runtime_kernel = strip_comments(read(RUNTIME_KERNEL_PATH))
for required in (
    "class LocalRuntimeKernel",
    "bootstrap.initialize(",
    "bootstrap.prepareAndRestore(",
):
    if required not in runtime_kernel:
        die("LocalRuntimeKernel lost required process lifecycle responsibility: " + required)

for forbidden in (
    "PersonaProfile",
    "ChatPersonaStore",
    "LocalGroupChatState",
    "LocalTodoItem",
    "LocalGoal",
    "GitHub",
    "LocalFeaturePage",
):
    if forbidden in runtime_kernel:
        die("LocalRuntimeKernel regained product/Feature semantics: " + forbidden)

bootstrap_composition = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/LocalRuntimeBootstrapComposition.kt"
))
for required in (
    "class LocalRuntimeBootstrapComposition",
    "LocalRuntimeBootstrapPort",
    "runtimeStateStore.initialize(",
    "prepareLocalHarnessStartup(",
    "foregroundSessionLoader.loadStartup(",
    "work.schedulePersistentRecovery()",
):
    if required not in bootstrap_composition:
        die("app Runtime bootstrap composition is incomplete: " + required)

application = strip_comments(read(APPLICATION_PATH))
for required in (
    "LocalRuntimeKernel",
    "localRuntimeKernel.start()",
):
    if required not in application:
        die("DshApplication must eagerly start the process Runtime Kernel: " + required)


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

for chat_owned_field in ("chatStyleGuardEnabled", "chatStyleGuardCustomPhrases", "styleGuardHits"):
    if chat_owned_field in aggregate_fields:
        die("LocalHarnessState re-flattened Chat-owned style guard field: " + chat_owned_field)

settings_coordinator = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/settings/LocalHarnessSettingsCoordinator.kt"
))
for chat_owned_setting in (
    "KEY_CHAT_STYLE_GUARD",
    "KEY_CHAT_STYLE_GUARD_CUSTOM_PHRASES",
    "configureChatStyleGuard(",
    "recordStyleGuardHits(",
):
    if chat_owned_setting in settings_coordinator:
        die("SettingsFeature reclaimed Chat-owned style guard semantics: " + chat_owned_setting)

for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    if "runtimeStateStore.mutableState" in source or "runtime.mutableState" in source:
        die(f"{relative} bypasses the Runtime projection with writable aggregate state")
    if "runtimeStateStore.projection.update(" in source and relative != RUNTIME_PROJECTION_UPDATE_OWNER:
        die(
            f"{relative} introduces a new aggregate write seam; "
            "use a domain StatePort or explicit Runtime projection command"
        )

for feature_name in ("chat", "work"):
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / feature_name):
        relative = path.relative_to(ROOT).as_posix()
        if "MutableStateFlow<LocalHarnessState>" in source:
            die(
                f"{relative} introduces writable aggregate state inside {feature_name} Feature; "
                "use domain-owned state or a narrow StatePort"
            )
        if re.search(r"\(\s*LocalHarnessState\s*\)\s*->\s*LocalHarnessState", source):
            die(
                f"{relative} exposes a full aggregate write transform inside {feature_name} Feature; "
                "keep aggregate snapshots read-only and write through domain-owned state"
            )

aggregate_ui_consumers: set[str] = set()
for path, source in kotlin_sources_under(UI_SOURCE_ROOT):
    if references_type(source, "LocalHarnessState"):
        aggregate_ui_consumers.add(path.relative_to(ROOT).as_posix())
if aggregate_ui_consumers:
    die(
        "UI must consume Feature/Shell projections instead of LocalHarnessState: "
        + ", ".join(sorted(aggregate_ui_consumers))
    )

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



# Stage-3 Chat/Work Execution Ports must remain complete lifecycle contracts.
for relative, request_type, result_type in (
    (
        "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkExecutionPort.kt",
        "LocalWorkExecutionRequest",
        "LocalWorkExecutionResult",
    ),
    (
        "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatExecutionPort.kt",
        "LocalChatExecutionRequest",
        "LocalChatExecutionResult",
    ),
):
    contract = strip_comments(read(relative))
    for required in (
        request_type,
        result_type,
        "targetSessionId",
        "timeoutMillis",
        "recoverInterrupted",
        "DELIVERED",
        "BLOCKED",
        "CANCELLED",
        "FAILED",
        "execute(",
        "cancel(",
        "cancelAndJoin(",
    ):
        if required not in contract:
            die(relative + " lost stage-3 execution lifecycle capability: " + required)

feature_execution_composition = strip_comments(read(COMPOSITION_PATH))
for adapter in (
    "LocalWorkAutomationExecutionAdapter",
    "LocalChatAutomationExecutionAdapter",
):
    if adapter not in feature_execution_composition:
        die("Automation must enter the complete Feature Execution Port through " + adapter)

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

feature_page_host_path = "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeaturePageContent.kt"
feature_page_host = strip_comments(read(feature_page_host_path))
if "LocalFeatureCatalog.ownerOf(page)" not in feature_page_host:
    die("Feature page host must resolve its contribution owner through LocalFeatureCatalog")
if re.search(r"\bwhen\s*\(\s*page\s*\)", feature_page_host):
    die("central Feature page host must not dispatch product pages with when(page)")
if "LocalFeatureUiContribution" not in feature_page_host:
    die("Feature page host lost the Feature UI contribution contract")
if "LocalConversationSurfaceState" in feature_page_host:
    die("central Feature page host must not broadcast Chat surface state to every Feature contribution")

for required in ("drawerActions", "backAction", "restorePage"):
    if required not in feature_page_host:
        die("Feature UI contribution lost navigation ownership contract: " + required)

feature_contribution_paths = {
    "SHELL": "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalShellUiContribution.kt",
    "CHAT": "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalChatUiContribution.kt",
    "WORK": "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalWorkUiContribution.kt",
    "AUTOMATION": "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalAutomationUiContribution.kt",
    "TOOLS": "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalToolsUiContribution.kt",
    "SETTINGS": "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalSettingsUiContribution.kt",
}
if set(feature_contribution_paths) != module_ids:
    die(
        "Feature UI contribution owners must match LocalFeatureModuleId exactly: "
        + ", ".join(sorted(set(feature_contribution_paths) ^ module_ids))
    )
for module_id, relative in feature_contribution_paths.items():
    contribution = strip_comments(read(relative))
    own_marker = f"LocalFeatureModuleId.{module_id}"
    if contribution.count(own_marker) != 1:
        die(f"Feature UI contribution {module_id} must declare its owner exactly once")
    foreign_markers = {
        other for other in module_ids
        if other != module_id and f"LocalFeatureModuleId.{other}" in contribution
    }
    if foreign_markers:
        die(
            f"Feature UI contribution {module_id} must not compose sibling owners: "
            + ", ".join(sorted(foreign_markers))
        )
    if "LocalHarnessViewModel" in contribution:
        die(
            f"Feature UI contribution {module_id} must consume narrow state/actions instead of LocalHarnessViewModel"
        )

    if module_id != "SHELL":
        for required in ("drawerActions =", "backAction =", "restorePage ="):
            if required not in contribution:
                die(f"Feature UI contribution {module_id} lost {required.strip(' =')} ownership")

feature_shell = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt"
))
for required in (
    "localFeatureOwnedBackAction(",
    "localFeatureRestoreStack(",
    "openDrawerEntry(LocalFeatureDrawerEntry.WORKSPACE)",
    "openDrawerEntry(LocalFeatureDrawerEntry.RUN_CENTER)",
    "openDrawerEntry(LocalFeatureDrawerEntry.GROUP_CHAT)",
    "openDrawerEntry(LocalFeatureDrawerEntry.PERSONA_GALLERY)",
    "openDrawerEntry(LocalFeatureDrawerEntry.DIARY)",
    "openDrawerEntry(LocalFeatureDrawerEntry.TASKS)",
    "openDrawerEntry(LocalFeatureDrawerEntry.TOOLS)",
    "openDrawerEntry(LocalFeatureDrawerEntry.SETTINGS)",
):
    if required not in feature_shell:
        die("Shell lost Feature-owned navigation dispatch: " + required)
if re.search(r"openFeatureFromDrawer\(\s*LocalFeaturePage\.", feature_shell):
    die("Shell must not hard-code product Drawer route ownership")

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
