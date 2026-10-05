#!/usr/bin/env python3
"""Ratchet architectural hotspots so new features cannot silently re-centralize the app."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]

LOCAL_SOURCE_ROOT = ROOT / "app/src/main/java/com/labteto/dshmobile/local"

ENGINE_MAX_PUBLIC_METHODS = 0
ENGINE_MAX_INTERNAL_METHODS = 59
ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES = 17
AGGREGATE_STATE_MAX_FIELDS = 28

HOTSPOT_CONSTRUCTOR_DEPENDENCY_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatTurnExecutor.kt": ("LocalGroupChatTurnExecutor", 26),
    "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt": ("LocalSubagentRunner", 23),
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt": ("LocalAutomationChatCoordinator", 14),
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt": ("LocalModelRequestCoordinator", 12),
}

RUNTIME_ENGINE_REFERENCE_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt": 3,
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt": 0,
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt": 12,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt": 3,
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt": 8,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt": 3,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt": 7,
}
PROJECTION_FIELD_BUDGETS = {
    "LocalHarnessSettingsState": 17,
    "LocalHarnessTaskState": 4,
    "LocalHarnessShellState": 10,
}

ENGINE_CONSUMER_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt",
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt",
}

UI_AGGREGATE_STATE_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStateContent.kt",
}


def die(message: str) -> None:
    print(f"[architecture-guard] {message}", file=sys.stderr)
    raise SystemExit(1)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//.*$", "", source, flags=re.MULTILINE)


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

runtime_state_store_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt")
)
if re.search(r"\binternal\s+val\s+mutableState\s*:", runtime_state_store_source):
    die("LocalRuntimeStateStore must not expose its writable aggregate state")

# Architecture 3.0 migration seam: aggregate projection access is temporary and consumer-frozen.
# New code must use a domain StatePort or an explicit Shared Runtime projection command instead.
aggregate_projection_migration_allowlist = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/GroupAnnouncementSaveCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalCharacterBehaviorTuningCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatBranchCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatPersonaCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatPersonaCorrectionCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatMembershipCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalReplySuggestionCommit.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalReplySuggestionCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/settings/LocalHarnessSettingsCoordinator.kt",
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

work_progress_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkProgressCoordinator.kt")
)
if "LocalHarnessState" in work_progress_source or "MutableStateFlow" in work_progress_source:
    die("LocalWorkProgressCoordinator must depend only on LocalWorkStatePort")
if "private val state: LocalWorkStatePort" not in work_progress_source:
    die("LocalWorkProgressCoordinator lost its Work-owned state boundary")

runtime_projection_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeProjection.kt")
)
if "private val state: MutableStateFlow<LocalHarnessState>" not in runtime_projection_source:
    die("LocalRuntimeProjection must remain the narrow writable aggregate projection owner")

work_plan_mode_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkPlanModeCoordinator.kt")
)
if "runtimeStateStore.projection.setWorkPlanMode" not in work_plan_mode_source:
    die("Work plan-mode visible projection must use LocalRuntimeProjection")
if "runtimeStateStore.projection.updateContextMetrics" not in work_plan_mode_source:
    die("Work plan-mode context metrics must use LocalRuntimeProjection")

engine_path = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
engine = read(engine_path)

work_registry_source = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunRegistry.kt")
)
if "runtimeStateStore.projection.projectJobs" not in work_registry_source:
    die("Work visible job projection must use LocalRuntimeProjection")
if "runtimeStateStore.projection.projectVisibleWorkRun" not in work_registry_source:
    die("Work visible run projection must be owned by LocalWorkRunRegistry through Shared Runtime")
if "private fun mirrorVisibleWorkRun" in engine:
    die("LocalHarnessEngine must not own Work visible run projection")
if "workRunRegistry.mirrorVisible(binding)" not in engine:
    die("Engine migration call sites must delegate Work visible projection to LocalWorkRunRegistry")
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
        rf"\bclass\s+{re.escape(class_name)}\s*\((.*?)\n\)\s*\{{",
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


for relative, maximum in RUNTIME_ENGINE_REFERENCE_BUDGETS.items():
    runtime_source = strip_comments(read(relative))
    references = len(re.findall(r"\bengine\.[A-Za-z0-9_]+", runtime_source))
    if references > maximum:
        die(
            f"{relative} has {references} direct LocalHarnessEngine references "
            f"(ratchet: {maximum}); capability runtimes must own behavior instead of growing proxies"
        )
    print(
        f"[architecture-guard] {relative}: "
        f"{references}/{maximum} direct engine references"
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
if "@Singleton" not in settings_coordinator or "private val runtimeStateStore: LocalRuntimeStateStore" not in settings_coordinator:
    die("LocalHarnessSettingsCoordinator must be the injected owner of Settings runtime state mutations")
if "private val settingsCoordinator: LocalHarnessSettingsCoordinator" not in settings_runtime:
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
if "private val settingsCoordinator: LocalHarnessSettingsCoordinator" in constructor.group(1):
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
    "foregroundRunLock = Any()",
    "foregroundPendingInputs = AgentInputQueue(MAX_PENDING_INPUTS)",
    "foregroundJob: Job?",
    "foregroundTranscriptProjectionCursor: Long?",
    "cancelForegroundRun(eventLog: LocalSessionEventLog)",
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
if "runtimeStateStore.cancelForegroundRun(eventLogs.get(sessionId))" not in work_runtime_source:
    die("Work visible-run cancellation must use the shared Runtime owner")
if "check(!initialized)" not in runtime_state_store:
    die("LocalRuntimeStateStore initialization must remain single-owner")
if "runtimeStateStore.initialize(" not in engine:
    die("LocalHarnessEngine must initialize state through LocalRuntimeStateStore")
if "private val runtimeStateStore: LocalRuntimeStateStore" not in constructor.group(1):
    die("LocalHarnessEngine must receive the shared LocalRuntimeStateStore by injection")
if "private val sessionStorageRuntime: LocalSessionStorageRuntime" not in constructor.group(1):
    die("LocalHarnessEngine must consume the shared Session storage capability")
if "private val eventLogRegistry: LocalSessionEventLogRegistry" in constructor.group(1):
    die("LocalHarnessEngine must not inject Session EventLog storage separately from LocalSessionStorageRuntime")
if "LocalSessionRepository(" in engine or "LocalSessionCoordinator(" in engine:
    die("LocalHarnessEngine must not construct Session persistence owners")
for required_session_storage in (
    "LocalSessionRepository(",
    "LocalSessionCoordinator(",
    "eventLogs: LocalSessionEventLogRegistry",
):
    if required_session_storage not in session_storage_runtime:
        die("Shared Session storage ownership is incomplete: " + required_session_storage)
if "foregroundSessionId" not in runtime_state_store or "activateSession(sessionId: String)" not in runtime_state_store:
    die("LocalRuntimeStateStore must own foreground Session identity")
if "private var currentSessionId" in engine:
    die("LocalHarnessEngine must not keep a second mutable foreground Session id")
if "get() = runtimeStateStore.currentSessionId" not in engine:
    die("LocalHarnessEngine foreground Session reads must resolve through LocalRuntimeStateStore")
if "runtimeStateStore.activateSession(id)" not in engine:
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
if "settings.configureImageInputMode(mode)" not in model_runtime_source:
    die("LocalModelRuntime image input mode must be owned by LocalModelSettingsCoordinator")
if "configuration.test(apiKey, model, baseUrl, protocol, profileId)" not in model_runtime_source:
    die("LocalModelRuntime must own model connection testing through Model configuration capability")
if "engine.configureImageInputMode" in model_runtime_source:
    die("LocalModelRuntime must not route image input settings through LocalHarnessEngine")
if "KEY_IMAGE_INPUT_MODE" not in model_settings_source or "runtimeStateStore.projection.setImageInputMode(mode)" not in model_settings_source:
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
if "approvals.disableDeviceApprovalLease(runtimeStateStore.state.value.sessionId)" not in work_runtime_source:
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
if "sessionRuntime.switchChatMode(mode)" not in chat_runtime_source:
    die("Chat mode switching must route through Session capability")
if "engine.createGroupChatSession" in chat_runtime_source or "engine.createSingleChatSession" in chat_runtime_source:
    die("Chat session creation must route through Session capability")
if "engine.undoChatPersonaCorrection" in chat_runtime_source:
    die("Persona-correction undo must stay inside ChatFeature")
if "personaCorrections.undo" not in chat_runtime_source:
    die("ChatRuntime must route persona-correction undo through its Chat coordinator")
if "behaviorTuning.configure(profile)" not in chat_runtime_source or "engine.configureChatPersona" in chat_runtime_source:
    die("Chat persona tuning must stay inside ChatFeature")
if "engine.setGroupChatAnnouncement" in chat_runtime_source:
    die("Chat group announcement save must stay inside ChatFeature")
if "saveGroupChatAnnouncement(" not in chat_runtime_source or "sessionStorage.coordinator" not in chat_runtime_source:
    die("ChatRuntime must commit group announcements through shared Session storage")
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
    "LocalRuntimeStateStore",
    "LocalSessionStorageRuntime",
    "ChatPersonaStore",
):
    if required_persona_dependency not in chat_persona_coordinator:
        die("Chat persona ownership is incomplete: " + required_persona_dependency)

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
if "pluginComposition.installStartup()" not in engine:
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
if "pluginManager.installAll(pluginCatalog.ids())" not in plugin_composition:
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
transcript_history_loader = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryLoader.kt")
)
if "runtime.withModelRequestResource" not in automation_planning:
    die("Automation planning provider calls must use the shared Runtime resource capability")
if "runtimeStateStore.withModelRequestResource(block)" not in automation_runtime:
    die("Automation runtime must acquire model-request resources from LocalRuntimeStateStore")
if "engine.withAutomationModelRequestResource" in automation_runtime or "withAutomationModelRequestResource" in engine:
    die("Automation resource ownership must not route through LocalHarnessEngine")
if "internal val resourceScheduler = HarnessResourceScheduler(" not in runtime_state_store:
    die("LocalRuntimeStateStore must own the single process-wide HarnessResourceScheduler")
if "private val resourceScheduler = HarnessResourceScheduler(" in engine:
    die("LocalHarnessEngine must not own HarnessResourceScheduler after Kernel boundary freeze")
if "LocalWorkRunRegistry" in runtime_state_store or "com.labteto.dshmobile.local.work" in runtime_state_store:
    die("Shared Runtime capability must not depend on WorkFeature internals")
if "observeResourceSnapshots" not in runtime_state_store:
    die("Shared Runtime resource ownership must expose snapshots without importing Feature state")
if "runtimeStateStore.observeResourceSnapshots(::projectResourceSnapshot)" not in work_run_registry:
    die("WorkFeature must observe shared resource snapshots without Engine mediation")
if "runtimeStateStore.resourceSnapshot()" not in work_run_registry:
    die("New Work bindings must project the current shared resource snapshot on attach")
if "jobOwner = LocalRuntimeJobOwner.persistent(context, json)" not in runtime_state_store:
    die("Shared Runtime must own the persistent process-wide background job manager")
if "observeJobSnapshots" not in runtime_state_store:
    die("Shared Runtime background jobs must expose snapshots without importing Feature state")
if "runtimeStateStore.observeJobSnapshots(::projectJobSnapshot)" not in work_run_registry:
    die("WorkFeature must observe shared background-job snapshots without Engine mediation")
if "runtimeStateStore.jobManager.output" not in work_runtime_source or "runtimeStateStore.jobManager.kill" not in work_runtime_source:
    die("WorkRuntime must access background jobs through the shared Runtime capability")
if "LocalWorkPlanModeCoordinator" not in work_runtime_source or "engine.setPlanMode" in work_runtime_source:
    die("Work plan-mode ownership must stay inside WorkFeature")
if "workRunRegistry.requestCancel(sessionId)" not in work_runtime_source or "val sessionId = runtimeStateStore.currentSessionId" not in work_runtime_source:
    die("Work session-bound cancellation must stay inside WorkFeature")
if "foregroundModelHistory = LocalModelHistoryBuffer()" not in runtime_state_store:
    die("Shared Runtime must own the visible foreground model-history state")
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
if "AutomationExecutionRegistry.tryAcquire(id)" not in automation_worker:
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
    read("app/src/main/java/com/labteto/dshmobile/local/LocalChatContextRefreshCoordinator.kt")
)
if "profile: LocalModelProfile" not in chat_refresh or "requestPlanner(before, prompt, boundEventLog, profile)" not in chat_refresh:
    die("Chat post-turn refresh and retries must retain the originating model profile")
if "profile = profile" not in engine[engine.find("requestPlanner ="):engine.find("private val chatReplyCoordinator")]:
    die("Chat post-turn planner must pass its frozen profile into model requests")
if "profileId = runSnapshot.modelState.modelSelection.activeProfileId" not in engine:
    die("Foreground runs must freeze the exact selected model profile id")
if "messages = chatPostTurnModelMessages(prompt)" not in engine:
    die("Chat post-turn requests must include a real model input, not system-only instructions")
if "modelRequestMarkerOrNull()?.let" in engine[engine.find("requestPlanner ="):engine.find("private val chatReplyCoordinator")]:
    die("Chat post-turn planner must not re-read mutable active model identity")

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
