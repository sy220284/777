#!/usr/bin/env python3
"""Validate the current Architecture 3.0 ownership and runtime boundaries.

This guard checks ownership, dependency direction, writable-state boundaries, composition roots,
and current execution contracts directly. It does not freeze implementation spelling, method-body
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

RUNTIME_KERNEL_PATH = "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt"
APPLICATION_PATH = "app/src/main/java/com/labteto/dshmobile/DshApplication.kt"
COMPOSITION_PATH = "app/src/main/java/com/labteto/dshmobile/local/LocalFeatureExecutionPortModule.kt"
EXECUTION_STATUS_PATH = "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalExecutionStatus.kt"


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

# Shared Capability packages stay Feature-agnostic under the current ownership graph.
SHARED_CAPABILITY_PACKAGES = (
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
    "LocalChatAutomationPolicy",
}


# UI may consume DTOs and presentation facades/projections; Feature implementation objects and
# top-level Feature behavior/values stay behind the Feature UI/API boundary.
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
    "Policy",
)


# These Shared boundary files must remain independent from product Feature internals.
SHARED_BOUNDARY_FILES = (
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalCurrentSessionSnapshotProvider.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionControlProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalRequestContextProjection.kt",
)

RUNTIME_PROJECTION_UPDATE_OWNER = "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt"
RUNTIME_AGGREGATE_WRITE_ADAPTERS = {
    RUNTIME_PROJECTION_UPDATE_OWNER,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalForegroundSessionLoader.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalRuntimeBootstrapComposition.kt",
}

def writes_runtime_aggregate(source: str) -> bool:
    # Match Kotlin's trailing lambda and parenthesized calls, ignoring comments.
    return re.search(
        r"\bruntimeStateStore\s*\.\s*projection\s*\.\s*update\s*(?:\(|\{)",
        strip_comments(source),
    ) is not None

NARROW_PORT_PATHS = (
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatExecutionPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTurnPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkExecutionPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionLifecyclePort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsManagementPort.kt",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalDiagnosticsPort.kt",
)

CURRENT_OWNER_SYMBOLS = {
    "LocalAgentRuntimeLimits": "app/src/main/java/com/labteto/dshmobile/local/agent/",
    "structuredWorkState": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "LocalWorkCueKind": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "extractLocalWorkCueSnippet": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "LocalChatContextRefreshCoordinator": "app/src/main/java/com/labteto/dshmobile/local/chat/",
    "LocalSubagentRunner": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "LocalSubagentRunnerFactory": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "LocalPersistentJobRecoveryCoordinator": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "LocalWorkTurnPromptContext": "app/src/main/java/com/labteto/dshmobile/local/work/",
    "LocalChatTurnCoordinator": "app/src/main/java/com/labteto/dshmobile/local/chat/",
    "LocalChatReplyCoordinator": "app/src/main/java/com/labteto/dshmobile/local/chat/",
    "boundedChatRequestHistory": "app/src/main/java/com/labteto/dshmobile/local/chat/",
    "editedChatUserModelMessage": "app/src/main/java/com/labteto/dshmobile/local/chat/",
    "LocalSessionCoordinator": "app/src/main/java/com/labteto/dshmobile/local/session/",
    "LocalSessionRepository": "app/src/main/java/com/labteto/dshmobile/local/session/",
    "LocalAgentRuntimeSettings": "app/src/main/java/com/labteto/dshmobile/local/agent/",
    "LocalModelConfigContract": "app/src/main/java/com/labteto/dshmobile/local/model/",
    "LocalExecutionStatus": "app/src/main/java/com/labteto/dshmobile/local/runtime/",
    "LocalChatAutomationPolicy": "app/src/main/java/com/labteto/dshmobile/local/chat/",
    "LocalHarnessPreferences": "app/src/main/java/com/labteto/dshmobile/local/persistence/",
}



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
    return set(re.findall(r"^import\s+([^\s]+)(?:\s+as\s+\w+)?\s*$", strip_comments(source), re.MULTILINE))


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


def declares_symbol(source: str, symbol: str) -> bool:
    return re.search(
        rf"^\s*(?:(?:public|internal|private|protected|data|enum|sealed|annotation|value|open|abstract)\s+)*"
        rf"(?:class|interface|object|typealias|fun|val|const\s+val)\s+{re.escape(symbol)}\b",
        strip_comments(source),
        re.MULTILINE,
    ) is not None




def local_references(source: str) -> set[str]:
    """Find imports and direct fully-qualified references without string/comment false positives."""
    cleaned = strip_comments(source)
    cleaned = re.sub(r'"""[\s\S]*?"""', '""', cleaned)
    cleaned = re.sub(r'"(?:\\.|[^"\\])*"', '""', cleaned)
    cleaned = re.sub(r"'(?:\\.|[^'\\])*'", "''", cleaned)
    direct = set(re.findall(
        r"\bcom\.labteto\.dshmobile\.local\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*",
        cleaned,
    ))
    return imports(source) | direct


def assembled_feature_factories(shell: str, expected: set[str]) -> set[str]:
    """Verify the actual shell listOf calls, not merely declared UI contributions."""
    anchor = re.search(r"\bval\s+featureContributions\s*=\s*listOf\s*\(", shell)
    if anchor is None:
        raise ValueError("Shell lost the featureContributions assembly list")
    depth, cursor, beginning = 1, anchor.end(), anchor.end()
    quote = None
    while cursor < len(shell) and depth:
        if shell.startswith('"""', cursor):
            cursor = shell.find('"""', cursor + 3)
            if cursor < 0:
                raise ValueError("unterminated string in Feature assembly")
            cursor += 3
            continue
        char = shell[cursor]
        if quote:
            if char == "\\":
                cursor += 2
                continue
            if char == quote:
                quote = None
        elif char in ("'", '"'):
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        cursor += 1
    if depth:
        raise ValueError("unterminated Feature assembly list")
    body = shell[beginning:cursor - 1]
    calls = re.findall(r"\b(local[A-Za-z_0-9]+FeatureUiContribution)\s*\(", body)
    duplicates = sorted({name for name in calls if calls.count(name) != 1})
    missing = sorted(expected - set(calls))
    extra = sorted(set(calls) - expected)
    if duplicates or missing or extra:
        raise ValueError(f"Shell assembly mismatch: missing={missing}; duplicate={duplicates}; unexpected={extra}")
    return set(calls)


