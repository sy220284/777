#!/usr/bin/env python3
"""Ratchet architectural hotspots so new features cannot silently re-centralize the app."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]

ENGINE_MAX_PUBLIC_METHODS = 0
ENGINE_MAX_INTERNAL_METHODS = 80
ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES = 19
AGGREGATE_STATE_MAX_FIELDS = 28

HOTSPOT_CONSTRUCTOR_DEPENDENCY_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatTurnExecutor.kt": ("LocalGroupChatTurnExecutor", 26),
    "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt": ("LocalSubagentRunner", 23),
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt": ("LocalAutomationChatCoordinator", 14),
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt": ("LocalModelRequestCoordinator", 12),
}

RUNTIME_ENGINE_REFERENCE_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt": 19,
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt": 12,
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt": 15,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt": 4,
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt": 8,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt": 6,
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
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalTaskRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt",
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

engine_path = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
engine = read(engine_path)
constructor = re.search(
    r"class LocalHarnessEngine @Inject constructor\((.*?)\n\) \{",
    engine,
    re.DOTALL,
)
if constructor is None:
    die("unable to locate LocalHarnessEngine constructor")
dependency_count = len(re.findall(r"private val\s+[A-Za-z0-9_]+\s*:", constructor.group(1)))
if dependency_count > ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES:
    die(
        f"LocalHarnessEngine constructor has {dependency_count} dependencies "
        f"(ratchet: {ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES})"
    )

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
for legacy_work_field in ("plan", "todos", "goal", "planMode"):
    if re.search(rf"^\s*val\s+{legacy_work_field}\s*:", aggregate_state_source, re.MULTILINE):
        die(
            f"LocalHarnessState must not reintroduce flattened Work field: {legacy_work_field}"
        )

work_state = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkState.kt")
)
for owned_field in ("plan", "todos", "goal", "planMode"):
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
    "jobs",
    "workflowProgress",
    "queuedInputCount",
    "resources",
    "contextChars",
    "contextBudgetChars",
    "pendingApproval",
    "pendingQuestion",
):
    if re.search(rf"^\\s*val\\s+{legacy_kernel_field}\\s*:", aggregate_state_source, re.MULTILINE):
        die(
            f"LocalHarnessState must not reintroduce flattened Kernel field: {legacy_kernel_field}"
        )

kernel_state = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/runtime/LocalKernelState.kt")
)
for owned_field in (
    "running",
    "jobs",
    "workflowProgress",
    "queuedInputCount",
    "resources",
    "contextChars",
    "contextBudgetChars",
    "pendingApproval",
    "pendingQuestion",
):
    if not re.search(rf"\\bval\\s+{owned_field}\\s*:", kernel_state):
        die(f"LocalKernelState must own Kernel field: {owned_field}")

work_progress = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkProgressCoordinator.kt")
)
for required_mutation in (
    "it.copy(work = it.work.copy(plan = normalized))",
    "it.copy(work = it.work.copy(todos = items))",
    "it.copy(work = it.work.copy(goal = goal))",
    "it.copy(work = it.work.copy(goal = updated))",
):
    if required_mutation not in work_progress:
        die("Work mutations must write through LocalWorkState: " + required_mutation)

stream_state_start = models.find("data class LocalHarnessStreamingState(")
stream_state_end = models.find("\n)", stream_state_start)
if stream_state_start < 0 or stream_state_end < 0:
    die("unable to locate LocalHarnessStreamingState")
stream_state = models[stream_state_start:stream_state_end]
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
    read("app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntimeRegistry.kt")
)
session_lifecycle = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/local/LocalSessionLifecycleCoordinator.kt")
)
transcript_history_loader = strip_comments(
    read("app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryLoader.kt")
)
if "runtime.withModelRequestResource" not in automation_planning:
    die("Automation planning provider calls must use the Engine-owned model request resource lease")
if "engine.withAutomationModelRequestResource(block)" not in automation_runtime:
    die("Automation runtime must delegate model request resource ownership to LocalHarnessEngine")
if 'resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST, "automation-planning", block)' not in engine:
    die("Automation planning must reuse the Engine-owned HarnessResourceScheduler")
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
