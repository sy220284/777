#!/usr/bin/env python3
"""Ratchet architectural hotspots so new features cannot silently re-centralize the app."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]

LINE_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalCharacterBehaviorTuningCoordinator.kt": 66,
    "app/src/main/java/com/labteto/dshmobile/local/chat/CharacterBehaviorTuningPersistence.kt": 135,
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkProgressCoordinator.kt": 99,
    "app/src/main/java/com/labteto/dshmobile/local/web/LocalWebSearchClient.kt": 174,
    "app/src/main/java/com/labteto/dshmobile/local/web/LocalWebDiagnostics.kt": 127,
    "app/src/main/java/com/labteto/dshmobile/local/web/LocalWebHttpPolicy.kt": 33,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/Events.kt": 512,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/LlmContent.kt": 233,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/LlmMessages.kt": 111,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/LlmRequests.kt": 61,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/SessionTurnPayloads.kt": 163,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/SessionControlPayloads.kt": 100,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/SessionWorkflowPayloads.kt": 79,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/SessionSchedulePayloads.kt": 71,
    "core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/SessionCompactionPayloads.kt": 59,

    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt": 4943,
    "app/src/main/java/com/labteto/dshmobile/data/SessionStore.kt": 1344,
    "app/src/main/java/com/labteto/dshmobile/data/SessionLifecycleRuntime.kt": 50,
    "app/src/main/java/com/labteto/dshmobile/data/SessionSlashCommandRuntime.kt": 71,
    "app/src/main/java/com/labteto/dshmobile/data/SessionGoalRuntime.kt": 61,
    "app/src/main/java/com/labteto/dshmobile/data/SessionWorkspaceRuntime.kt": 73,
    "app/src/main/java/com/labteto/dshmobile/data/SessionInteractionRuntime.kt": 201,
    "app/src/main/java/com/labteto/dshmobile/data/SessionTurnCommandRuntime.kt": 116,
    "app/src/main/java/com/labteto/dshmobile/data/SessionSearchRuntime.kt": 49,
    "app/src/main/java/com/labteto/dshmobile/data/SessionSubagentRuntime.kt": 176,
    "app/src/main/java/com/labteto/dshmobile/data/SessionAttachmentTransfer.kt": 100,
    "app/src/main/java/com/labteto/dshmobile/data/SessionCatalogRuntime.kt": 151,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt": 506,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationSurface.kt": 1011,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationComposer.kt": 351,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessConfigurationComponents.kt": 163,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/TasksScreen.kt": 320,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/TasksViewModel.kt": 315,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/TaskCardComponents.kt": 355,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/TaskEditorComponents.kt": 706,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt": 240,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsViewModel.kt": 340,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsScreen.kt": 1041,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/main/ChatListDrawer.kt": 590,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/main/ChatListDrawerComponents.kt": 571,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/UsageCalculationPage.kt": 480,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/UsageCalculationComponents.kt": 593,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/AppearanceSettingsComponents.kt": 450,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/AdvancedSettingsSections.kt": 496,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/LocalModelSettingsSections.kt": 533,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/MemorySettingsSections.kt": 455,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/AgentDeviceSettingsSections.kt": 285,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/RemoteSettingsController.kt": 190,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/DeviceCapabilitiesController.kt": 47,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/MemorySettingsController.kt": 72,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalPersonaGalleryUiController.kt": 404,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryController.kt": 143,
    "app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomation.kt": 393,
    "app/src/main/java/com/labteto/dshmobile/automation/AutomationDocumentStore.kt": 198,
    "app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomationScheduler.kt": 603,
    "app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomationWorker.kt": 450,
    "app/src/main/java/com/labteto/dshmobile/automation/AutomationPlugin.kt": 217,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionPlanner.kt": 60,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionStateReducer.kt": 640,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionPlanParser.kt": 72,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionNormalization.kt": 5,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionPromptBuilder.kt": 158,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionModels.kt": 145,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatPersonaGalleryStore.kt": 443,
    "app/src/main/java/com/labteto/dshmobile/local/chat/PersonaGalleryModels.kt": 118,
    "app/src/main/java/com/labteto/dshmobile/local/chat/PersonaGalleryMergePolicy.kt": 431,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryStore.kt": 218,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryDocumentStore.kt": 105,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryEntryPolicy.kt": 280,
    "app/src/main/java/com/labteto/dshmobile/local/memory/MemoryStore.kt": 490,
    "app/src/main/java/com/labteto/dshmobile/local/memory/MemoryDocumentStore.kt": 188,
    "app/src/main/java/com/labteto/dshmobile/local/memory/MemoryRecordMaintenance.kt": 144,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStateContent.kt": 20,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalHarnessUiState.kt": 130,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt": 75,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalTaskRuntime.kt": 20,
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt": 24,
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolSchemaProjection.kt": 87,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt": 80,
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt": 46,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt": 19,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelHistoryBuffer.kt": 74,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelHistoryCompaction.kt": 40,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalRecoveryModelRoute.kt": 19,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalPromptContext.kt": 128,
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentRunnerFactory.kt": 135,
    "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt": 585,
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentModelStepExecutor.kt": 153,
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentStructureRecovery.kt": 81,
    "app/src/main/java/com/labteto/dshmobile/local/LocalWebProvider.kt": 418,
    "app/src/main/java/com/labteto/dshmobile/local/web/LocalWebTargetResolver.kt": 261,
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentHistoryPolicy.kt": 66,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt": 316,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkRecovery.kt": 64,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt": 523,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessRuntimePolicy.kt": 76,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessDefaults.kt": 76,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessResourceProjection.kt": 23,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalBundledRuntimeManager.kt": 56,
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalTranscriptRuntime.kt": 67,
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupExecutionModels.kt": 22,
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelConfigurationCoordinator.kt": 172,
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt": 64,
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt": 24,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalUiRuntime.kt": 17,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStreamingComponents.kt": 150,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalConversationSurfaceState.kt": 140,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStateContent.kt": 24,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalWorkUiState.kt": 87,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalWorkStateContent.kt": 16,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationHeaders.kt": 344,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalWorkSurfaceComponents.kt": 421,
}

ENGINE_MAX_PUBLIC_METHODS = 0
ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES = 19
AGGREGATE_STATE_MAX_FIELDS = 55
LOCAL_ROOT_MAX_KOTLIN_FILES = 23
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


for relative, maximum in LINE_BUDGETS.items():
    lines = len(read(relative).splitlines())
    if lines > maximum:
        die(f"{relative} grew to {lines} lines (ratchet: {maximum}); move the new responsibility out")
    print(f"[architecture-guard] {relative}: {lines}/{maximum} lines")

# Diary privacy is a security/knowledge-boundary invariant, not a tuning preference.
# Group prompts may receive PUBLIC diary entries only. Do not relax this to "anything except PRIVATE"
# and do not bypass the central policy with a second group-memory path.
diary_recall_policy = read(
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryRecallPolicy.kt"
)
diary_store = read("app/src/main/java/com/labteto/dshmobile/local/chat/ChatDiaryStore.kt")
if (
    "disclosure == ChatDiaryDisclosure.PUBLIC" not in diary_recall_policy
    or "canExposeDiaryToGroup(entry.disclosure)" not in diary_store
):
    die("group diary recall must stay centralized and PUBLIC-only")
if "!= ChatDiaryDisclosure.PRIVATE" in diary_store:
    die("group diary recall must not regress to the old non-private shortcut")
if "crossScopeAdmissible" in diary_store or "crossScopeAdmissible" in diary_recall_policy:
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

local_root = ROOT / "app/src/main/java/com/labteto/dshmobile/local"
local_root_files = list(local_root.glob("*.kt"))
if len(local_root_files) > LOCAL_ROOT_MAX_KOTLIN_FILES:
    die(
        f"local/ root has {len(local_root_files)} Kotlin files "
        f"(ratchet: {LOCAL_ROOT_MAX_KOTLIN_FILES}); place new code in a capability package"
    )

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
if "profileId = runSnapshot.modelSelection.activeProfileId" not in engine:
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
    f"aggregate fields={state_field_count}, local root files={len(local_root_files)}"
)