def self_test() -> None:
    target = "com.labteto.dshmobile.local.work.LocalSubagentRunner"
    assert target in local_references("val runner = " + target + "()")
    assert target in local_references("import " + target + " as Runner")
    assert target not in local_references('val label = "' + target + '"')
    assert target not in local_references("// " + target)
    # A qualified implementation method must not evade the UI internal suffix check.
    qualified = "com.labteto.dshmobile.local.work.LocalWorkCoordinator.execute"
    assert any(part.endswith(UI_INTERNAL_IMPLEMENTATION_SUFFIXES) for part in qualified.split(".")[5:])
    sample = ("val featureContributions = listOf("
              "localShellFeatureUiContribution(), localToolsFeatureUiContribution())")
    factories = {"localShellFeatureUiContribution", "localToolsFeatureUiContribution"}
    assert assembled_feature_factories(sample, factories) == factories
    assert writes_runtime_aggregate("runtimeStateStore.projection.update { it }")
    assert writes_runtime_aggregate("runtimeStateStore.projection.update({ it })")
    assert not writes_runtime_aggregate("// runtimeStateStore.projection.update { it }")
    for invalid in (
        sample.replace(", localToolsFeatureUiContribution()", ""),
        sample.replace("localToolsFeatureUiContribution()", "localShellFeatureUiContribution()"),
        sample.replace("localToolsFeatureUiContribution()", "localUnknownFeatureUiContribution()"),
    ):
        try:
            assembled_feature_factories(invalid, factories)
        except ValueError:
            pass
        else:
            raise AssertionError("Feature assembly regression unexpectedly passed")



if "--self-test" in sys.argv:
    self_test()
    print("[architecture-3] ownership/reference guard self-test passed")
    sys.exit(0)

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

# Current domain symbols must have one declaration under their architectural owner.
production_sources = list(kotlin_sources_under(APP_SOURCE_ROOT))
for symbol, owner_prefix in CURRENT_OWNER_SYMBOLS.items():
    declarations = [
        path.relative_to(ROOT).as_posix()
        for path, source in production_sources
        if declares_symbol(source, symbol)
    ]
    if len(declarations) != 1:
        die(
            f"{symbol} must have exactly one production declaration under {owner_prefix}; "
            f"found {declarations}"
        )
    if not declarations[0].startswith(owner_prefix):
        die(
            f"{symbol} belongs to {owner_prefix}, found declaration at {declarations[0]}"
        )

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
        die("foreground composition contains Chat domain restore semantics: " + forbidden)


