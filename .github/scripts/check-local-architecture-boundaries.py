#!/usr/bin/env python3
"""Ratchet architectural hotspots so new features cannot silently re-centralize the app."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]

LOCAL_SOURCE_ROOT = ROOT / "app/src/main/java/com/labteto/dshmobile/local"

ENGINE_MAX_PUBLIC_METHODS = 0
ENGINE_MAX_INTERNAL_METHODS = 1
ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES = 17
AGGREGATE_STATE_MAX_FIELDS = 28
ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST = {
    "chatTurnPort",
    "diagnosticsPort",
    "sessionLifecyclePort",
    "toolsManagementPort",
    "workTurnPort",
}
ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST = {
    "automationChatCoordinator",
    "automationWorkCoordinator",
}
ENGINE_COMPOSITION_BRIDGE_ALLOWLIST = (
    ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST
    | ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST
)
ENGINE_STAGE3_FEATURE_ROOT_CANDIDATES = (
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
)
ENGINE_STAGE3_FEATURE_ROOT_ALLOWLIST = set()
ENGINE_REMOVED_PRIVATE_BUSINESS_METHODS = {
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

HOTSPOT_CONSTRUCTOR_DEPENDENCY_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatTurnExecutor.kt": ("LocalGroupChatTurnExecutor", 16),
    "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt": ("LocalSubagentRunner", 23),
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt": ("LocalAutomationChatCoordinator", 14),
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt": ("LocalModelRequestCoordinator", 4),
}

RUNTIME_ENGINE_FORBIDDEN_PATHS = (
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt",
)
PROJECTION_FIELD_BUDGETS = {
    "LocalHarnessSettingsState": 17,
    "LocalHarnessTaskState": 4,
    "LocalHarnessShellState": 10,
}

ENGINE_CONSUMER_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalFeatureExecutionPortModule.kt",
}

UI_AGGREGATE_STATE_ALLOWLIST = set()


def die(message: str) -> None:
    print(f"[architecture-guard] {message}", file=sys.stderr)
    raise SystemExit(1)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//.*$", "", source, flags=re.MULTILINE)


def has_typed_property(source: str, type_name: str) -> bool:
    """Match a retained Kotlin property by type, including generics, without coupling to its variable name."""
    return re.search(
        rf"\b(?:(?:private|internal|public|protected)\s+)?(?:val|var)\s+[A-Za-z0-9_]+\s*:\s*{re.escape(type_name)}(?![A-Za-z0-9_])",
        source,
    ) is not None


def has_call(source: str, receiver: str, method: str) -> bool:
    """Match ownership-routing calls while ignoring argument spelling and formatting."""
    return re.search(
        rf"\b{re.escape(receiver)}\s*\.\s*{re.escape(method)}\s*\(",
        source,
    ) is not None


# Provider Features must not import sibling product internals.
# Cross-feature collaboration goes through provider-owned APIs/Ports or Shared Capabilities.
for feature_name, forbidden_prefixes in (
    ("chat", (
        "com.labteto.dshmobile.local.work.",
        "com.labteto.dshmobile.local.automation.",
    )),
    ("work", (
        "com.labteto.dshmobile.local.chat.",
        "com.labteto.dshmobile.local.automation.",
    )),
):
    for feature_source_path in (LOCAL_SOURCE_ROOT / feature_name).rglob("*.kt"):
        feature_source = strip_comments(feature_source_path.read_text(encoding="utf-8"))
        relative = feature_source_path.relative_to(ROOT).as_posix()
        for forbidden_prefix in forbidden_prefixes:
            if re.search(
                rf"^import\s+{re.escape(forbidden_prefix)}",
                feature_source,
                re.MULTILINE,
            ):
                die(
                    f"{relative} imports sibling Feature internals through {forbidden_prefix}; "
                    "depend on a provider-owned API/Port or Shared Capability instead"
                )

timeline_rewrite_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalTimelineRewriteTransaction.kt")
)
for forbidden_work_timeline_type in ("LocalGoal", "LocalTodoItem"):
    if forbidden_work_timeline_type in timeline_rewrite_source:
        die(
            "Chat timeline rewrite state must not persist WorkFeature controls: "
            + forbidden_work_timeline_type
        )

for shared_context_path in (LOCAL_SOURCE_ROOT / "context").rglob("*.kt"):
    shared_context_source = strip_comments(shared_context_path.read_text(encoding="utf-8"))
    if "import com.labteto.dshmobile.local.work." in shared_context_source:
        relative = shared_context_path.relative_to(ROOT).as_posix()
        die(
            f"{relative} makes Shared Context depend on WorkFeature internals; "
            "inject a neutral context projection policy from composition instead"
        )

chat_user_activity_contract_path = (
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatUserActivityPort.kt"
)
chat_user_activity_contract = strip_comments(read(chat_user_activity_contract_path))
if "internal fun interface LocalChatUserActivityPort" not in chat_user_activity_contract:
    die("ChatFeature must own the LocalChatUserActivityPort cross-feature contract")
if "com.labteto.dshmobile.local.automation" in chat_user_activity_contract:
    die("Chat-owned user-activity contract must stay Automation-agnostic")

automation_user_activity_adapter_path = (
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatUserActivityAdapter.kt"
)
automation_user_activity_adapter = strip_comments(read(automation_user_activity_adapter_path))
if (
    "class LocalAutomationChatUserActivityAdapter" not in automation_user_activity_adapter
    or ": LocalChatUserActivityPort" not in automation_user_activity_adapter
):
    die("AutomationFeature must adapt the Chat-owned user-activity contract")
if (
    ROOT / "app/src/main/java/com/labteto/dshmobile/local/automation/LocalChatUserActivityPort.kt"
).exists():
    die("legacy Automation-owned LocalChatUserActivityPort must not return")

# Removed package paths are permanent exits. Catch stale imports in source/tests before
# Kotlin compilation so a completed ownership migration cannot silently depend on its old package.
LEGACY_MOVED_IMPORTS = {
    "com.labteto.dshmobile.local.runtime.structuredWorkState":
        "com.labteto.dshmobile.local.work.structuredWorkState",
    "com.labteto.dshmobile.local.runtime.LocalWorkCueKind":
        "com.labteto.dshmobile.local.work.LocalWorkCueKind",
    "com.labteto.dshmobile.local.runtime.extractLocalWorkCueSnippet":
        "com.labteto.dshmobile.local.work.extractLocalWorkCueSnippet",
    "com.labteto.dshmobile.local.LocalChatContextRefreshCoordinator":
        "com.labteto.dshmobile.local.chat.LocalChatContextRefreshCoordinator",
}
for kotlin_root in (
    ROOT / "app/src/main",
    ROOT / "app/src/test",
    ROOT / "app/src/androidTest",
):
    if not kotlin_root.exists():
        continue
    for kotlin_path in kotlin_root.rglob("*.kt"):
        kotlin_source = strip_comments(kotlin_path.read_text(encoding="utf-8"))
        for legacy_import, current_import in LEGACY_MOVED_IMPORTS.items():
            if re.search(
                rf"^import\s+{re.escape(legacy_import)}\s*$",
                kotlin_source,
                re.MULTILINE,
            ):
                relative = kotlin_path.relative_to(ROOT).as_posix()
                die(
                    f"{relative} still imports removed architecture path {legacy_import}; "
                    f"use {current_import}"
                )

# Physical boundaries must also be Kotlin boundaries; root-package leakage defeats import guards.
for source_path in LOCAL_SOURCE_ROOT.rglob("*.kt"):
    relative = source_path.relative_to(LOCAL_SOURCE_ROOT)
    expected_package = "com.labteto.dshmobile.local"
    if relative.parent.parts:
        expected_package += "." + ".".join(relative.parent.parts)
    source = source_path.read_text(encoding="utf-8")
    declared = re.search(r"^package\s+([A-Za-z0-9_.]+)\s*$", source, re.MULTILINE)
    if declared is None or declared.group(1) != expected_package:
        die(f"directory/package mismatch: {relative}; expected {expected_package}")
    if re.search(r"^import\s+[^\n]+\.\*\s*$", source, re.MULTILINE):
        die(f"architecture-sensitive source must use explicit imports: {relative}")

domain_models = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/LocalHarnessModels.kt"))
aggregate_contracts = {"LocalHarnessState", "LocalUsageMode"}
declared_contracts = set(re.findall(
    r"^\s*(?:(?:data|enum|sealed|annotation|value|internal|public)\s+)*(?:class|interface|object|typealias)\s+([A-Za-z0-9_]+)",
    domain_models, re.MULTILINE,
))
if declared_contracts != aggregate_contracts:
    die(f"root aggregate may only declare shared aggregate contracts: {sorted(declared_contracts - aggregate_contracts)}")
if "deviceApprovalLease" in domain_models:
    die("device approval lease is Work-owned state and must not return to LocalHarnessState")

work_state_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkState.kt")
)
if "val deviceApprovalLease: Boolean" not in work_state_source:
    die("device approval lease must stay owned by LocalWorkState")

interaction_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/interaction/LocalInteractionCoordinator.kt")
)
if "private val state: LocalInteractionStatePort" not in interaction_coordinator_source:
    die("LocalInteractionCoordinator must depend on its narrow interaction state port")
if "private val state: MutableStateFlow<LocalHarnessState>" in interaction_coordinator_source:
    die("LocalInteractionCoordinator must not own the aggregate mutable app state")

agent_run_recovery_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt")
)
for forbidden_work_recovery_semantic in (
    "LocalWorkCheckpoint",
    "<work-checkpoint>",
    "最近持久工作检查点",
):
    if forbidden_work_recovery_semantic in agent_run_recovery_source:
        die(
            "Shared Agent recovery must not interpret WorkFeature checkpoints: "
            + forbidden_work_recovery_semantic
        )
if "LocalAgentRunRecoveryContextPolicy" not in agent_run_recovery_source:
    die("Shared Agent recovery must expose a neutral continuation context policy")

work_recovery_policy_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRecoveryContextPolicy.kt")
)
if (
    "LocalWorkCheckpoint.latestFrom" not in work_recovery_policy_source
    or "LocalAgentRunRecoveryContextPolicy" not in work_recovery_policy_source
):
    die("WorkFeature must own Work checkpoint recovery decoration")

runtime_state_store_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt")
)
if re.search(r"\binternal\s+val\s+mutableState\s*:", runtime_state_store_source):
    die("LocalRuntimeStateStore must not expose its writable aggregate state")

for required_transition_owner in (
    "private var sessionTransitionInProgress = false",
    "internal val sessionTransitioning: Boolean",
    "internal fun beginSessionTransition(): Boolean",
    "internal fun endSessionTransition()",
):
    if required_transition_owner not in runtime_state_store_source:
        die("Shared Runtime must own Session transition fact: " + required_transition_owner)

# ChatFeature owns the only remaining generic aggregate-write bridge.
# All Chat coordinators consume LocalChatStatePort; Settings/Model/Work already use explicit domain ports.
aggregate_projection_migration_allowlist = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatStatePort.kt",
}
for source_path in LOCAL_SOURCE_ROOT.rglob("*.kt"):
    relative = source_path.relative_to(ROOT).as_posix()
    source = strip_comments(source_path.read_text(encoding="utf-8"))
    if "runtimeStateStore.mutableState" in source or "runtime.mutableState" in source:
        die(f"{relative} bypasses Runtime projection with the removed writable aggregate state")
    uses_migration_port = (
        "LocalAggregateProjectionPort" in source
        or "runtimeStateStore.projection.update(" in source
    )
    if (
        uses_migration_port
        and relative != "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt"
        and relative not in aggregate_projection_migration_allowlist
    ):
        die(
            f"{relative} introduces a new aggregate projection migration consumer; "
            "use a domain StatePort or explicit Runtime projection command"
        )

chat_state_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatStatePort.kt")
)
if re.search(r"\bruntimeStateStore\.projection\.update\s*\(", chat_state_port_source) is None:
    die("ChatFeature aggregate projection bridge must stay centralized in LocalChatStatePort")
for forbidden in ("work =", "mainMaxSteps =", "subagentMaxSteps =", "userRules =", "safeAutoApprovalEnabled ="):
    if forbidden in chat_state_port_source:
        die(f"LocalChatStatePort must not write non-Chat aggregate field: {forbidden}")

work_binding_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunBinding.kt")
)
if "MutableStateFlow<LocalHarnessState>" in work_binding_source or "initialState: LocalHarnessState" in work_binding_source:
    die("LocalWorkRunBinding must own LocalWorkRunState instead of writable aggregate app state")
if "val state = MutableStateFlow(initialState)" not in work_binding_source:
    die("LocalWorkRunBinding must keep one Work-owned mutable run state")
if "sessionBase: LocalHarnessSession" not in work_binding_source or "persistenceSnapshot()" not in work_binding_source:
    die("Work run persistence must materialize from its Session envelope without Session depending on Work")

session_persistence_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionPersistenceProjection.kt")
)
if "LocalWorkRunBinding" in session_persistence_source or "com.labteto.dshmobile.local.work" in session_persistence_source:
    die("Shared Session persistence must not depend on WorkFeature runtime internals")

session_control_cursor_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionControlProjection.kt")
)
if (
    "com.labteto.dshmobile.local.chat" in session_control_cursor_source
    or "com.labteto.dshmobile.local.work" in session_control_cursor_source
    or "projectSessionControlTail" in session_control_cursor_source
):
    die("Shared Session control projection must own only replay cursor semantics, not Feature event interpretation")

feature_control_projection_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalSessionControlProjection.kt")
)
if "projectSessionControlTail" not in feature_control_projection_source:
    die("Feature composition must own Chat/Work control-event projection")

# The only remaining Work-side writable aggregate adapter is the foreground composition bridge.
# Detached/foreground Work bindings themselves must own LocalWorkRunState, never LocalHarnessState.
work_aggregate_state_allowlist = {
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkStatePort.kt",
}
work_root = LOCAL_SOURCE_ROOT / "work"
for source_path in work_root.rglob("*.kt"):
    source = strip_comments(source_path.read_text(encoding="utf-8"))
    relative = source_path.relative_to(ROOT).as_posix()
    if "MutableStateFlow<LocalHarnessState>" in source and relative not in work_aggregate_state_allowlist:
        die(
            f"{relative} introduces a new writable LocalHarnessState seam inside WorkFeature; "
            "depend on Work-owned state or a narrow Shared Capability instead"
        )
    if "runtimeStateStore.mutableState" in source or "runtime.mutableState" in source:
        die(
            f"{relative} writes the Runtime aggregate directly; "
            "use LocalRuntimeProjection or a narrower capability instead"
        )

settings_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/settings/LocalHarnessSettingsCoordinator.kt")
)
if not has_typed_property(settings_coordinator_source, "LocalSettingsStatePort"):
    die("LocalHarnessSettingsCoordinator must depend on its narrow Settings state port")
if (
    "runtimeStateStore.projection.update(" in settings_coordinator_source
    or "LocalAggregateProjectionPort" in settings_coordinator_source
):
    die("LocalHarnessSettingsCoordinator must not mutate the aggregate Runtime projection directly")

settings_state_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/settings/LocalSettingsStatePort.kt")
)
if "interface LocalSettingsStatePort" not in settings_state_port_source:
    die("SettingsFeature must keep an explicit LocalSettingsStatePort boundary")
if "LocalHarnessState" in settings_state_port_source or "LocalAggregateProjectionPort" in settings_state_port_source:
    die("LocalSettingsStatePort must expose only Settings-owned projection data")

model_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt")
)
if "LocalHarnessEngine" in model_runtime_source:
    die("LocalModelRuntime must use Model/Shared capabilities instead of LocalHarnessEngine")

model_request_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalAgentModelRequestRuntime.kt")
)
model_request_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt")
)
foreground_history_compaction_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalForegroundHistoryCompactionRuntime.kt")
)
for required_shared_model_request in (
    "@Inject constructor(",
    "private val runtimeStateStore: LocalRuntimeStateStore",
    "private val sessionStorage: LocalSessionStorageRuntime",
    "private val foregroundCompaction: LocalForegroundHistoryCompactionRuntime",
    "runtimeStateStore.requestPressureStore",
    "sessionStorage.eventLogs.get(snapshot.sessionId)",
):
    if required_shared_model_request not in model_request_coordinator_source:
        die("Shared model request coordinator must be injectable and self-contained: " + required_shared_model_request)
for removed_engine_model_closure in (
    "toolSchemas:",
    "defaultEventLog:",
    "persistOverflowCompaction:",
):
    if removed_engine_model_closure in model_request_coordinator_source:
        die("Shared model request coordinator must not retain Engine composition closure: " + removed_engine_model_closure)
if "import com.labteto.dshmobile.local.work." in model_request_coordinator_source:
    die(
        "Shared model request coordinator must not depend on WorkFeature internals; "
        "consume LocalRequestContextPolicy and LocalModelAdmissionPort"
    )
for required_neutral_model_policy in (
    "contextPolicy: LocalRequestContextPolicy? = null",
    "admission: LocalModelAdmissionPort? = null",
    "admission = admission",
):
    if required_neutral_model_policy not in model_request_coordinator_source:
        die("Shared model request coordinator lost its neutral Feature extension point: " + required_neutral_model_policy)

request_context_contract_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/context/LocalRequestContextProjection.kt")
)
for forbidden_shared_context_work_contract in (
    "LocalStructuredWorkState",
    "LocalWorkContextAssessmentSnapshot",
    "structuredState(",
):
    if forbidden_shared_context_work_contract in request_context_contract_source:
        die(
            "Shared Context contract must remain Work-agnostic: "
            + forbidden_shared_context_work_contract
        )
for retired_runtime_work_semantic in (
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalStructuredWorkStateProjection.kt",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalWorkStateCueExtractor.kt",
):
    if (ROOT / retired_runtime_work_semantic).exists():
        die(
            "Work semantic interpretation must stay in WorkFeature, not Shared Runtime: "
            + retired_runtime_work_semantic
        )
prompt_pressure_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalPromptPressure.kt")
)
if "LocalWorkContextAssessmentSnapshot" in prompt_pressure_source:
    die("Shared request pressure store must use the neutral request-context assessment DTO")

work_context_policy_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRequestContextPolicy.kt")
)
for required_work_context_policy in (
    "object LocalWorkRequestContextPolicy : LocalRequestContextPolicy",
    "projectWorkRequestContext(",
    "assessWorkStepContext(",
    "structuredWorkState(",
):
    if required_work_context_policy not in work_context_policy_source:
        die("WorkFeature request-context policy is incomplete: " + required_work_context_policy)
for required_foreground_compaction_owner in (
    "class LocalForegroundHistoryCompactionRuntime",
    "runtimeStateStore.foregroundRunHandle.modelHistory",
    "runtimeStateStore.requestPressureStore.advanceGeneration(",
    "sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)",
):
    if required_foreground_compaction_owner not in foreground_history_compaction_source:
        die("Shared foreground history compaction ownership is incomplete: " + required_foreground_compaction_owner)
engine_source_for_model_boundary = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt")
)
if "private val requestPressureStore = LocalRequestPressureStore()" in engine_source_for_model_boundary:
    die("LocalHarnessEngine must not own a second request-pressure window")
if (
    "private val modelRequestCoordinator by lazy" in engine_source_for_model_boundary
    or "LocalModelRequestCoordinator(" in engine_source_for_model_boundary
):
    die("LocalHarnessEngine must consume the injected Shared model request coordinator")
if "private fun persistForegroundOverflowCompaction(" in engine_source_for_model_boundary:
    die("LocalHarnessEngine must not own visible foreground overflow persistence")
if "LocalModelAdmissionPort" not in model_request_runtime_source:
    die("Model Capability must expose a Work-agnostic model admission port")
if "import com.labteto.dshmobile.local.work." in model_request_runtime_source:
    die("LocalAgentModelRequestRuntime must not depend on WorkFeature internals")
if "admissionHandledExternally" in model_request_runtime_source:
    die("Model request runtime must not retain the legacy Work-specific admission bypass")
if "validateModelRequestAdmission(admissionRequest)" not in model_request_runtime_source:
    die("Every model request must keep the generic context-budget preflight before provider invocation")
generic_preflight_pos = model_request_runtime_source.find("validateModelRequestAdmission(admissionRequest)")
provider_invoke_pos = model_request_runtime_source.find("admission?.execute(admissionRequest, invokeProvider)")
if generic_preflight_pos < 0 or provider_invoke_pos < 0 or generic_preflight_pos > provider_invoke_pos:
    die("Generic model admission preflight must run before optional Feature admission/provider execution")

for model_source_path in (LOCAL_SOURCE_ROOT / "model").rglob("*.kt"):
    model_source = strip_comments(model_source_path.read_text(encoding="utf-8"))
    if "import com.labteto.dshmobile.local.work." in model_source:
        relative = model_source_path.relative_to(ROOT).as_posix()
        die(f"{relative} makes Model/Shared capability depend on WorkFeature internals")

for relative in (
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentModelRequestBoundary.kt",
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentModelStepExecutor.kt",
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentRunnerFactory.kt",
):
    agent_model_source = strip_comments(read(relative))
    if (
        "LocalWorkExecutionControl" in agent_model_source
        or "import com.labteto.dshmobile.local.work." in agent_model_source
    ):
        die(f"{relative} must consume LocalModelAdmissionPort instead of WorkFeature internals")

work_model_admission_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkModelAdmission.kt")
)
if (
    "LocalModelAdmissionPort" not in work_model_admission_source
    or "executeWithModelAdmission(" not in work_model_admission_source
):
    die("WorkFeature must adapt its execution control through LocalModelAdmissionPort")

work_progress_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkProgressCoordinator.kt")
)
if "LocalHarnessState" in work_progress_source or "MutableStateFlow" in work_progress_source:
    die("LocalWorkProgressCoordinator must depend only on LocalWorkStatePort")
if not has_typed_property(work_progress_source, "LocalWorkStatePort"):
    die("LocalWorkProgressCoordinator lost its Work-owned state boundary")

runtime_projection_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt")
)
if not has_typed_property(runtime_projection_source, "MutableStateFlow<LocalHarnessState>"):
    die("LocalRuntimeProjection must remain the narrow writable aggregate projection owner")

work_plan_mode_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkPlanModeCoordinator.kt")
)
if not has_call(work_plan_mode_source, "runtimeStateStore.projection", "setWorkPlanMode"):
    die("Work plan-mode visible projection must use LocalRuntimeProjection")
if not has_call(work_plan_mode_source, "runtimeStateStore.projection", "updateContextMetrics"):
    die("Work plan-mode context metrics must use LocalRuntimeProjection")
if "internal suspend fun exitWorkPlanMode(" not in work_plan_mode_source:
    die("WorkFeature must own the active-run plan exit transaction")

engine_path = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
engine = read(engine_path)
work_regenerator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkReplyRegenerator.kt")
)
if "internal suspend fun regenerate(messageId: String)" not in work_regenerator_source:
    die("WorkFeature must own final-answer regeneration")
if "private suspend fun regenerateWorkReply(" in engine:
    die("Work regeneration business must not return to LocalHarnessEngine")

group_execution_owner_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatExecutionOwner.kt")
)
chat_turn_dispatcher_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTurnDispatcher.kt")
)
if "internal suspend fun runOwnedGroupChatTurn(" not in group_execution_owner_source:
    die("ChatFeature must own group-chat foreground admission")
if "LocalSessionRuntimeRegistry.withOwner(" not in group_execution_owner_source:
    die("Group Chat must retain Shared Session ownership")
if "runOwnedGroupChatTurn(" not in chat_turn_dispatcher_source:
    die("Chat turn dispatcher must route group turns through Chat-owned execution")
if "private suspend fun runGroupChatTurn(" in engine:
    die("Group-chat foreground execution proxy must not return to LocalHarnessEngine")
if re.search(r"\brunOwnedGroupChatTurn\s*\(", strip_comments(engine)):
    die("LocalHarnessEngine must not directly invoke Chat-owned group execution")

work_turn_starter_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnStarter.kt")
)
work_agent_turn_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkAgentTurnExecutor.kt")
)
for required_work_turn_owner in (
    "internal fun startFresh(",
    "internal fun startResumed(",
    "internal fun startExisting(",
    "LocalWorkRunBinding(",
    "workRunRegistry.attach(binding)",
):
    if required_work_turn_owner not in work_turn_starter_source:
        die("Work first-turn ownership is incomplete: " + required_work_turn_owner)
if "private fun queueWorkTurnLocked(" in engine or "private fun queueExistingWorkTurnLocked(" in engine:
    die("Work first-turn binding must not return to LocalHarnessEngine")
if "workTurnStarter.startFresh(" not in engine or "workTurnStarter.startResumed(" not in engine:
    die("Engine migration call sites must route Work start/resume to WorkFeature")
for required_work_agent_owner in (
    "internal suspend fun run(",
    "LocalSessionRuntimeRegistry.withOwner(",
    "val loop = AgentLoop(",
    "modelRequests.complete(",
    "workTurnToolRuntime.execute(",
    "workModelHistoryRuntime.checkpointAtTurnBoundary(",
    "queueAutomaticWorkContinuation(",
    "workRunRegistry.finishTurn(",
):
    if required_work_agent_owner not in work_agent_turn_source:
        die("Work Agent main-loop ownership is incomplete: " + required_work_agent_owner)
if "private suspend fun runWorkAgentTurn(" in engine:
    die("Work Agent main loop must not return to LocalHarnessEngine")
if "workAgentTurnExecutor.run(" not in engine:
    die("Work first-turn composition must route execution to WorkFeature")

work_subagent_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkSubagentRuntime.kt")
)
if "internal fun runner(binding: LocalWorkRunBinding)" not in work_subagent_runtime_source:
    die("WorkFeature must own bound subagent runner composition")
if "internal suspend fun runWorkflow(" not in work_subagent_runtime_source:
    die("WorkFeature must own workflow execution and progress projection")
if "private fun workSubagents(" in engine or "private suspend fun runWorkflow(" in engine:
    die("Work subagent/workflow business must not return to LocalHarnessEngine")
if "workSubagentRuntime.runWorkflow(" not in engine:
    die("Work workflow tool must stay routed to the Work-owned runtime")

if "private suspend fun exitPlanMode(" in engine:
    die("Work plan-exit business must not return to LocalHarnessEngine")
if "exitWorkPlanMode(" not in engine:
    die("Work plan-exit tool must stay routed to the Work-owned transaction")

work_registry_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunRegistry.kt")
)
if not has_call(work_registry_source, "runtimeStateStore.projection", "projectJobs"):
    die("Work visible job projection must use LocalRuntimeProjection")
if not has_call(work_registry_source, "runtimeStateStore.projection", "projectVisibleWorkRun"):
    die("Work visible run projection must be owned by LocalWorkRunRegistry through Shared Runtime")
for required_work_send_owner in (
    "internal fun enqueueIntoLiveRun(prepared: LocalPreparedSend)",
    "coordinateOwnedLocalSend(",
    "encodeLocalAgentInboxEvent(",
    "binding.transcriptRuntime.applyMessages(",
    "persistBinding(binding)",
):
    if required_work_send_owner not in work_registry_source:
        die("Work live-run additional-input ownership is incomplete: " + required_work_send_owner)
if "sessionTransitioning = runtimeStateStore.sessionTransitioning" not in work_registry_source:
    die("Work send admission must consume the Shared Runtime Session transition fact")
if "sessionStorage.enqueueSnapshot(binding.persistenceSnapshot())" not in work_registry_source:
    die("Work run persistence must use the narrow Shared Session storage command")
if "private fun mirrorVisibleWorkRun" in engine:
    die("LocalHarnessEngine must not own Work visible run projection")
work_turn_starter_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnStarter.kt")
)
if not has_call(work_turn_starter_source, "workRunRegistry", "mirrorVisible"):
    die("Work first-turn owner must delegate visible projection to LocalWorkRunRegistry")
if has_call(engine, "workRunRegistry", "mirrorVisible"):
    die("LocalHarnessEngine must not regain Work visible projection call sites")
if "projectJobSnapshotToSessionStates" in work_registry_source:
    die("legacy Work job projection must not regain direct aggregate-state access")


approval_preferences_source = read("app/src/main/java/com/labteto/dshmobile/local/interaction/LocalApprovalPreferences.kt")
approval_coordinator_source = read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkApprovalCoordinator.kt")
if "StateFlow<Boolean>" not in approval_preferences_source or "mode.value = enabled" not in approval_preferences_source:
    die("Approval preferences must publish the single global mode flow")
if re.search(r"copy\(\s*safeAutoApprovalEnabled\s*=", approval_coordinator_source):
    die("Approval coordinator must not fan out writable global mode copies")
for forbidden_approval_aggregate in (
    "MutableStateFlow<LocalHarnessState>",
    "runtime.mutableState",
    "binding.state",
    "target.state",
):
    if forbidden_approval_aggregate in approval_coordinator_source:
        die(
            "Work approval must use LocalInteractionCoordinator instead of aggregate mutable state: "
            + forbidden_approval_aggregate
        )


def constructor_dependency_count(relative: str, class_name: str) -> int:
    source = strip_comments(read(relative))
    match = re.search(
        rf"\bclass\s+{re.escape(class_name)}\b"
        rf"(?:\s+@[A-Za-z0-9_.]+(?:\([^)]*\))?)*"
        rf"\s*(?:constructor\s*)?\((.*?)\)\s*(?::[^{{]+)?\{{",
        source,
        re.DOTALL,
    )
    if match is None:
        die(f"unable to locate {class_name} constructor in {relative}")
    return len(re.findall(r"\bprivate\s+val\s+[A-Za-z0-9_]+\s*:", match.group(1)))


for relative, (class_name, maximum) in HOTSPOT_CONSTRUCTOR_DEPENDENCY_BUDGETS.items():
    dependencies = constructor_dependency_count(relative, class_name)
    if dependencies > maximum:
        die(
            f"{class_name} has {dependencies} constructor dependencies (ratchet: {maximum}); "
            "split ownership/capabilities instead of extending dependency soup"
        )
    print(
        f"[architecture-guard] {class_name}: "
        f"{dependencies}/{maximum} constructor dependencies"
    )


automation_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt")
)
for removed_automation_engine_proxy in (
    "internal suspend fun prepareAutomationWorkSession",
    "internal suspend fun runAutomationWork",
    "internal suspend fun runAutomationChat",
):
    if removed_automation_engine_proxy in engine:
        die("LocalHarnessEngine must not reintroduce Automation execution proxy API: " + removed_automation_engine_proxy)
for required_automation_owner in (
    "chatCoordinator: LocalAutomationChatCoordinator",
    "workCoordinator: LocalAutomationWorkCoordinator",
):
    if required_automation_owner not in automation_runtime_source:
        die("AutomationRuntime must depend on Automation-owned coordinators: " + required_automation_owner)

for relative in RUNTIME_ENGINE_FORBIDDEN_PATHS:
    runtime_source = strip_comments(read(relative))
    direct_calls = sorted(set(re.findall(r"\bengine\.([A-Za-z0-9_]+)", runtime_source)))
    if direct_calls or has_typed_property(runtime_source, "LocalHarnessEngine"):
        details = ", ".join(direct_calls) if direct_calls else "typed LocalHarnessEngine dependency"
        die(
            f"{relative} reintroduced forbidden LocalHarnessEngine access: {details}; "
            "Architecture 3.0 capability runtimes must depend only on their Feature/Shared capability"
        )

# Diary privacy is a security/knowledge-boundary invariant, not a tuning preference.
# Group prompts may receive PUBLIC diary entries only. Do not relax this to "anything except PRIVATE"
# and do not bypass the central policy with a second group-memory path.
diary_recall_policy = read(
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryRecallPolicy.kt"
)
diary_recall_engine = read(
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryRecallEngine.kt"
)
if (
    "disclosure == ChatDiaryDisclosure.PUBLIC" not in diary_recall_policy
    or "canExposeDiaryToGroup(entry.disclosure)" not in diary_recall_engine
):
    die("group diary recall must stay centralized and PUBLIC-only")
if "!= ChatDiaryDisclosure.PRIVATE" in diary_recall_engine:
    die("group diary recall must not regress to the old non-private shortcut")
if (
    "crossScopeAdmissible" in diary_recall_engine
    or "crossScopeAdmissible" in diary_recall_policy
):
    die("legacy cross-scope diary bypass must not return")

memory_coordinator = read(
    "app/src/main/java/com/labteto/dshmobile/local/memory/LocalMemoryCoordinator.kt"
)
if "val recallFacts = ChatMemorySelector.shouldRecall(query)" not in memory_coordinator:
    die("precise chat facts must remain recallable in both direct and group chat")
if "!groupAudience && ChatMemorySelector.shouldRecall(query)" in memory_coordinator:
    die("group chat must not disable precise fact recall")

if "internal val state: StateFlow<LocalHarnessState>" in engine:
    die("LocalHarnessEngine must not expose aggregate runtime state")

for relative in ENGINE_CONSUMER_ALLOWLIST:
    if relative == engine_path:
        continue
    consumer_source = strip_comments(read(relative))
    if "engine.state" in consumer_source:
        die(f"{relative} must consume LocalRuntimeStateStore instead of LocalHarnessEngine.state")

settings_coordinator = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/settings/LocalHarnessSettingsCoordinator.kt")
)
settings_runtime = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt")
)
if "@Singleton" not in settings_coordinator or not has_typed_property(settings_coordinator, "LocalSettingsStatePort"):
    die("LocalHarnessSettingsCoordinator must own Settings mutations through LocalSettingsStatePort")
if "runtimeStateStore.projection" in settings_coordinator:
    die("Settings coordinator must not write the aggregate Runtime projection directly")
settings_state_port = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/settings/LocalSettingsStatePort.kt")
)
for required_settings_command in (
    "updateSettingsRuntimeLimits",
    "updateSettingsWorkerProfile",
    "updatePersonalization",
    "setChatStyleGuardEnabled",
    "setChatStyleGuardPhrases",
    "clearStyleGuardHits",
    "recordStyleGuardHits",
    "publishError",
):
    if required_settings_command not in settings_state_port:
        die("Settings state port must expose explicit Runtime projection command: " + required_settings_command)
if not has_typed_property(settings_runtime, "LocalHarnessSettingsCoordinator"):
    die("LocalSettingsRuntime must depend on the Settings capability directly")
for forbidden_proxy in (
    "engine.configureRuntimeLimits",
    "engine.configureWorkerProfile",
    "engine.configurePersonalization",
    "engine.configureChatStyleGuard",
    "engine.addChatStyleGuardPhrase",
    "engine.removeChatStyleGuardPhrase",
    "engine.clearChatStyleGuardHits",
):
    if forbidden_proxy in settings_runtime:
        die("LocalSettingsRuntime must not route owned Settings behavior back through Engine: " + forbidden_proxy)
for removed_engine_proxy in (
    "internal fun configureRuntimeLimits",
    "internal fun configureWorkerProfile",
    "internal fun configurePersonalization",
    "internal fun configureChatStyleGuard",
    "internal fun addChatStyleGuardPhrase",
    "internal fun removeChatStyleGuardPhrase",
    "internal fun clearChatStyleGuardHits",
):
    if removed_engine_proxy in engine:
        die("LocalHarnessEngine must not reintroduce Settings proxy API: " + removed_engine_proxy)

constructor = re.search(
    r"class LocalHarnessEngine @Inject\s+(?:internal\s+)?constructor\((.*?)\n\) \{",
    engine,
    re.DOTALL,
)
if constructor is None:
    die("unable to locate LocalHarnessEngine constructor")
if has_typed_property(constructor.group(1), "LocalHarnessSettingsCoordinator"):
    die("LocalHarnessEngine must not depend on the Settings capability")
dependency_count = len(re.findall(r"private val\s+[A-Za-z0-9_]+\s*:", constructor.group(1)))
if dependency_count > ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES:
    die(
        f"LocalHarnessEngine constructor has {dependency_count} dependencies "
        f"(ratchet: {ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES})"
    )

runtime_state_store = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt")
)
session_storage_runtime = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorageRuntime.kt")
)
work_run_registry = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunRegistry.kt")
)
if "MutableStateFlow(LocalHarnessState())" not in runtime_state_store:
    die("LocalRuntimeStateStore must own the aggregate runtime MutableStateFlow")
if "foregroundInteractions = LocalInteractionCoordinator(mutable)" not in runtime_state_store:
    die("LocalRuntimeStateStore must own the foreground interaction coordinator")
if "private val interactions = LocalInteractionCoordinator" in engine:
    die("LocalHarnessEngine must not own a second foreground interaction coordinator")
for required_runtime_owner in (
    "foregroundRunHandle = LocalAgentRunHandle(",
    "cancelForegroundRun(eventLog: LocalSessionEventLog)",
    "cancelForegroundRunAndJoin(eventLog: LocalSessionEventLog)",
):
    if required_runtime_owner not in runtime_state_store:
        die("Shared Runtime foreground-run ownership is incomplete: " + required_runtime_owner)
for forbidden_engine_owner in (
    "private val runStateLock = Any()",
    "private val pendingInputs = AgentInputQueue",
    "private var activeJob: Job? = null",
    "private var transcriptProjectionCursor: Long? = null",
):
    if forbidden_engine_owner in engine:
        die("LocalHarnessEngine must not recreate foreground-run backing state: " + forbidden_engine_owner)
if "binding.runtimeStateStore.foregroundInteractions" in engine:
    die("Work-bound interactions must resolve through LocalWorkRunBinding.interactions")
if "?: interactions" in engine or "else interactions." in engine:
    die("LocalHarnessEngine must resolve foreground interactions through LocalRuntimeStateStore")
work_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt")
)
for forbidden_proxy in (
    "engine.backgroundJobOutputForUi",
    "engine.stopBackgroundJobForUi",
    "engine.answerApproval",
    "engine.answerQuestion",
    "engine.cancelQuestion",
):
    if forbidden_proxy in work_runtime_source:
        die("LocalWorkRuntime must route interaction responses without Engine: " + forbidden_proxy)
for removed_engine_proxy in (
    "internal fun backgroundJobOutputForUi",
    "internal fun stopBackgroundJobForUi",
    "internal fun answerApproval",
    "internal fun answerQuestion",
    "internal fun cancelQuestion",
):
    if removed_engine_proxy in engine:
        die("LocalHarnessEngine must not reintroduce Work interaction proxy API: " + removed_engine_proxy)
if "LocalHarnessEngine" in work_runtime_source or "engine." in work_runtime_source:
    die("LocalWorkRuntime must not depend on LocalHarnessEngine")

feature_execution_port_module_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalFeatureExecutionPortModule.kt")
)
if (
    "provideLocalChatUserActivityPort(" not in feature_execution_port_module_source
    or "adapter: LocalAutomationChatUserActivityAdapter" not in feature_execution_port_module_source
    or "): LocalChatUserActivityPort = adapter" not in feature_execution_port_module_source
):
    die("app composition root must bind Automation's adapter to the Chat-owned user-activity contract")

work_execution_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkExecutionCoordinator.kt")
)
chat_turn_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTurnPort.kt")
)
if "startRegeneration" in chat_turn_port_source:
    die("Chat regeneration must not return to the transitional Engine-backed turn port")

chat_direct_turn_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatDirectTurnExecutor.kt")
)
for required_direct_turn_owner in (
    "@ApplicationContext private val context: Context",
    "LocalSessionRuntimeRegistry.withOwner(",
    "LocalExecutionService.withTurn(",
):
    if required_direct_turn_owner not in chat_direct_turn_source:
        die("Chat direct turn ownership is incomplete: " + required_direct_turn_owner)
if "override fun startRegeneration" in engine:
    die("LocalHarnessEngine must not restore Chat/Work regeneration through a turn bridge")

work_turn_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnPort.kt")
)
chat_send_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatSendCoordinator.kt")
)
chat_execution_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatExecutionCoordinator.kt")
)
chat_timeline_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTimelineCoordinator.kt")
)
chat_direct_turn_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatDirectTurnExecutor.kt")
)
group_chat_turn_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatTurnExecutor.kt")
)
chat_turn_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTurnPort.kt")
)
chat_turn_dispatcher_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatTurnDispatcher.kt")
)
chat_persona_correction_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatPersonaCorrectionCoordinator.kt")
)
chat_relationship_hydrator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRelationshipHydrator.kt")
)
if "LocalHarnessEngine" in work_execution_coordinator_source:
    die("WorkExecutionPort implementation must stay inside WorkFeature without Engine")
if "prepareLocalSend(text, attachments)" not in work_execution_coordinator_source:
    die("WorkFeature must own Work input preparation")
for required_work_send_owner in (
    "workRunRegistry.enqueueIntoLiveRun(prepared)",
    "coordinateOwnedLocalSend(",
    "runtimeStateStore.foregroundRunHandle.pendingInputs",
    "enqueueSnapshot(sessionId)",
    "startPreparedTurn = turn::startPrepared",
    "started = startPreparedTurn(",
):
    if required_work_send_owner not in work_execution_coordinator_source:
        die("WorkFeature first-send ownership is incomplete: " + required_work_send_owner)
if "eventLogFor = sessionStorage.eventLogs::get" not in work_execution_coordinator_source:
    die("WorkFeature must adapt Shared Session EventLog access at its injection boundary")
if "enqueueSnapshot = sessionStorage::enqueueCurrentSnapshot" not in work_execution_coordinator_source:
    die("WorkFeature must adapt Shared Session snapshot persistence at its injection boundary")
if work_execution_coordinator_source.find("workRunRegistry.enqueueIntoLiveRun(prepared)") > work_execution_coordinator_source.find("coordinateOwnedLocalSend("):
    die("Work-owned live-run queue must be checked before first-turn admission")
if "queueHumanTurn(" in work_execution_coordinator_source:
    die("Work send must not route through the Engine Chat queue")
for required_work_regeneration_owner in (
    "override fun regenerateReply(messageId: String): Boolean",
    "handle.modelHistory.lastOrNull()",
    "regenerator: LocalWorkReplyRegenerator",
    "startRegeneration = regenerator::start",
    "started = startRegeneration(messageId).also { handle.job = it }",
):
    if required_work_regeneration_owner not in work_execution_coordinator_source:
        die("WorkFeature regeneration admission is incomplete: " + required_work_regeneration_owner)
if "regenerateReply(messageId: String): Boolean" in work_turn_port_source:
    die("Work turn bridge must not own regeneration eligibility")
if "interface LocalWorkTurnPort" not in work_turn_port_source:
    die("Work turn migration port contract is missing")
if "LocalHarnessEngine" in work_turn_port_source or "LocalHarnessState" in work_turn_port_source:
    die("Work turn migration port must not expose Engine or aggregate state")
if "provideLocalWorkExecutionPort" not in feature_execution_port_module_source:
    die("app composition root must bind the Work-owned execution implementation")
if "coordinator: LocalWorkExecutionCoordinator" not in feature_execution_port_module_source:
    die("WorkExecutionPort must be implemented by LocalWorkExecutionCoordinator")
if "engine.workExecutionPort" in feature_execution_port_module_source or "internal val workExecutionPort" in engine:
    die("LocalHarnessEngine must not own the Work product execution port")
if "LocalHarnessEngine" in chat_execution_coordinator_source:
    die("ChatExecutionPort implementation must stay inside ChatFeature without Engine")
for required_chat_timeline_owner in (
    "internal fun editAndResendUserMessage(",
    "internal fun regenerateReply(messageId: String): Boolean",
    "LocalSessionRuntimeKind.MAINTENANCE",
    "appendTimelineRewriteCommit(",
    "modelHistory.checkpoint(",
    "directTurn.start(",
):
    if required_chat_timeline_owner not in chat_timeline_coordinator_source:
        die("Chat timeline ownership is incomplete: " + required_chat_timeline_owner)
if "turn.editAndResendUserMessage(" in chat_execution_coordinator_source or "turn.regenerateReply(" in chat_execution_coordinator_source:
    die("ChatExecutionPort must route timeline actions to the Chat-owned timeline coordinator")
if "timeline.editAndResendUserMessage(" not in chat_execution_coordinator_source or "timeline.regenerateReply(" not in chat_execution_coordinator_source:
    die("ChatExecutionPort must expose the Chat-owned timeline coordinator")
for required_chat_send_owner in (
    "prepareLocalSend(text, attachments)",
    "coordinateOwnedLocalSend(",
    "persistChatTimelineBaseline(",
    "appendMaterializedChatBranchMessage(",
    "sessionStorage.enqueueCurrentSnapshot(sessionId)",
    "turn.start(",
):
    if required_chat_send_owner not in chat_send_coordinator_source:
        die("ChatFeature send ownership is incomplete: " + required_chat_send_owner)
if "interface LocalChatTurnPort" not in chat_turn_port_source:
    die("Chat turn migration port contract is missing")
if "LocalHarnessEngine" in chat_turn_port_source or "LocalHarnessState" in chat_turn_port_source:
    die("Chat turn migration port must not expose Engine or aggregate state")
for required_chat_turn_owner in (
    "internal suspend fun run(",
    "personaCorrections.captureGroup(memoryInput)",
    "personaCorrections.captureDirect(memoryInput)",
    "relationshipHydrator.hydrate()",
    "runOwnedGroupChatTurn(",
    "LocalExecutionService.withTurn(",
):
    if required_chat_turn_owner not in chat_turn_dispatcher_source:
        die("Chat turn dispatcher ownership is incomplete: " + required_chat_turn_owner)
for required_persona_owner in (
    "internal fun captureDirect(text: String)",
    "internal fun captureGroup(text: String)",
    'boundLog.append("chat/persona-correction"',
    'boundLog.append("group/persona-correction"',
):
    if required_persona_owner not in chat_persona_correction_source:
        die("Chat persona-correction ownership is incomplete: " + required_persona_owner)
for required_relationship_owner in (
    "internal fun hydrate()",
    "!snapshot.autoRecall",
    "currentLineageId = aggregate.lineageId",
    'append("chat/relationship-hydrate"',
):
    if required_relationship_owner not in chat_relationship_hydrator_source:
        die("Chat relationship hydration ownership is incomplete: " + required_relationship_owner)
for removed_chat_turn_root in (
    "private suspend fun runTurn(",
    "private fun captureChatPersonaCorrection(",
    "private fun captureGroupPersonaCorrections(",
    "private fun hydrateNewChatStateFromRelationshipMemory(",
):
    if removed_chat_turn_root in engine:
        die("Chat turn preparation must not return to LocalHarnessEngine: " + removed_chat_turn_root)

if "provideLocalChatExecutionPort" not in feature_execution_port_module_source:
    die("app composition root must bind the Chat-owned execution implementation")
if "coordinator: LocalChatExecutionCoordinator" not in feature_execution_port_module_source:
    die("ChatExecutionPort must be implemented by LocalChatExecutionCoordinator")
if "engine.chatExecutionPort" in feature_execution_port_module_source or "internal val chatExecutionPort" in engine:
    die("LocalHarnessEngine must not own the Chat product execution port")
if "provideLocalChatTurnPort" not in feature_execution_port_module_source or "engine.chatTurnPort" not in feature_execution_port_module_source:
    die("Chat turn composition must use the explicit migration bridge")
for removed_chat_send_root in (
    "private fun sendChat(",
    "private fun queueHumanTurn(",
    "private fun queueTurn(",
    "private fun queueTurnLocked(",
    "private fun recordUserTranscript(",
):
    if removed_chat_send_root in engine:
        die("Chat send business must not return to LocalHarnessEngine: " + removed_chat_send_root)
for removed_chat_timeline_root in (
    "private fun editAndResendUserMessage(",
    "private fun regenerateReplyForMode(",
    "private fun transcriptForBranchMaterialization(",
):
    if removed_chat_timeline_root in engine:
        die("Chat timeline business must not return to LocalHarnessEngine: " + removed_chat_timeline_root)
for required_direct_chat_turn_owner in (
    "internal suspend fun run(",
    "LocalSessionRuntimeRegistry.withOwner(",
    "modelHistory.compactChatIfNeeded(",
    "modelRequests.complete(",
    "replyCoordinator.finalizeDirect(",
    "postTurn.schedule(",
    "startNextQueuedTurnIfIdle()?.start()",
):
    if required_direct_chat_turn_owner not in chat_direct_turn_source:
        die("Direct Chat main turn ownership is incomplete: " + required_direct_chat_turn_owner)
if "private suspend fun runChatTurn(" in engine:
    die("Direct Chat main turn must not return to LocalHarnessEngine")
for required_group_owner in (
    "private val runtimeStateStore: LocalRuntimeStateStore",
    "private val chatState: LocalChatStatePort",
    "private val modelRequests: LocalModelRequestCoordinator",
    "private val modelHistoryRuntime: LocalForegroundModelHistoryRuntime",
    "private val sessionStorage: LocalSessionStorageRuntime",
    "private val chatMemory: LocalChatMemoryRuntime",
    "private val branchCoordinator: LocalChatBranchCoordinator",
    "private val transcriptRuntime: LocalChatTranscriptRuntime",
):
    if required_group_owner not in group_chat_turn_source:
        die("Group Chat must use Chat/Shared owners instead of Engine callbacks: " + required_group_owner)
for removed_group_callback in (
    "MutableStateFlow<LocalHarnessState>",
    "private val completeModel:",
    "private val ensureSystemMessageAction:",
    "private val captureAutoMemoryAction:",
    "private val chatMemoryContextAction:",
    "private val compactHistoryAction:",
    "private val persistBranchStateAction:",
    "private val checkpointHistoryAction:",
    "private val persistAction:",
    "private val persistNowAction:",
):
    if removed_group_callback in group_chat_turn_source:
        die("Group Chat regained an Engine callback dependency: " + removed_group_callback)
for stale_timeline_bridge in (
    "fun editAndResendUserMessage(",
    "fun regenerateReply(",
):
    if stale_timeline_bridge in chat_turn_port_source:
        die("Chat turn bridge must stay start-only: " + stale_timeline_bridge)

if "provideLocalWorkTurnPort" not in feature_execution_port_module_source:
    die("app composition root must expose the temporary Work turn bridge")
if "engine.workTurnPort" not in feature_execution_port_module_source:
    die("Work turn composition must use the explicit migration bridge")
if "internal val workTurnPort: LocalWorkTurnPort" not in engine:
    die("Engine migration code must expose the narrow Work turn bridge")
if not has_call(work_runtime_source, "runtimeStateStore", "cancelForegroundRun"):
    die("Work visible-run cancellation must use the shared Runtime owner")
if "check(!initialized)" not in runtime_state_store:
    die("LocalRuntimeStateStore initialization must remain single-owner")
if not has_call(engine, "runtimeStateStore", "initialize"):
    die("LocalHarnessEngine must initialize state through LocalRuntimeStateStore")
if not has_typed_property(constructor.group(1), "LocalRuntimeStateStore"):
    die("LocalHarnessEngine must receive the shared LocalRuntimeStateStore by injection")
if not has_typed_property(constructor.group(1), "LocalSessionStorageRuntime"):
    die("LocalHarnessEngine must consume the shared Session storage capability")
if has_typed_property(constructor.group(1), "LocalSessionEventLogRegistry"):
    die("LocalHarnessEngine must not inject Session EventLog storage separately from LocalSessionStorageRuntime")
if "LocalSessionRepository(" in engine or "LocalSessionCoordinator(" in engine:
    die("LocalHarnessEngine must not construct Session persistence owners")
for removed_session_engine_method in (
    "internal suspend fun importAttachment",
    "internal suspend fun workspaceFilesForUi",
    "internal suspend fun conversationFilesForUi",
    "internal suspend fun previewWorkspaceFileForUi",
    "internal fun transcriptPageForUi",
    "internal fun transcriptTailForUi",
    "internal fun completeTranscriptForUi",
):
    if removed_session_engine_method in engine:
        die("LocalHarnessEngine must not reintroduce Session file/transcript proxy API: " + removed_session_engine_method)
for required_session_storage in (
    "LocalSessionRepository(",
    "LocalSessionCoordinator(",
    "eventLogs: LocalSessionEventLogRegistry",
    "files: LocalSessionFilesRuntime",
):
    if required_session_storage not in session_storage_runtime:
        die("Shared Session storage ownership is incomplete: " + required_session_storage)
if "internal fun enqueueSnapshot(snapshot: LocalHarnessSession)" not in session_storage_runtime:
    die("Shared Session storage must expose a narrow snapshot enqueue command for Feature-owned runs")
if "foregroundSessionId" not in runtime_state_store or "activateSession(sessionId: String)" not in runtime_state_store:
    die("LocalRuntimeStateStore must own foreground Session identity")
if "private var currentSessionId" in engine:
    die("LocalHarnessEngine must not keep a second mutable foreground Session id")
if "get() = runtimeStateStore.currentSessionId" not in engine:
    die("LocalHarnessEngine foreground Session reads must resolve through LocalRuntimeStateStore")
if not has_call(engine, "runtimeStateStore", "activateSession"):
    die("Session activation must update LocalRuntimeStateStore ownership before projection load")

session_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt")
)
if "internal val state: StateFlow<LocalHarnessState> = runtimeStateStore.state" not in session_runtime_source:
    die("LocalSessionRuntime must consume aggregate state from LocalRuntimeStateStore")
if "engine.state" in session_runtime_source:
    die("LocalSessionRuntime must not reach through LocalHarnessEngine for aggregate state")
if "runtimeStateStore.sendFeedbackState" not in session_runtime_source:
    die("LocalSessionRuntime send feedback must consume LocalRuntimeStateStore")
if "engine.sendFeedbackState" in session_runtime_source:
    die("LocalSessionRuntime must not reach through LocalHarnessEngine for send feedback state")
if "runtimeStateStore.streamingPreviewStore.state" not in session_runtime_source:
    die("LocalSessionRuntime streaming preview must consume LocalRuntimeStateStore")
if "engine.streamingState" in session_runtime_source:
    die("LocalSessionRuntime must not reach through LocalHarnessEngine for streaming preview state")
if "createSession(mode, state.value.usageMode)" not in session_runtime_source:
    die("LocalSessionRuntime convenience session creation must stay inside the Session capability")
if "LocalHarnessEngine" in session_runtime_source:
    die("LocalSessionRuntime must not depend on LocalHarnessEngine after Session lifecycle port migration")
if not has_typed_property(session_runtime_source, "LocalSessionLifecyclePort"):
    die("LocalSessionRuntime must depend on the Session-owned lifecycle port")
if "class LocalSessionRuntime @Inject internal constructor(" not in session_runtime_source:
    die("LocalSessionRuntime constructor must stay internal while consuming internal Session contracts")
for required_lifecycle_delegate in (
    "lifecycle.createSession(",
    "lifecycle.switchDomainMode(command)",
    "lifecycle.switchUsageMode(mode)",
    "lifecycle.switchSession(sessionId)",
    "lifecycle.deleteSessions(ids)",
):
    if required_lifecycle_delegate not in session_runtime_source:
        die("Session lifecycle entry must route through LocalSessionLifecyclePort: " + required_lifecycle_delegate)
for required_session_delegate in (
    "sessionFiles.importAttachment(uri)",
    "sessionFiles.workspaceFiles()",
    "sessionFiles.conversationFiles(sessionId)",
    "sessionFiles.previewWorkspaceFile(path)",
    "sessionRead.transcriptPage(sessionId, cursor, limit)",
    "sessionRead.transcriptTail(sessionId, limit)",
    "sessionRead.completeTranscript(sessionId)",
):
    if required_session_delegate not in session_runtime_source:
        die("Session UI file/transcript reads must use narrow Session capabilities: " + required_session_delegate)
for removed_session_engine_proxy in (
    "engine.importAttachment(",
    "engine.workspaceFilesForUi(",
    "engine.conversationFilesForUi(",
    "engine.previewWorkspaceFileForUi(",
    "engine.transcriptPageForUi(",
    "engine.transcriptTailForUi(",
    "engine.completeTranscriptForUi(",
):
    if removed_session_engine_proxy in session_runtime_source:
        die("LocalSessionRuntime must not restore Engine file/transcript proxies: " + removed_session_engine_proxy)
if "com.labteto.dshmobile.local.chat." in session_runtime_source:
    die("Shared SessionRuntime must not import ChatFeature internals")
for required_session_domain_command in (
    "domainSpec: LocalSessionDomainCreateSpec?",
    "switchDomainMode(command: LocalSessionDomainModeCommand)",
):
    if required_session_domain_command not in session_runtime_source:
        die("SessionRuntime must carry Feature session intent through opaque Session contracts")
if (
    "createGroupChatSession" in session_runtime_source
    or "createSingleChatSession" in session_runtime_source
    or "MIN_GROUP_CHAT_MEMBERS" in session_runtime_source
    or "MAX_GROUP_CHAT_MEMBERS" in session_runtime_source
):
    die("SessionRuntime must not own Chat session/member admission rules")
chat_runtime_for_session_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt")
)
if (
    "MIN_GROUP_CHAT_MEMBERS" not in chat_runtime_for_session_source
    or "MAX_GROUP_CHAT_MEMBERS" not in chat_runtime_for_session_source
    or "sessionRuntime.createSession(" not in chat_runtime_for_session_source
    or "LocalChatSessionCreateSpec" not in chat_runtime_for_session_source
    or "LocalChatSessionModeCommand" not in chat_runtime_for_session_source
):
    die("ChatRuntime must own Chat session intent and use opaque Session contracts")
persona_gallery_ui_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalPersonaGalleryUiController.kt")
)
if "runtime.session.createSession(" in persona_gallery_ui_source:
    die("Persona gallery UI must route Chat session creation through ChatFeature")
if "runtime.chat.startSessionFromGallery(" not in persona_gallery_ui_source:
    die("Persona gallery UI must use the ChatFeature gallery-session entry point")
if "private val streamingPreviewStore = LocalStreamingPreviewStore()" in engine:
    die("LocalHarnessEngine must not own the process-wide streaming preview store")
if "MutableStateFlow(LocalSendFeedbackState())" in engine:
    die("LocalHarnessEngine must not own send feedback state")

model_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt")
)
model_settings_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalModelSettingsCoordinator.kt")
)
if not has_call(model_runtime_source, "settings", "configureImageInputMode"):
    die("LocalModelRuntime image input mode must be owned by LocalModelSettingsCoordinator")
if not has_call(model_runtime_source, "configuration", "test"):
    die("LocalModelRuntime must own model connection testing through Model configuration capability")
if "engine.configureImageInputMode" in model_runtime_source:
    die("LocalModelRuntime must not route image input settings through LocalHarnessEngine")
if "KEY_IMAGE_INPUT_MODE" not in model_settings_source or not has_call(model_settings_source, "runtimeStateStore.projection", "setImageInputMode"):
    die("LocalModelSettingsCoordinator must own image input persistence and use the ModelState projection command")

task_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/presentation/LocalTaskRuntime.kt")
)
if "LocalRuntimeStateStore" not in task_runtime_source or "runtimeStateStore.state" not in task_runtime_source:
    die("LocalTaskRuntime must consume task projection from LocalRuntimeStateStore")
if "LocalHarnessEngine" in task_runtime_source or "engine." in task_runtime_source:
    die("LocalTaskRuntime must not depend on LocalHarnessEngine")

settings_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt")
)
if "runtimeStateStore.state" not in settings_runtime_source:
    die("LocalSettingsRuntime must consume LocalRuntimeStateStore for visible settings state")
if "engine.state" in settings_runtime_source:
    die("LocalSettingsRuntime must not reach through LocalHarnessEngine for aggregate state")
if "private val modelRuntime: LocalModelRuntime" not in settings_runtime_source:
    die("LocalSettingsRuntime must depend on the Model capability for model operations")
for forbidden_model_proxy in (
    "engine.configure(apiKey, model, baseUrl)",
    "engine.selectModel(id)",
    "engine.clearCredential()",
    "engine.testModelConfiguration",
):
    if forbidden_model_proxy in settings_runtime_source:
        die("LocalSettingsRuntime must route model operations through LocalModelRuntime: " + forbidden_model_proxy)

work_approval_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkApprovalCoordinator.kt")
)
if "LocalHarnessEngine" in work_approval_source or "persist:" in work_approval_source:
    die("Work approval policy must depend on shared capabilities, not Engine callbacks")
if not has_call(work_runtime_source, "approvals", "disableDeviceApprovalLease"):
    die("Device approval revocation must bind to the visible Session instead of mutable foreground identity")
if "internal fun disableDeviceApprovalLease(sessionId: String)" not in work_approval_source:
    die("Work approval coordinator must require an explicit Session for device lease revocation")
for method in (
    "enableAutoApproval", "enableAutoApprovalForPending", "enableDeviceApprovalLease",
    "disableDeviceApprovalLease", "disableAutoApproval",
):
    if re.search(rf"\bfun\s+{method}\s*\(", engine):
        die(f"Work approval ownership must not return to Engine: {method}")
if "LocalSessionEventLogRegistry(sessionsRoot" in engine or "LocalApprovalPreferences(preferences)" in engine:
    die("Engine must consume the shared Session EventLog/approval preference owners")
if "binding.runtimeStateStore" in engine:
    die("Work bindings must use their session-owned interactions")

automation_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt")
)
if "runtimeStateStore.state" not in automation_runtime_source:
    die("LocalAutomationRuntime planning state must consume LocalRuntimeStateStore")
if "engine.state" in automation_runtime_source:
    die("LocalAutomationRuntime must not reach through LocalHarnessEngine for aggregate state")

chat_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt")
)
local_view_model_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt")
)
if (
    "LocalUsageMode.CHAT -> runtime.chat.stop()" not in local_view_model_source
    or "LocalUsageMode.WORK -> runtime.work.stop()" not in local_view_model_source
):
    die("Local UI stop action must route to the owning Chat/Work Feature")
if (
    not has_call(chat_runtime_source, "sessionRuntime", "switchDomainMode")
    or "LocalChatSessionModeCommand(" not in chat_runtime_source
):
    die("Chat mode switching must route through the generic Session domain command capability")
if "engine.createGroupChatSession" in chat_runtime_source or "engine.createSingleChatSession" in chat_runtime_source:
    die("Chat session creation must route through Session capability")
if "engine.undoChatPersonaCorrection" in chat_runtime_source:
    die("Persona-correction undo must stay inside ChatFeature")
if "personaCorrections.undo" not in chat_runtime_source:
    die("ChatRuntime must route persona-correction undo through its Chat coordinator")
if not has_call(chat_runtime_source, "behaviorTuning", "configure") or "engine.configureChatPersona" in chat_runtime_source:
    die("Chat persona tuning must stay inside ChatFeature")
if "engine.setGroupChatAnnouncement" in chat_runtime_source:
    die("Chat group announcement save must stay inside ChatFeature")
if (
    "saveGroupChatAnnouncement(" not in chat_runtime_source
    or "persistNow = sessionStorage::writeCurrentSnapshotNow" not in chat_runtime_source
):
    die("ChatRuntime must commit group announcements through the shared Session persistence capability")
if "internal suspend fun setGroupChatAnnouncement(" in engine:
    die("LocalHarnessEngine must not reintroduce group announcement API")
if "internal suspend fun configureChatPersona(" in engine:
    die("LocalHarnessEngine must not reintroduce Chat persona tuning API")
for forbidden_persona_proxy in (
    "engine.selectChatPersona",
    "engine.bindChatGallery",
    "engine.clearChatGalleryBinding",
    "engine.syncDefaultChatPersona",
):
    if forbidden_persona_proxy in chat_runtime_source:
        die("ChatRuntime must route persona/gallery state through LocalChatPersonaCoordinator: " + forbidden_persona_proxy)
for removed_persona_api in (
    "internal fun selectChatPersona(",
    "internal fun bindChatGallery(",
    "internal fun clearChatGalleryBinding(",
    "internal suspend fun syncDefaultChatPersona(",
):
    if removed_persona_api in engine:
        die("LocalHarnessEngine must not reintroduce Chat persona/gallery API: " + removed_persona_api)
chat_persona_coordinator = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatPersonaCoordinator.kt")
)
for required_persona_dependency in (
    "LocalChatStatePort",
    "LocalSessionStorageRuntime",
    "ChatPersonaStore",
):
    if required_persona_dependency not in chat_persona_coordinator:
        die("Chat persona ownership is incomplete: " + required_persona_dependency)
if "LocalRuntimeStateStore" in chat_persona_coordinator or "LocalAggregateProjectionPort" in chat_persona_coordinator:
    die("Chat persona coordinator must not regain aggregate Runtime write access")

model_configuration = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalModelConfigurationCoordinator.kt")
)
if (
    "@Inject constructor(" not in model_configuration
    and "@Inject internal constructor(" not in model_configuration
) or "@Singleton" not in model_configuration:
    die("LocalModelConfigurationCoordinator must remain an injected Model capability")
if "LocalDeepSeekSearchCredentialResolver(::readProfiles, apiKeys).resolve()" not in model_configuration:
    die("DeepSeek search credential resolution must stay inside Model configuration capability")
if "private val apiKeys: LocalApiKeyStore" in constructor.group(1) or "private val modelConnectionTester: LocalModelConnectionTester" in constructor.group(1):
    die("LocalHarnessEngine must not re-own Model configuration dependencies")

public_method_count = len(
    re.findall(
        r"^    (?:public\s+)?(?:suspend\s+)?fun\s+[A-Za-z0-9_]+\s*\(",
        engine,
        re.MULTILINE,
    )
)
if public_method_count > ENGINE_MAX_PUBLIC_METHODS:
    die(
        f"LocalHarnessEngine exposes {public_method_count} methods "
        f"(ratchet: {ENGINE_MAX_PUBLIC_METHODS}); add capability-specific APIs instead"
    )

internal_method_count = len(
    re.findall(
        r"^    internal\s+(?:suspend\s+)?fun\s+[A-Za-z0-9_]+\s*\(",
        engine,
        re.MULTILINE,
    )
)
if internal_method_count > ENGINE_MAX_INTERNAL_METHODS:
    die(
        f"LocalHarnessEngine exposes {internal_method_count} internal methods "
        f"(ratchet: {ENGINE_MAX_INTERNAL_METHODS}); "
        "internal capability API is still architecture API and must move outward"
    )

models_path = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessModels.kt"
models = read(models_path)
state_start = models.find("data class LocalHarnessState(")
state_end = models.find("\n)", state_start)
if state_start < 0 or state_end < 0:
    die("unable to locate LocalHarnessState")
state_field_count = len(
    re.findall(r"^\s*val\s+[A-Za-z0-9_]+\s*:", models[state_start:state_end], re.MULTILINE)
)
if state_field_count > AGGREGATE_STATE_MAX_FIELDS:
    die(
        f"LocalHarnessState has {state_field_count} fields "
        f"(ratchet: {AGGREGATE_STATE_MAX_FIELDS}); create a domain/projection state instead"
    )
if "streamingAssistant" in models or "streamingReasoning" in models:
    die("streaming preview must stay outside LocalHarnessState")

aggregate_state_source = models[state_start:state_end]
if "val work: LocalWorkState = LocalWorkState()" not in aggregate_state_source:
    die("LocalHarnessState must compose Work runtime state through LocalWorkState")
for legacy_work_field in (
    "plan",
    "todos",
    "goal",
    "planMode",
    "jobs",
    "workflowProgress",
    "pendingApproval",
    "pendingQuestion",
):
    if re.search(rf"^\s*val\s+{legacy_work_field}\s*:", aggregate_state_source, re.MULTILINE):
        die(
            f"LocalHarnessState must not reintroduce flattened Work field: {legacy_work_field}"
        )

work_state = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkState.kt")
)
for owned_field in (
    "plan",
    "todos",
    "goal",
    "planMode",
    "jobs",
    "workflowProgress",
    "pendingApproval",
    "pendingQuestion",
):
    if not re.search(rf"\bval\s+{owned_field}\s*:", work_state):
        die(f"LocalWorkState must own Work field: {owned_field}")

if "val modelState: LocalModelState = LocalModelState()" not in aggregate_state_source:
    die("LocalHarnessState must compose Model runtime state through LocalModelState")
for legacy_model_field in (
    "configured",
    "model",
    "baseUrl",
    "modelSelection",
    "modelAttempts",
    "imageInputMode",
):
    if re.search(rf"^\s*val\s+{legacy_model_field}\s*:", aggregate_state_source, re.MULTILINE):
        die(
            f"LocalHarnessState must not reintroduce flattened Model field: {legacy_model_field}"
        )

model_state = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalModelState.kt")
)
for owned_field in (
    "configured",
    "model",
    "baseUrl",
    "modelSelection",
    "modelAttempts",
    "imageInputMode",
):
    if not re.search(rf"\bval\s+{owned_field}\s*:", model_state):
        die(f"LocalModelState must own Model field: {owned_field}")

if "val chat: LocalChatState = LocalChatState()" not in aggregate_state_source:
    die("LocalHarnessState must compose Chat runtime state through LocalChatState")
for legacy_chat_field in (
    "personaId",
    "galleryId",
    "galleryStoryId",
    "gallerySaveSuppressedThrough",
    "chatPersona",
    "chatState",
    "chatContext",
    "replySuggestions",
    "chatBranches",
    "groupChat",
    "groupActiveSpeakerName",
    "personaCorrectionNotice",
):
    if re.search(rf"^\s*val\s+{legacy_chat_field}\s*:", aggregate_state_source, re.MULTILINE):
        die(
            f"LocalHarnessState must not reintroduce flattened Chat field: {legacy_chat_field}"
        )

chat_state = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatState.kt")
)
for owned_field in (
    "personaId",
    "galleryId",
    "galleryStoryId",
    "gallerySaveSuppressedThrough",
    "chatPersona",
    "chatState",
    "chatContext",
    "replySuggestions",
    "chatBranches",
    "groupChat",
    "groupActiveSpeakerName",
    "personaCorrectionNotice",
):
    if not re.search(rf"\bval\s+{owned_field}\s*:", chat_state):
        die(f"LocalChatState must own Chat field: {owned_field}")

if "val kernel: LocalKernelState = LocalKernelState()" not in aggregate_state_source:
    die("LocalHarnessState must compose Kernel runtime state through LocalKernelState")
for legacy_kernel_field in (
    "running",
    "queuedInputCount",
    "resources",
    "contextChars",
    "contextBudgetChars",
):
    if re.search(rf"^\s*val\s+{legacy_kernel_field}\s*:", aggregate_state_source, re.MULTILINE):
        die(
            f"LocalHarnessState must not reintroduce flattened Kernel field: {legacy_kernel_field}"
        )

kernel_state = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalKernelState.kt")
)
for owned_field in (
    "running",
    "queuedInputCount",
    "resources",
    "contextChars",
    "contextBudgetChars",
):
    if not re.search(rf"\bval\s+{owned_field}\s*:", kernel_state):
        die(f"LocalKernelState must own Kernel field: {owned_field}")

work_run_registry = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunRegistry.kt")
)
work_run_binding = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunBinding.kt")
)
token_usage_bridge = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/usage/LocalTokenUsageContextBridge.kt")
)
if "private val workRunRegistry: LocalWorkRunRegistry" not in engine:
    die("LocalHarnessEngine must consume the Work-owned LocalWorkRunRegistry")
if "activeWorkRuns" in engine or "ConcurrentHashMap<String, LocalWorkRunBinding>" in engine:
    die("LocalHarnessEngine must not own a second active Work-run map")
if "ConcurrentHashMap<String, LocalWorkRunBinding>" not in work_run_registry:
    die("LocalWorkRunRegistry must remain the single in-memory owner of active Work bindings")
if re.search(r"workRunRegistry\[[^\]]+\]\s*=", engine):
    die("Work bindings must be registered through LocalWorkRunRegistry.attach")
if "LocalWorkRunRegistry" not in work_run_binding:
    die("Work job projection must consume LocalWorkRunRegistry instead of a raw active-run map")
if "LocalWorkRunRegistry" not in token_usage_bridge:
    die("Token usage attribution must resolve active Work state through LocalWorkRunRegistry")
if "private fun finishBoundWorkTurn" in engine:
    die("LocalHarnessEngine must not own Work binding completion lifecycle")
if "internal fun finishTurn(" not in work_run_registry:
    die("LocalWorkRunRegistry must own Work binding completion and queued continuation lifecycle")
if "workRunRegistry.finishTurn(" not in work_agent_turn_source:
    die("Work Agent execution must delegate binding completion to LocalWorkRunRegistry")
if "workRunRegistry.finishTurn(" in engine:
    die("LocalHarnessEngine must not regain Work binding completion call sites")

work_progress = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkProgressCoordinator.kt")
)
for required_mutation in (
    "it.copy(plan = normalized)",
    "it.copy(todos = items)",
    "it.copy(goal = goal)",
    "it.copy(goal = updated)",
):
    if required_mutation not in work_progress:
        die("Work mutations must remain owned by LocalWorkStatePort: " + required_mutation)
if ".work." in work_progress or "LocalHarnessState" in work_progress:
    die("LocalWorkProgressCoordinator must not reach through the aggregate state to mutate Work")

model_contracts = read("app/src/main/java/com/labteto/dshmobile/local/model/LocalModelModels.kt")
stream_state_start = model_contracts.find("data class LocalHarnessStreamingState(")
stream_state_end = model_contracts.find("\n)", stream_state_start)
if stream_state_start < 0 or stream_state_end < 0:
    die("unable to locate LocalHarnessStreamingState")
stream_state = model_contracts[stream_state_start:stream_state_end]
for identity_field in ("sessionId", "requestId", "usageMode"):
    if not re.search(rf"\bval\s+{identity_field}\s*:", stream_state):
        die(f"streaming preview must carry explicit {identity_field} ownership")
if "MutableStateFlow(LocalHarnessStreamingState())" in engine:
    die("LocalHarnessEngine must not own anonymous process-wide streaming preview state")

stream_store_path = "app/src/main/java/com/labteto/dshmobile/local/model/LocalStreamingPreviewStore.kt"
stream_store = read(stream_store_path)
if "LocalStreamingPreviewOwner" not in stream_store or "requestId" not in stream_store:
    die("streaming preview store must enforce request-scoped ownership")
transcript_runtime = read("app/src/main/java/com/labteto/dshmobile/local/session/LocalTranscriptRuntime.kt")
if "clearStreamingPreview" in transcript_runtime:
    die("transcript runtime must not expose a fake streaming-preview clear flag")

projection_path = "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalHarnessUiState.kt"
projection_source = read(projection_path)
for class_name, maximum in PROJECTION_FIELD_BUDGETS.items():
    class_start = projection_source.find(f"data class {class_name}(")
    class_end = projection_source.find("\n)", class_start)
    if class_start < 0 or class_end < 0:
        die(f"unable to locate {class_name}")
    field_count = len(
        re.findall(
            r"^\s*val\s+[A-Za-z0-9_]+\s*:",
            projection_source[class_start:class_end],
            re.MULTILINE,
        )
    )
    if field_count > maximum:
        die(
            f"{class_name} has {field_count} fields (ratchet: {maximum}); "
            "split the projection instead of recreating aggregate state"
        )

work_projection_path = "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalWorkUiState.kt"
work_projection_source = read(work_projection_path)
work_class_start = work_projection_source.find("data class LocalWorkUiState(")
work_class_end = work_projection_source.find("\n)", work_class_start)
if work_class_start < 0 or work_class_end < 0:
    die("unable to locate LocalWorkUiState")
work_field_count = len(
    re.findall(
        r"^\s*val\s+[A-Za-z0-9_]+\s*:",
        work_projection_source[work_class_start:work_class_end],
        re.MULTILINE,
    )
)
if work_field_count > 23:
    die(
        f"LocalWorkUiState has {work_field_count} fields (ratchet: 23); "
        "split status/run-center projections instead of growing the work surface"
    )

conversation_projection_path = "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalConversationSurfaceState.kt"
conversation_projection_source = read(conversation_projection_path)
conversation_class_start = conversation_projection_source.find("data class LocalConversationSurfaceState(")
conversation_class_end = conversation_projection_source.find("\n)", conversation_class_start)
if conversation_class_start < 0 or conversation_class_end < 0:
    die("unable to locate LocalConversationSurfaceState")
conversation_field_count = len(re.findall(r"^\s*val\s+[A-Za-z0-9_]+\s*:", conversation_projection_source[conversation_class_start:conversation_class_end], re.MULTILINE))
if conversation_field_count > 31:
    die(f"LocalConversationSurfaceState has {conversation_field_count} fields (ratchet: 31); split narrower mode projections instead of widening the shared surface")

screen_path = "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt"
screen_source = read(screen_path)
screen_entry_start = screen_source.find("fun LocalHarnessScreen(")
screen_entry_tail = screen_source[screen_entry_start + 1:] if screen_entry_start >= 0 else ""
screen_entry_boundary = re.search(
    r"\n@Composable\n(?:private|internal) fun [A-Za-z0-9_]+\s*\(",
    screen_entry_tail,
)
screen_entry_end = (
    screen_entry_start + 1 + screen_entry_boundary.start()
    if screen_entry_boundary is not None
    else len(screen_source)
)
if screen_entry_start < 0:
    die("unable to locate LocalHarnessScreen entry function")
screen_entry = screen_source[screen_entry_start:screen_entry_end]
if "viewModel.state.collectAsStateWithLifecycle()" in screen_entry:
    die("LocalHarnessScreen shell must not directly subscribe to aggregate runtime state")
if "viewModel.shellState.collectAsStateWithLifecycle()" not in screen_entry:
    die("LocalHarnessScreen must subscribe to LocalHarnessShellState")
if "viewModel.workState.collectAsStateWithLifecycle()" in screen_entry:
    die("LocalHarnessScreen shell must not directly subscribe to hot Work state")
if "LocalHarnessStateContent(" in screen_source or "state: LocalHarnessState" in screen_source:
    die("Local conversation UI must use mode-specific Chat/Work surface projections, not aggregate state")
if "LocalConversationStateContent(viewModel, shell.usageMode)" not in screen_source:
    die("LocalHarnessScreen must select the Chat/Work conversation projection from shell usage mode")

feature_catalog_path = "app/src/main/java/com/labteto/dshmobile/local/feature/LocalFeatureCatalog.kt"
feature_catalog = strip_comments(read(feature_catalog_path))
feature_navigation = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeatureNavigation.kt")
)
for required_feature_owner in (
    "LocalFeatureModuleId.SHELL",
    "LocalFeatureModuleId.CHAT",
    "LocalFeatureModuleId.WORK",
    "LocalFeatureModuleId.AUTOMATION",
    "LocalFeatureModuleId.TOOLS",
    "LocalFeatureModuleId.SETTINGS",
):
    if required_feature_owner not in feature_catalog:
        die(f"Architecture 3.0 feature catalog is missing owner: {required_feature_owner}")
for forbidden_catalog_mutation in (
    "fun register(",
    "fun unregister(",
    "fun addModule(",
    "fun removeModule(",
):
    if forbidden_catalog_mutation in feature_catalog:
        die("Architecture 3.0 product Feature catalog must remain immutable after composition")
if "check(put(route, module.id) == null)" not in feature_catalog:
    die("feature catalog must reject duplicate subfeature route ownership")
if "ownerByRoute.keys == LocalFeatureRoute.entries.toSet()" not in feature_catalog:
    die("feature catalog must require every route to have exactly one owner")
if "LocalFeatureCatalog.resolve(stack.lastOrNull())" not in feature_navigation:
    die("local navigation must resolve routes through LocalFeatureCatalog")
if "LocalFeatureRoute.valueOf" in feature_navigation or "LocalFeaturePage.valueOf" in feature_navigation:
    die("local navigation must not bypass LocalFeatureCatalog with enum valueOf")
if "LocalFeatureCatalog.routes" not in screen_source:
    die("LocalHarnessScreen must enumerate registered feature routes through LocalFeatureCatalog")

local_root = ROOT / "app/src/main/java/com/labteto/dshmobile/local"

main_root = ROOT / "app/src/main/java/com/labteto/dshmobile"
engine_consumers = set()
for path in main_root.rglob("*.kt"):
    text = strip_comments(path.read_text(encoding="utf-8"))
    if re.search(r"\bLocalHarnessEngine\b", text):
        engine_consumers.add(path.relative_to(ROOT).as_posix())
view_model_path = "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt"
if "LocalHarnessEngine" in strip_comments(read(view_model_path)):
    die("LocalHarnessViewModel must depend on capability runtimes, not LocalHarnessEngine")
if not has_call(engine, "pluginComposition", "installStartup"):
    die("LocalHarnessEngine must delegate atomic startup plugin installation to its composition root")
if re.search(r"\bpluginRegistry\b", strip_comments(engine)):
    die("LocalHarnessEngine must not own PluginRegistry directly")
if "LocalPluginCompositionFactory" not in engine:
    die("LocalHarnessEngine must delegate plugin construction to LocalPluginCompositionFactory")
for forbidden_plugin_type in (
    "AndroidRuntimePlugin",
    "McpToolBridgePlugin",
    "GitHubConnectorPlugin",
    "LspPlugin",
    "AndroidDevicePlugin",
    "LocalVisionPlugin",
    "AutomationPlugin",
    "WebhookPlugin",
):
    if re.search(r"\b" + forbidden_plugin_type + r"\b", strip_comments(engine)):
        die(f"LocalHarnessEngine must not construct concrete plugin {forbidden_plugin_type}")

plugin_composition_path = "app/src/main/java/com/labteto/dshmobile/local/tools/LocalPluginComposition.kt"
plugin_composition = strip_comments(read(plugin_composition_path))
if "PluginCatalog(" not in plugin_composition:
    die("LocalPluginComposition must declare a PluginCatalog")
if "PluginManager(" not in plugin_composition:
    die("LocalPluginComposition must route lifecycle through PluginManager")
if not has_call(plugin_composition, "pluginManager", "installAll"):
    die("startup plugin installation must be resolved through PluginManager/PluginCatalog")
if re.search(r"\bstartupPlugins\b", plugin_composition):
    die("startup plugins must not be maintained as a raw hard-coded lifecycle list")
if "apiKeys.get()" in plugin_composition:
    die("Vision/plugin composition must not read the mutable global active API key")
if "route.copy(profile = modelGateway.activeProfile())" in plugin_composition:
    die("Vision route identity must not be overwritten from mutable activeProfile")
if "routeProvider = routeProvider" not in plugin_composition or "apiKeys.getFor(profile.id)" not in plugin_composition:
    die("Vision composition must preserve the exact route profile and resolve credentials by profile id")

automation_chat = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt")
)
if "val runProfile = modelGateway.profileForRun()" not in automation_chat:
    die("Automation Chat must freeze one model profile for the whole detached run")
if "profile = runProfile" not in automation_chat:
    die("Automation Chat model retries/repairs must reuse the frozen profile")

automation_planning = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/automation/AutomationPlanningService.kt")
)
automation_runtime = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt")
)
automation_session_transaction = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationSessionTransaction.kt")
)
automation_worker = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomationWorker.kt")
)
automation_settlement = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/automation/AutomationWorkerSettlementCoordinator.kt")
)
session_event_log = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionEventLog.kt")
)
session_runtime_registry = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionRuntimeRegistry.kt")
)
agent_run_runtime = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt")
)
if "package com.labteto.dshmobile.local.runtime" not in session_runtime_registry:
    die("Session ownership must live in the shared runtime package")
if "package com.labteto.dshmobile.local.runtime" not in agent_run_runtime:
    die("Agent run ownership/recovery must live in the shared runtime package")
recovery_model_route = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/model/LocalRecoveryModelRoute.kt")
)
if "import com.labteto.dshmobile.local.runtime.LocalAgentRunRouteIdentity" not in recovery_model_route:
    die("Recovery model routing must consume the shared Runtime route identity after package migration")
session_lifecycle = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalSessionLifecycleCoordinator.kt")
)
session_coordinator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalSessionCoordinator.kt")
)
session_storage_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorageRuntime.kt")
)
session_domain_codec_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionDomainCodec.kt")
)
session_domain_command_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionDomainCommand.kt")
)
chat_session_domain_command_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatSessionDomainCommand.kt")
)
if "com.labteto.dshmobile.local.chat." in session_domain_command_source:
    die("Session domain command contracts must stay Feature-agnostic")
for required_domain_command in (
    "interface LocalSessionDomainCreateSpec",
    "interface LocalSessionDomainModeCommand",
):
    if required_domain_command not in session_domain_command_source:
        die("Shared Session domain command contract is incomplete: " + required_domain_command)
for required_chat_command in (
    "LocalChatSessionCreateSpec",
    "LocalChatSessionModeCommand",
):
    if required_chat_command not in chat_session_domain_command_source:
        die("ChatFeature session command contribution is incomplete: " + required_chat_command)
chat_session_codec_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatSessionDomainCodec.kt")
)
if "import com.labteto.dshmobile.local.chat." in session_coordinator_source:
    die("Session coordinator must not execute ChatFeature internal migration rules")
if (
    "List<LocalSessionDomainCodec>" not in session_coordinator_source
    or "normalizeLocalSessionDomains(session, domainCodecs)" not in session_coordinator_source
):
    die("Session coordinator must normalize Feature data through injected domain codecs")
if "Set<@JvmSuppressWildcards LocalSessionDomainCodec>" not in session_storage_runtime_source:
    die("Shared Session storage must receive Feature codecs through the Session contract")
if "com.labteto.dshmobile.local.chat." in session_storage_runtime_source:
    die("Shared Session storage must not import ChatFeature internals")
for required_contract in (
    "val id: String",
    "fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession",
):
    if required_contract not in session_domain_codec_source:
        die(f"Session domain codec contract is incomplete: {required_contract}")
for required_chat_migration in (
    "canonicalizeLegacyCharacterState()",
    "withLegacyFallback(session.chatState)",
    "canonicalizeLegacyChatBranchState()",
    "migrateLegacyConversationContext()",
):
    if required_chat_migration not in chat_session_codec_source:
        die(f"ChatFeature Session codec lost migration rule: {required_chat_migration}")
if "@IntoSet" not in chat_session_codec_source or "LocalChatSessionDomainCodec" not in chat_session_codec_source:
    die("ChatFeature must contribute its Session codec through DI multibinding")
if "override fun projectSummary(" not in chat_session_codec_source:
    die("ChatFeature Session codec must own Chat summary projection")
session_models_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt")
)
if "import com.labteto.dshmobile.local.chat.LocalChatMode" in session_models_source:
    die("Shared Session summary must not type its mode as a ChatFeature enum")
for shared_summary_path in (
    "app/src/main/java/com/labteto/dshmobile/local/LocalSessionRepository.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionSummaryIndex.kt",
):
    shared_summary_source = strip_comments(read(shared_summary_path))
    if "com.labteto.dshmobile.local.chat." in shared_summary_source:
        die(f"{shared_summary_path} must not import ChatFeature internals")
if "payload[\"groupChat\"]" in strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalSessionRepository.kt")
):
    die("Session repository must not interpret ChatFeature summary payloads")
transcript_history_loader = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryLoader.kt")
)
if "runtime.withModelRequestResource" not in automation_planning:
    die("Automation planning provider calls must use the shared Runtime resource capability")
if not has_call(automation_runtime, "runtimeStateStore", "withModelRequestResource"):
    die("Automation runtime must acquire model-request resources from LocalRuntimeStateStore")
if "engine.withAutomationModelRequestResource" in automation_runtime or "withAutomationModelRequestResource" in engine:
    die("Automation resource ownership must not route through LocalHarnessEngine")
if "internal val resourceScheduler = HarnessResourceScheduler(" not in runtime_state_store:
    die("LocalRuntimeStateStore must own the single process-wide HarnessResourceScheduler")
if "private val resourceScheduler = HarnessResourceScheduler(" in engine:
    die("LocalHarnessEngine must not own HarnessResourceScheduler after Kernel boundary freeze")
if "LocalWorkRunRegistry" in runtime_state_store or "com.labteto.dshmobile.local.work" in runtime_state_store:
    die("Shared Runtime capability must not depend on WorkFeature internals")

for runtime_source_path in (LOCAL_SOURCE_ROOT / "runtime").rglob("*.kt"):
    runtime_source = strip_comments(runtime_source_path.read_text(encoding="utf-8"))
    if "import com.labteto.dshmobile.local.work." in runtime_source:
        relative = runtime_source_path.relative_to(ROOT).as_posix()
        die(f"{relative} makes Shared Runtime depend on WorkFeature internals")

session_read_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionReadRuntime.kt")
)
session_files_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionFilesRuntime.kt")
)
for relative, shared_session_source in (
    ("LocalSessionReadRuntime", session_read_runtime_source),
    ("LocalSessionFilesRuntime", session_files_runtime_source),
):
    if "import com.labteto.dshmobile.local.work." in shared_session_source:
        die(f"{relative} must not depend on WorkFeature internals")
if "LocalHarnessState" in strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionAccessCoordinator.kt")
):
    die("Session access authorization must use narrow access scopes instead of aggregate app state")
if (ROOT / "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkspace.kt").exists():
    die("LocalWorkspace is shared file infrastructure and must not return to WorkFeature")
if not (ROOT / "app/src/main/java/com/labteto/dshmobile/local/files/LocalWorkspace.kt").exists():
    die("shared LocalWorkspace capability is missing")
if "provideLocalToolsManagementPort" not in feature_execution_port_module_source:
    die("app composition root must provide the Tools management port")
if "engine.toolsManagementPort" not in feature_execution_port_module_source:
    die("Tools management composition must expose the plugin-owned port, not Engine proxy methods")

tools_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt")
)
if "LocalHarnessEngine" in tools_runtime_source:
    die("LocalToolsRuntime must not depend on LocalHarnessEngine")
if not has_typed_property(tools_runtime_source, "LocalToolsManagementPort"):
    die("LocalToolsRuntime must depend on LocalToolsManagementPort")
for required_tools_delegate in (
    "management.servers()",
    "management.installedPluginIds()",
    "management.connectHttp(serverId, endpoint)",
    "management.connectStdio(serverId, command, workingDirectory)",
    "management.disconnect(serverId)",
):
    if required_tools_delegate not in tools_runtime_source:
        die("ToolsRuntime must route management through LocalToolsManagementPort: " + required_tools_delegate)

tools_management_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsManagementPort.kt")
)
if "interface LocalToolsManagementPort" not in tools_management_port_source:
    die("Tools management port contract is missing")
if "LocalHarnessEngine" in tools_management_port_source:
    die("Tools management port must not expose LocalHarnessEngine")
for removed_engine_tools_proxy in (
    "internal suspend fun mcpServersForUi(",
    "internal suspend fun connectMcpHttpForUi(",
    "internal suspend fun connectMcpStdioForUi(",
    "internal suspend fun disconnectMcpForUi(",
    "internal fun installedPluginIdsForUi(",
):
    if removed_engine_tools_proxy in engine:
        die("LocalHarnessEngine must not restore Tools management proxy API: " + removed_engine_tools_proxy)
if "internal val toolsManagementPort: LocalToolsManagementPort" not in engine:
    die("Engine composition bridge must expose one Tools management port during Stage 3 migration")

settings_runtime_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt")
)
if "LocalHarnessEngine" in settings_runtime_source:
    die("LocalSettingsRuntime must not depend on LocalHarnessEngine")
if not has_typed_property(settings_runtime_source, "LocalDiagnosticsPort"):
    die("LocalSettingsRuntime must depend on the shared diagnostics port")
for required_diagnostics_delegate in (
    "diagnostics.environmentInfo()",
    "diagnostics.diagnosticReport()",
):
    if required_diagnostics_delegate not in settings_runtime_source:
        die("Settings diagnostics must route through LocalDiagnosticsPort: " + required_diagnostics_delegate)

diagnostics_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalDiagnosticsPort.kt")
)
if "interface LocalDiagnosticsPort" not in diagnostics_port_source:
    die("shared diagnostics port contract is missing")
if "LocalHarnessEngine" in diagnostics_port_source or "LocalHarnessState" in diagnostics_port_source:
    die("shared diagnostics port must not expose Engine or aggregate state")
if "provideLocalDiagnosticsPort" not in feature_execution_port_module_source:
    die("app composition root must provide the shared diagnostics port")
if "engine.diagnosticsPort" not in feature_execution_port_module_source:
    die("diagnostics composition must expose the migrated port, not Engine proxy methods")
for removed_engine_diagnostics_proxy in (
    "internal suspend fun environmentInfoForUi(",
    "internal suspend fun diagnosticReportForUi(",
):
    if removed_engine_diagnostics_proxy in engine:
        die("LocalHarnessEngine must not restore Settings diagnostics proxy API: " + removed_engine_diagnostics_proxy)
if "internal val diagnosticsPort: LocalDiagnosticsPort" not in engine:
    die("Engine composition bridge must expose one diagnostics port during Stage 3 migration")
if "provideLocalActiveSessionScopeProvider" not in feature_execution_port_module_source:
    die("app composition root must adapt active Work session scope into the Shared Session access contract")
if "provideLocalSessionLifecyclePort" not in feature_execution_port_module_source:
    die("app composition root must provide the Session lifecycle port")
if "engine.sessionLifecyclePort" not in feature_execution_port_module_source:
    die("Session lifecycle composition must expose the migrated lifecycle port, not Engine proxy methods")

session_lifecycle_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionLifecyclePort.kt")
)
for required_lifecycle_contract in (
    "interface LocalSessionLifecyclePort",
    "fun createSession(",
    "fun switchDomainMode(",
    "fun switchUsageMode(",
    "fun switchSession(",
    "suspend fun deleteSessions(",
):
    if required_lifecycle_contract not in session_lifecycle_port_source:
        die("Session lifecycle port contract is incomplete: " + required_lifecycle_contract)
if "LocalHarnessEngine" in session_lifecycle_port_source or "LocalHarnessState" in session_lifecycle_port_source:
    die("Session lifecycle port must not expose Engine or writable aggregate state")
for removed_engine_lifecycle_proxy in (
    "internal fun createSession(",
    "internal fun newSession()",
    "internal fun switchSessionDomainMode(",
    "internal fun switchUsageMode(",
    "internal fun switchSession(",
    "internal suspend fun deleteSessions(",
):
    if removed_engine_lifecycle_proxy in engine:
        die("LocalHarnessEngine must not restore Session lifecycle proxy API: " + removed_engine_lifecycle_proxy)
if "internal val sessionLifecyclePort: LocalSessionLifecyclePort" not in engine:
    die("Engine composition bridge must expose one Session lifecycle port during Stage 3 migration")
if "private var sessionTransitioning" in engine:
    die("LocalHarnessEngine must not own Session transition state")
if "get() = runtimeStateStore.sessionTransitioning" not in engine:
    die("Engine migration code must read the Shared Runtime Session transition fact")
if "runtimeStateStore.beginSessionTransition()" not in engine or "runtimeStateStore.endSessionTransition()" not in engine:
    die("Session transition mutation must stay owned by Shared Runtime")

conversation_files_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalConversationFilesCoordinator.kt")
)
if (
    "import com.labteto.dshmobile.local.work.LocalWorkspace" in conversation_files_source
    or "private val workspace: LocalWorkspace" in conversation_files_source
):
    die("Session conversation-files capability must depend on workspace file ports, not WorkFeature LocalWorkspace")
if "observeResourceSnapshots" not in runtime_state_store:
    die("Shared Runtime resource ownership must expose snapshots without importing Feature state")
if not has_call(work_run_registry, "runtimeStateStore", "observeResourceSnapshots"):
    die("WorkFeature must observe shared resource snapshots without Engine mediation")
if not has_call(work_run_registry, "runtimeStateStore", "resourceSnapshot"):
    die("New Work bindings must project the current shared resource snapshot on attach")
if "jobOwner = LocalRuntimeJobOwner.persistent(context, json)" not in runtime_state_store:
    die("Shared Runtime must own the persistent process-wide background job manager")
if "observeJobSnapshots" not in runtime_state_store:
    die("Shared Runtime background jobs must expose snapshots without importing Feature state")
if not has_call(work_run_registry, "runtimeStateStore", "observeJobSnapshots"):
    die("WorkFeature must observe shared background-job snapshots without Engine mediation")
if "runtimeStateStore.jobManager.output" not in work_runtime_source or "runtimeStateStore.jobManager.kill" not in work_runtime_source:
    die("WorkRuntime must access background jobs through the shared Runtime capability")
if "LocalWorkPlanModeCoordinator" not in work_runtime_source or "engine.setPlanMode" in work_runtime_source:
    die("Work plan-mode ownership must stay inside WorkFeature")
if not has_call(work_runtime_source, "workRunRegistry", "requestCancel") or "val sessionId = runtimeStateStore.currentSessionId" not in work_runtime_source:
    die("Work session-bound cancellation must stay inside WorkFeature")
if "foregroundRunHandle = LocalAgentRunHandle(" not in runtime_state_store:
    die("Shared Runtime must own one foreground LocalAgentRunHandle")
for legacy_foreground_fact in (
    "foregroundModelHistory",
    "foregroundRunLock",
    "foregroundPendingInputs",
    "foregroundJob",
    "foregroundTranscriptProjectionCursor",
    "foregroundTurnsSinceModelHistoryCheckpoint",
):
    if legacy_foreground_fact in runtime_state_store:
        die(f"Shared Runtime must not split foreground run facts again: {legacy_foreground_fact}")
if "private val modelHistory = LocalModelHistoryBuffer()" in engine:
    die("LocalHarnessEngine must not recreate foreground model-history ownership")
if "internal fun setPlanMode(enabled: Boolean)" in engine:
    die("LocalHarnessEngine must not own Work plan-mode transitions")
if "LocalPersistentJobStore(" in engine or "LocalJobManager(scope" in engine:
    die("LocalHarnessEngine must not own the process-wide background job manager")
if "runtimeStateStore.observeResourceSnapshots" in engine or "projectResourceSnapshotToSessionStates" in engine:
    die("LocalHarnessEngine must not bridge Runtime resource snapshots into WorkFeature")
work_binding_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunBinding.kt")
)
if "projectResourceSnapshotToSessionStates" in work_binding_source:
    die("Legacy cross-layer resource projection helper must stay removed")
if "val runHandle: LocalAgentRunHandle" not in work_binding_source:
    die("WorkFeature must reference the shared LocalAgentRunHandle for runtime facts")
for forbidden_work_run_fact in (
    "val modelHistory = LocalModelHistoryBuffer",
    "val pendingInputs = AgentInputQueue",
    "var job: Job?",
    "var mirrorJob: Job?",
    "var transcriptProjectionCursor:",
    "var turnsSinceModelHistoryCheckpoint:",
):
    if forbidden_work_run_fact in work_binding_source:
        die(f"LocalWorkRunBinding must not recreate shared run fact: {forbidden_work_run_fact}")

run_handle_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunHandle.kt")
)
for required_run_fact in (
    "val modelHistory = LocalModelHistoryBuffer()",
    "val pendingInputs = AgentInputQueue(maxPendingInputs)",
    "var job: Job? = null",
    "var transcriptProjectionCursor: Long? = initialTranscriptProjectionCursor",
    "var turnsSinceModelHistoryCheckpoint: Int = 0",
    "internal fun rebindSession(nextSessionId: String)",
):
    if required_run_fact not in run_handle_source:
        die(f"Shared LocalAgentRunHandle is missing runtime ownership fact: {required_run_fact}")
if "com.labteto.dshmobile.local.work." in run_handle_source:
    die("Shared LocalAgentRunHandle must not depend on WorkFeature internals")

for runtime_consumer_root in (
    LOCAL_SOURCE_ROOT,
    ROOT / "app/src/test/java/com/labteto/dshmobile/local",
):
    for runtime_consumer_path in runtime_consumer_root.rglob("*.kt"):
        runtime_consumer = strip_comments(runtime_consumer_path.read_text(encoding="utf-8"))
        for removed_runtime_access in (
            ".foregroundModelHistory",
            ".foregroundRunLock",
            ".foregroundPendingInputs",
            ".foregroundJob",
            ".foregroundTranscriptProjectionCursor",
            ".foregroundTurnsSinceModelHistoryCheckpoint",
        ):
            if removed_runtime_access in runtime_consumer:
                relative = runtime_consumer_path.relative_to(ROOT).as_posix()
                die(f"{relative} still consumes removed foreground run fact: {removed_runtime_access}")

for removed_work_binding_access in (
    "binding.modelHistory",
    "binding.pendingInputs",
    "binding.job",
    "binding.mirrorJob",
    "binding.transcriptProjectionCursor",
    "binding.turnsSinceModelHistoryCheckpoint",
):
    if removed_work_binding_access in engine:
        die(f"LocalHarnessEngine still consumes removed Work binding fact: {removed_work_binding_access}")
    for test_path in (ROOT / "app/src/test/java/com/labteto/dshmobile/local").rglob("*.kt"):
        test_source = strip_comments(test_path.read_text(encoding="utf-8"))
        if removed_work_binding_access in test_source:
            relative = test_path.relative_to(ROOT).as_posix()
            die(f"{relative} still consumes removed Work binding fact: {removed_work_binding_access}")
if not has_call(automation_worker, "AutomationExecutionRegistry", "tryAcquire"):
    die("Automation scheduled/manual execution must share one task runtime lease")
if "AutomationExecutionRegistry.tryAcquire(id) ?: return Result.retry()" not in automation_worker:
    die("Manual Automation execution must queue/retry on lease contention instead of reporting false success")
generation_preflight = automation_worker.find("preflightTask.scheduleGeneration != requestedGeneration")
task_lease_pos = automation_worker.find("AutomationExecutionRegistry.tryAcquire(id)")
if generation_preflight < 0 or task_lease_pos < 0 or generation_preflight > task_lease_pos:
    die("Stale Automation generations must be rejected before acquiring the task execution lease")
if "predicate = { it.scheduleGeneration == requestedGeneration }" not in automation_settlement:
    die("Automation Worker terminal writes must be guarded by schedule generation")
if "LocalSessionRuntimeRegistry::hasLiveOwner" not in session_event_log:
    die("Session crash-tail repair must respect live in-process session owners")
if "LocalSessionRuntimeKind.SESSION_DELETE" not in session_lifecycle or ".acquireAll(" not in session_lifecycle:
    die("Session deletion must hold runtime ownership until durable deletion is complete")
visible_turn_pos = automation_session_transaction.find("acquireVisibleTurn(targetSessionId, automationJob)")
session_owner_pos = automation_session_transaction.find("budget.acquireSession(targetSessionId, LocalSessionRuntimeKind.AUTOMATION_CHAT)")
if visible_turn_pos < 0 or session_owner_pos < 0 or visible_turn_pos > session_owner_pos:
    die("Automation Chat must acquire the visible turn before session ownership to preserve lock ordering")
if "user_activity_during_generation" not in automation_session_transaction:
    die("Automation Chat must reject a proactive reply when user activity arrives during generation")
if "fun submitWhenIdle(" not in session_runtime_registry or "tryAcquire(sessionId, LocalSessionRuntimeKind.MAINTENANCE)" not in session_runtime_registry:
    die("Load-time session maintenance must use a per-session lease outside the registry monitor")
for budget_name in (
    "LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD",
    "LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD",
):
    if budget_name not in transcript_history_loader:
        die(f"Foreground transcript history must keep bounded total-load budget: {budget_name}")

for helper_path in (
    "app/src/main/java/com/labteto/dshmobile/local/chat/PersonaAutoFillService.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/PersonaInspectionService.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/GroupAnnouncementService.kt",
):
    helper_source = strip_comments(read(helper_path))
    if "modelGateway.withFrozenRoute(profileId, model, baseUrl)" not in helper_source:
        die(f"{helper_path} must freeze the explicit selected profile across the helper operation")

responses_client = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/model/OpenAiResponsesClient.kt"))
for required in ("validateRequestPayload(payload)", "CHATGPT_PLAN_STREAM_INTERRUPTED", "stream_interrupted_after_admission"):
    if required not in responses_client:
        die(f"Responses retry/input safety contract is missing: {required}")

model_configuration = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/LocalModelConfigurationCoordinator.kt"))
if "synchronizeCredentialSelection(profile)" not in model_configuration:
    die("Model profile selection must synchronize the bound ChatGPT account registration")

chat_refresh = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatContextRefreshCoordinator.kt")
)
if "profile: LocalModelProfile" not in chat_refresh or "requestPlanner(before, prompt, boundEventLog, profile)" not in chat_refresh:
    die("Chat post-turn refresh and retries must retain the originating model profile")
if "modelRequests.complete(" not in chat_refresh or "profile = profile" not in chat_refresh:
    die("Chat post-turn planner must pass its frozen profile into Shared model requests")
if "messages = chatPostTurnModelMessages(prompt)" not in chat_refresh:
    die("Chat post-turn requests must include a real model input, not system-only instructions")
if "modelRequestMarkerOrNull()?.let" in chat_refresh:
    die("Chat post-turn planner must not re-read mutable active model identity")
if "profileId = runSnapshot.modelState.modelSelection.activeProfileId" not in work_agent_turn_source:
    die("Work foreground runs must freeze the exact selected model profile id")
if "private fun scheduleChatPostTurn(" in engine:
    die("Chat PostTurn scheduling proxy must not return to LocalHarnessEngine")
if "postTurn.schedule(" not in chat_direct_turn_source:
    die("Direct Chat turn completion must stay wired to the Chat PostTurn coordinator")

engine_composition_bridges = set(
    re.findall(r"\bengine\s*\.\s*([A-Za-z0-9_]+)", feature_execution_port_module_source)
)
unexpected_engine_bridges = sorted(
    engine_composition_bridges - ENGINE_COMPOSITION_BRIDGE_ALLOWLIST
)
if unexpected_engine_bridges:
    die(
        "new Stage-3 Engine-backed composition bridge(s): "
        + ", ".join(unexpected_engine_bridges)
        + "; add a real capability owner instead of substituting another Engine proxy"
    )
stale_stage3_engine_bridges = sorted(
    ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST - engine_composition_bridges
)
if stale_stage3_engine_bridges:
    die(
        "stale Stage-3 Engine composition bridge allowlist entries: "
        + ", ".join(stale_stage3_engine_bridges)
        + "; tighten Stage 3 in the same migration that removes the bridge"
    )

stale_stage4_automation_bridges = sorted(
    ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST - engine_composition_bridges
)
if stale_stage4_automation_bridges:
    die(
        "stale Stage-4 Automation Engine bridge allowlist entries: "
        + ", ".join(stale_stage4_automation_bridges)
        + "; tighten Stage 4 when Automation stops using the Engine composition source"
    )

for removed_private_business_method in ENGINE_REMOVED_PRIVATE_BUSINESS_METHODS:
    if re.search(
        rf"\bprivate\s+(?:suspend\s+)?fun\s+{re.escape(removed_private_business_method)}\s*\(",
        engine,
    ):
        die(
            "Removed Engine private business/helper implementation must not return: "
            + removed_private_business_method
        )

work_turn_port_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnPort.kt")
)
if "startRegeneration" in work_turn_port_source:
    die("Work regeneration must not return to the transitional Engine-backed turn port")

work_reply_regenerator_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkReplyRegenerator.kt")
)
for required_work_regeneration_owner in (
    "@Inject constructor(",
    "LocalSessionRuntimeRegistry.withOwner(",
    "LocalExecutionService.withTurn(",
):
    if required_work_regeneration_owner not in work_reply_regenerator_source:
        die(
            "WorkFeature regeneration owner is incomplete: "
            + required_work_regeneration_owner
        )
if "LocalWorkReplyRegenerator(" in engine:
    die("LocalHarnessEngine must not construct the Work regeneration owner")

work_run_binding_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunBinding.kt")
)
work_turn_starter_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkTurnStarter.kt")
)
if "pruneToolResult:" in work_run_binding_source or "pruneToolResult:" in work_turn_starter_source:
    die("Work run ownership must not regain the legacy Engine tool-result pruning callback")
if "contentAlreadyBounded = true" not in strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkAgentTurnExecutor.kt")
):
    die("Work tool transcript projection must prove tool output is bounded before identity projection")

stage3_feature_roots = {
    method
    for method in ENGINE_STAGE3_FEATURE_ROOT_CANDIDATES
    if re.search(rf"\bprivate\s+(?:suspend\s+)?fun\s+{re.escape(method)}\s*\(", engine)
}
unexpected_stage3_roots = sorted(
    stage3_feature_roots - ENGINE_STAGE3_FEATURE_ROOT_ALLOWLIST
)
if unexpected_stage3_roots:
    die(
        "LocalHarnessEngine regained removed Stage-3 Feature business root(s): "
        + ", ".join(unexpected_stage3_roots)
    )
stale_stage3_roots = sorted(
    ENGINE_STAGE3_FEATURE_ROOT_ALLOWLIST - stage3_feature_roots
)
if stale_stage3_roots:
    die(
        "stale Stage-3 Engine business-root allowlist entries: "
        + ", ".join(stale_stage3_roots)
        + "; tighten the ratchet when ownership moves into the Feature"
    )

for transactional_chat_owner in (
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatPersonaCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalCharacterBehaviorTuningCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatMembershipCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalReplySuggestionCoordinator.kt",
):
    transactional_source = strip_comments(read(transactional_chat_owner))
    if "appendChatDomainStateCommit" not in transactional_source:
        die(f"{transactional_chat_owner} must commit the durable Chat domain event before projection")

chat_branch_owner = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatBranchCoordinator.kt")
)
if "appendChatProjectionCommit" not in chat_branch_owner:
    die("Chat branch selection must atomically commit transcript/model-history/domain projection")

unexpected_consumers = sorted(engine_consumers - ENGINE_CONSUMER_ALLOWLIST)
if unexpected_consumers:
    die(
        "new direct LocalHarnessEngine consumer(s): "
        + ", ".join(unexpected_consumers)
        + "; depend on a capability boundary instead"
    )
stale_consumers = sorted(ENGINE_CONSUMER_ALLOWLIST - engine_consumers)
if stale_consumers:
    die("stale LocalHarnessEngine allowlist entries: " + ", ".join(stale_consumers))

ui_root = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"
aggregate_ui_consumers = set()
for path in ui_root.rglob("*.kt"):
    text = strip_comments(path.read_text(encoding="utf-8"))
    if re.search(r"\bLocalHarnessState\b", text):
        aggregate_ui_consumers.add(path.relative_to(ROOT).as_posix())
unexpected_ui_consumers = sorted(aggregate_ui_consumers - UI_AGGREGATE_STATE_ALLOWLIST)
if unexpected_ui_consumers:
    die(
        "UI must consume projected state instead of LocalHarnessState: "
        + ", ".join(unexpected_ui_consumers)
    )

stale_ui_consumers = sorted(UI_AGGREGATE_STATE_ALLOWLIST - aggregate_ui_consumers)
if stale_ui_consumers:
    die(
        "stale UI aggregate-state allowlist entries: "
        + ", ".join(stale_ui_consumers)
        + "; remove exemptions as soon as UI moves to projected state"
    )

android_device_provider_allowlist = {
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalPluginComposition.kt",
}
for path in local_root.rglob("*.kt"):
    relative = path.relative_to(ROOT).as_posix()
    source = strip_comments(path.read_text(encoding="utf-8"))
    if relative not in android_device_provider_allowlist and re.search(r"\bAndroidDeviceProvider\b", source):
        die(
            f"{relative} depends on AndroidDeviceProvider directly; "
            "only the plugin composition root may construct platform providers"
        )

subagent_factory_path = "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentRunnerFactory.kt"
subagent_factory = strip_comments(read(subagent_factory_path))
if "HarnessVirtualDisplayProvider" not in subagent_factory:
    die("LocalSubagentRunnerFactory must depend on HarnessVirtualDisplayProvider")

tool_registry_source = strip_comments(
    read("harness-core/src/main/kotlin/com/labteto/dshmobile/harness/tools/ToolRegistry.kt")
)
for required_contract in (
    "val access: ToolAccess,",
    "val approvalPolicy: ToolApprovalPolicy,",
    "val exposure: ToolExposure,",
    "val metadata: ToolMetadata,",
    "validateToolRegistration(",
):
    if required_contract not in tool_registry_source:
        die(f"HarnessTool registration contract is missing: {required_contract}")

deepseek_client = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/DeepSeekClient.kt"))
if "object LocalToolCatalog" in deepseek_client:
    die("LocalToolCatalog must stay outside the model transport client")
tool_catalog = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolCatalog.kt"))
if "functionToolSchema(" not in tool_catalog:
    die("LocalToolCatalog must build model schemas through the shared functionToolSchema")

tool_router = strip_comments(read("app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolRouter.kt"))
for forbidden in (
    "optionalExact",
    "familyTags(",
    'startsWith("android_")',
    'startsWith("vision_")',
    'startsWith("github_")',
    'startsWith("mcp_")',
    'startsWith("lsp_")',
    'startsWith("webhook_")',
):
    if forbidden in tool_router:
        die(f"LocalToolRouter must use ToolExposure/ToolMetadata instead of naming heuristics: {forbidden}")

tool_registration_files = (
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalBuiltinPlugin.kt",
    "app/src/main/java/com/labteto/dshmobile/local/vision/LocalVisionPlugin.kt",
    "app/src/main/java/com/labteto/dshmobile/automation/AutomationPlugin.kt",
    "app/src/main/java/com/labteto/dshmobile/automation/HarnessWebhook.kt",
    "harness-device-android/src/main/java/com/labteto/dshmobile/device/AndroidDevicePlugin.kt",
    "harness-runtime-android/src/main/kotlin/com/labteto/dshmobile/runtime/AndroidRuntimePlugin.kt",
    "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/github/GitHubConnectorPlugin.kt",
    "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/lsp/LspPlugin.kt",
    "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePlugin.kt",
)
for relative in tool_registration_files:
    source = strip_comments(read(relative))
    if 'put("type", "function")' in source:
        die(f"{relative} rebuilds function schema locally; use shared functionToolSchema")
    for match in re.finditer(r"\bHarnessTool\s*\(", source):
        executor = source.find("executor =", match.start())
        if executor < 0:
            die(f"{relative} has a HarnessTool without an executor")
        declaration = source[match.start():executor]
        for required_field in ("access =", "approvalPolicy =", "exposure =", "metadata ="):
            if required_field not in declaration:
                die(f"{relative} HarnessTool is missing explicit {required_field[:-2].strip()} declaration")

print(
    "[architecture-guard] OK: "
    f"engine deps={dependency_count}, public methods={public_method_count}, "
    f"internal methods={internal_method_count}, "
    f"aggregate fields={state_field_count}"
)