# App shell lifecycle hooks must enter local product behavior through presentation boundaries.
main_activity_imports = local_references(read("app/src/main/java/com/labteto/dshmobile/MainActivity.kt"))
if any(
    item == "com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator"
    or item.startswith("com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator.")
    for item in main_activity_imports
):
    die(
        "MainActivity bypasses presentation ownership for ChatGPT refresh; "
        "use the presentation account workflow so remote catalog refresh and local profile reconciliation stay atomic"
    )

# ---- Dependency direction --------------------------------------------------

for feature_name, forbidden_prefixes in FEATURE_FORBIDDEN_IMPORT_PREFIXES.items():
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / feature_name):
        source_imports = local_references(source)
        for prefix in forbidden_prefixes:
            if any(item.startswith(prefix) for item in source_imports):
                die(
                    f"{path.relative_to(ROOT)} imports sibling Feature internals through {prefix}; "
                    "use provider-owned API/Port or a Shared Capability"
                )

for package_name in SHARED_CAPABILITY_PACKAGES:
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / package_name):
        source_imports = local_references(source)
        for prefix in FEATURE_INTERNAL_IMPORT_PREFIXES:
            if any(item.startswith(prefix) for item in source_imports):
                die(
                    f"{path.relative_to(ROOT)} makes Shared Capability depend on Feature internal {prefix}; "
                    "invert the dependency through a neutral Shared contract"
                )
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

for relative in SHARED_BOUNDARY_FILES:
    source_imports = local_references(read(relative))
    for prefix in FEATURE_INTERNAL_IMPORT_PREFIXES:
        if any(item.startswith(prefix) for item in source_imports):
            die(
                f"{relative} is a Shared Capability boundary but imports Feature internal {prefix}"
            )

# Shared Session/Memory remain one-way boundaries; reverse Feature dependencies fail directly.
def format_edges(edges: set[tuple[str, str]]) -> str:
    return ", ".join(f"{path} -> {imported}" for path, imported in sorted(edges))


shared_reverse_dependency_edges: set[tuple[str, str]] = set()
for package_name in ("session", "memory"):
    for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / package_name):
        relative = path.relative_to(ROOT).as_posix()
        for imported in local_references(source):
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
    for imported in local_references(source):
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

# Settings is a product Feature; cross-Feature internals are forbidden in the current graph.
settings_internal_edges: set[tuple[str, str]] = set()
for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT / "settings"):
    relative = path.relative_to(ROOT).as_posix()
    for imported in local_references(source):
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
    for imported in local_references(source):
        if not imported.startswith("com.labteto.dshmobile.local."):
            continue
        if imported.startswith(UI_ALLOWED_PRESENTATION_IMPORT_PREFIXES):
            continue
        symbol = imported.rsplit(".", 1)[-1]
        if any(part.endswith(UI_INTERNAL_IMPLEMENTATION_SUFFIXES) for part in imported.split(".")[5:]):
            ui_internal_edges.add((relative, imported))
        if imported in feature_ui_behavior_symbols or any(
            imported.startswith(behavior + ".") for behavior in feature_ui_behavior_symbols
        ):
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


# ---- Runtime Kernel current contract ----------------------------------------

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
        die("LocalRuntimeKernel contains product/Feature semantics: " + forbidden)

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
if "LocalRuntimeKernel" not in application:
    die("DshApplication must inject the process Runtime Kernel")
kernel_direct = "localRuntimeKernel.start()"
kernel_provider = "localRuntimeKernelProvider.get().start()"
if kernel_direct not in application and kernel_provider not in application:
    die("DshApplication must eagerly start the main-process Runtime Kernel")
if kernel_provider in application:
    worker_guard = "if (isMcpWorkerProcess(Application.getProcessName(), packageName)) return"
    if worker_guard not in application or application.index(worker_guard) >= application.index(kernel_provider):
        die("MCP worker process must return before eager main-process Runtime Kernel start")


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
allowed_root_types = {"LocalHarnessState"}
root_types = declared_top_level_types(aggregate_models)
if root_types != allowed_root_types:
    die(
        "root aggregate file may only declare LocalHarnessState; found extra types: "
        + ", ".join(sorted(root_types - allowed_root_types))
    )

usage_api = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/LocalUsageMode.kt"))
if declared_top_level_types(usage_api) != {"LocalUsageMode"}:
    die("the neutral usage mode API must declare exactly LocalUsageMode")

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
            f"LocalHarnessState duplicates {class_name} field(s): "
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
        die("SettingsFeature contains Chat-owned style guard semantics: " + chat_owned_setting)

for domain_owned_setting in (
    "KEY_MAIN_MAX_STEPS",
    "KEY_SUBAGENT_MAX_STEPS",
    "KEY_MODEL_ATTEMPTS",
    "KEY_WORKER_PROFILE_ID",
):
    if domain_owned_setting in settings_coordinator:
        die("SettingsFeature contains Agent/Model configuration fact: " + domain_owned_setting)

runtime_defaults = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessDefaults.kt"
))
for domain_owned_default in (
    "DEFAULT_MODEL",
    "DEFAULT_BASE_URL",
    "DEFAULT_MODEL_ATTEMPTS",
    "DEFAULT_MAIN_MAX_STEPS",
    "DEFAULT_SUBAGENT_MAX_STEPS",
    "KEY_MODEL",
    "KEY_BASE_URL",
):
    if domain_owned_default in runtime_defaults:
        die("Runtime contains domain-owned configuration fact: " + domain_owned_default)

for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    if "valueOf(result.status.name)" in source:
        die(relative + " maps execution state by enum name; use LocalExecutionStatus")
    if (
        '"local_harness"' in source
        and relative != "app/src/main/java/com/labteto/dshmobile/local/persistence/LocalHarnessPreferences.kt"
    ):
        die(relative + " duplicates the local Harness preferences container; use LocalHarnessPreferences")

for path, source in kotlin_sources_under(LOCAL_SOURCE_ROOT):
    relative = path.relative_to(ROOT).as_posix()
    if "runtimeStateStore.mutableState" in source or "runtime.mutableState" in source:
        die(f"{relative} bypasses the Runtime projection with writable aggregate state")
    if writes_runtime_aggregate(source) and relative not in RUNTIME_AGGREGATE_WRITE_ADAPTERS:
        die(
            f"{relative} introduces a new aggregate write seam; "
            "use a domain StatePort or explicit Runtime projection command"
        )
    allowed_aggregate_flow_declarations = {
        "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt",
        "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt",
        "app/src/main/java/com/labteto/dshmobile/local/LocalFeatureStatePortComposition.kt",
    }
    if re.search(r"\bMutableStateFlow\s*<\s*LocalHarnessState\s*>", source) and relative not in allowed_aggregate_flow_declarations:
        die(f"{relative} declares an aggregate writable flow outside the Runtime/adapter owner")

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
    if "MutableStateFlow<LocalHarnessState>" in source:
        die(relative + " exposes writable aggregate state through a 3.0 Port")

    source_imports = local_references(source)
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



# Chat/Work Execution Ports must expose the complete current lifecycle contract.
execution_status_contract = strip_comments(read(EXECUTION_STATUS_PATH))
for terminal_status in ("DELIVERED", "SKIPPED", "BLOCKED", "CANCELLED", "FAILED"):
    if terminal_status not in execution_status_contract:
        die(EXECUTION_STATUS_PATH + " lost shared terminal status: " + terminal_status)

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
        "LocalExecutionStatus",
        "execute(",
        "cancel(",
        "cancelAndJoin(",
    ):
        if required not in contract:
            die(relative + " lost required execution lifecycle capability: " + required)

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

# V6.3 reinstates the native screen while preserving Architecture 3.0 business ownership.
# Historical backend-only "no visual Feature screens" assertions are no longer valid.
if not (ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt").is_file():
    die("V6.3 native conversation screen missing")

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

# ---- Architecture 3.0: shared Model/Usage/Context ownership regression gates ----

model_error_contract = "app/src/main/java/com/labteto/dshmobile/local/LocalModelException.kt"
web_error_contract = "app/src/main/java/com/labteto/dshmobile/local/LocalWebException.kt"
model_transport = "app/src/main/java/com/labteto/dshmobile/local/DeepSeekClient.kt"
web_provider = "app/src/main/java/com/labteto/dshmobile/local/LocalWebProvider.kt"
if "class LocalModelException(" not in strip_comments(read(model_error_contract)):
    die("Shared Model error contract was removed")
if "class LocalWebException(" not in strip_comments(read(web_error_contract)):
    die("Shared Web error contract was removed")
if "class LocalModelException(" in strip_comments(read(model_transport)):
    die("Model transport reintroduced ownership of the shared error type")
if "class LocalWebException(" in strip_comments(read(web_provider)):
    die("Web provider reintroduced ownership of the shared error type")

context_contract = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalContextCheckpointEnvelope.kt"
))
context_projection = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalRequestContextProjection.kt"
))
pressure_contract = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/context/LocalPromptPressure.kt"
))
history_compactor = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalHistoryCompactor.kt"
))
if "enum class LocalHistorySummaryMode" not in context_contract or "data class LocalPromptPressure" not in pressure_contract:
    die("Shared Context lost neutral mode/pressure ownership")
if "import com.labteto.dshmobile.local.model.LocalHistorySummaryMode" in context_contract:
    die("Shared Context checkpoint contract must not import Model policy mode")
if "import com.labteto.dshmobile.local.model.LocalPromptPressure" in context_projection:
    die("Shared Context projection must not import Model pressure value")
if "enum class LocalHistorySummaryMode" in history_compactor or "data class LocalPromptPressure" in strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalPromptPressure.kt"
)):
    die("Shared Context values must not be redefined under Model")
if "history_compactor_v1" not in context_contract or "history_compactor_v2" not in context_contract:
    die("History checkpoint backward compatibility was removed")

usage_arithmetic = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/usage/UsageArithmetic.kt"
))
usage_contract = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/usage/ReportedTokenUsage.kt"
))
usage_analytics = strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/local/TokenUsageAnalytics.kt"
))
if "import com.labteto.dshmobile.local.model." in usage_arithmetic or "DeepSeekTokenUsage" in usage_arithmetic:
    die("Shared Usage arithmetic must not depend on provider-specific model types")
for usage_path, usage_source in kotlin_sources_under(LOCAL_SOURCE_ROOT / "usage"):
    for imported in local_references(usage_source):
        if imported.startswith("com.labteto.dshmobile.local.model."):
            die(
                str(usage_path.relative_to(ROOT))
                + " creates a Shared Usage -> Model reverse dependency: " + imported
            )
if "data class ReportedTokenUsage" not in usage_contract:
    die("Shared Usage lost provider-neutral reported token contract")
if "DeepSeekUsageTracker" in usage_analytics:
    die("Usage analytics store must not depend on provider tracker or adapters")
if "TokenUsageDatabase" not in usage_analytics:
    die("Usage analytics lost its sole SQLite request/totals owner")
if "DshApplication" in strip_comments(read(
    "app/src/main/java/com/labteto/dshmobile/connection/HostsStore.kt"
)):
    die("Settings persistence must not depend on Application entrypoint")
if "ThemeBootstrapPreferences.store(context" not in read(
    "app/src/main/java/com/labteto/dshmobile/connection/HostsStore.kt"
):
    die("DataStore->bootstrap theme mirror ordering was removed")

# ---- R4 genuine isolated MCP process + standalone V6 boot --------------------
# These are production wiring invariants, not claims that device tests passed.
worker_manifest = read("app/src/main/AndroidManifest.xml")
if not (
    'android:name=".local.tools.ipc.McpPluginWorkerService"' in worker_manifest
    and 'android:process=":mcp_worker"' in worker_manifest
    and 'android:exported="false"' in worker_manifest
):
    die("R4 MCP worker must be non-exported in an independent Android process")
if "transportFactory = { endpoint ->" not in plugin_composition or (
    "stdioTransportFactory = { command, workingDirectory ->" not in plugin_composition
) or plugin_composition.count("McpPluginWorkerTransport(") < 2:
    die("R4 production MCP HTTP and stdio must both use Binder worker without local fallback")
main_activity = strip_comments(read("app/src/main/java/com/labteto/dshmobile/MainActivity.kt"))
if "setContent {" not in main_activity or "AppRoot(" not in main_activity:
    die("Native V6 AppRoot must remain Android's only UI entry")
for retired_path in ("ui-pack-zhaoxi", "ui-pack-v6", "ui-host", "ui-contract"):
    if (ROOT / retired_path).exists():
        die("Retired UI3 and Zhaoxi module remains: " + retired_path)

worker_process_guard = strip_comments(read(APPLICATION_PATH))
if "if (isMcpWorkerProcess(Application.getProcessName(), packageName)) return" not in worker_process_guard:
    die("R4 isolated worker must not bootstrap the full Agent runtime")

print("[architecture-3] OK: current ownership, dependency direction, state boundaries, and runtime contracts hold")
