package com.labteto.dshmobile.local

import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.AgentLoop
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentToolBatchExecutor
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.session.ConversationHandoffBuilder
import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import com.labteto.dshmobile.local.agent.LocalSubagentRunnerFactory
import com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.agent.requireCompletedOutput
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatPersonaCorrectionNotice
import com.labteto.dshmobile.local.chat.ChatStyleGuard
import com.labteto.dshmobile.local.chat.LocalCharacterBehaviorTuningCoordinator
import com.labteto.dshmobile.local.chat.LocalChatBranchCoordinator
import com.labteto.dshmobile.local.chat.LocalChatContextRefreshCoordinator
import com.labteto.dshmobile.local.chat.LocalChatBranchNode
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalChatComposition
import com.labteto.dshmobile.local.chat.LocalChatDirectTurnExecutor
import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalChatMemoryRuntime
import com.labteto.dshmobile.local.chat.LocalChatPersistence
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.chat.LocalChatStatePort
import com.labteto.dshmobile.local.chat.LocalChatPersonaCorrectionCoordinator
import com.labteto.dshmobile.local.chat.LocalChatRelationshipHydrator
import com.labteto.dshmobile.local.chat.LocalChatSessionLifecyclePlanner
import com.labteto.dshmobile.local.chat.LocalChatTurnDispatcher
import com.labteto.dshmobile.local.chat.LocalChatTranscriptRuntime
import com.labteto.dshmobile.local.chat.LocalChatTurnPort
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.chat.LocalGroupChatTurnExecutor
import com.labteto.dshmobile.local.chat.LocalReplySuggestionCoordinator
import com.labteto.dshmobile.local.chat.LocalTimelineRewriteProjectionInput
import com.labteto.dshmobile.local.chat.LocalTimelineRewriteState
import com.labteto.dshmobile.local.chat.MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.activeChatBranchMessages
import com.labteto.dshmobile.local.chat.activeTranscriptForUserEdit
import com.labteto.dshmobile.local.chat.appendMaterializedChatBranchMessage
import com.labteto.dshmobile.local.chat.appendTimelineRewriteCommit
import com.labteto.dshmobile.local.chat.applySceneTurn
import com.labteto.dshmobile.local.chat.boundDurablePending
import com.labteto.dshmobile.local.chat.canonicalFactLines
import com.labteto.dshmobile.local.chat.chatBranchLastContext
import com.labteto.dshmobile.local.chat.chatBranchLastSnapshot
import com.labteto.dshmobile.local.chat.chatBranchParentContext
import com.labteto.dshmobile.local.chat.chatBranchParentState
import com.labteto.dshmobile.local.chat.chatBranchingEligible
import com.labteto.dshmobile.local.chat.chatPostTurnModelMessages
import com.labteto.dshmobile.local.chat.editableChatUserText
import com.labteto.dshmobile.local.chat.encodeChatBranchStateEvent
import com.labteto.dshmobile.local.chat.enqueuePendingDurably
import com.labteto.dshmobile.local.chat.groupTranscriptLine
import com.labteto.dshmobile.local.chat.hasChatBranchAlternatives
import com.labteto.dshmobile.local.chat.persistChatTimelineBaseline
import com.labteto.dshmobile.local.chat.projectGroupGalleryState
import com.labteto.dshmobile.local.chat.reconcileCharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.reconcileGroupCharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.recoverPendingTimelineRewriteProjection
import com.labteto.dshmobile.local.chat.replayHardChatContextFromTranscript
import com.labteto.dshmobile.local.chat.restoreBranchContext
import com.labteto.dshmobile.local.chat.restoreChatStateBefore
import com.labteto.dshmobile.local.chat.restoreGroupStateBefore
import com.labteto.dshmobile.local.chat.restoreMaterializedChatBranchState
import com.labteto.dshmobile.local.chat.rewriteChatTranscriptFromUserEdit
import com.labteto.dshmobile.local.chat.saveGroupChatAnnouncement
import com.labteto.dshmobile.local.chat.selectChatBranchVariant
import com.labteto.dshmobile.local.chat.sourceEventSequenceForMessage
import com.labteto.dshmobile.local.chat.syncChatBranchState
import com.labteto.dshmobile.local.chat.upsertChatBranchNode
import com.labteto.dshmobile.local.chat.withEditedChatUserText
import com.labteto.dshmobile.local.chat.withoutLegacyConversationContext
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.context.ContextRequest
import com.labteto.dshmobile.local.context.LocalWorkTurnPromptContext
import com.labteto.dshmobile.local.context.composeWorkTurnContext
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalPersistentJobRecoveryCoordinator
import com.labteto.dshmobile.local.lsp.parseLanguageServerCommand
import com.labteto.dshmobile.local.memory.LocalMemoryTools
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalImageCapability
import com.labteto.dshmobile.local.model.LocalImageCapabilityRegistry
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelAccountStateCoordinator
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.model.LocalModelSettingsCoordinator
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.model.LocalRequestPressureStore
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.chatSystemPrompt
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.compact
import com.labteto.dshmobile.local.model.compactOverflow
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.model.durableToolResultContent
import com.labteto.dshmobile.local.model.estimateModelTokens
import com.labteto.dshmobile.local.model.groupChatSystemPrompt
import com.labteto.dshmobile.local.model.hasLocalImageRefs
import com.labteto.dshmobile.local.model.hasMaterializedImageUrls
import com.labteto.dshmobile.local.model.imageInputUnsupported
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.model.localToolHistoryMessage
import com.labteto.dshmobile.local.model.migrateOfficialClaudeModel
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import com.labteto.dshmobile.local.model.projectRecoverableToolResult
import com.labteto.dshmobile.local.model.recordRuntimeSystemPromptUpdate
import com.labteto.dshmobile.local.model.resolveLocalImageInputMode
import com.labteto.dshmobile.local.model.toRunModelSurface
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.model.withChatTurnContext
import com.labteto.dshmobile.local.model.withEphemeralContext
import com.labteto.dshmobile.local.model.withModelToolCallEventData
import com.labteto.dshmobile.local.model.withoutLastCompletedAssistantReply
import com.labteto.dshmobile.local.model.workSystemPrompt
import com.labteto.dshmobile.local.runtime.ATTACHMENT_GC_INTERVAL_MILLIS
import com.labteto.dshmobile.local.runtime.BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.CHAT_POST_TURN_MODEL_STEP
import com.labteto.dshmobile.local.runtime.CHAT_RECENT_HISTORY_MESSAGES
import com.labteto.dshmobile.local.runtime.CHAT_ROLEPLAY_TEMPERATURE
import com.labteto.dshmobile.local.runtime.DEFAULT_BASE_URL
import com.labteto.dshmobile.local.runtime.DEFAULT_DOWNLOAD_BYTES
import com.labteto.dshmobile.local.runtime.DEFAULT_MAIN_MAX_STEPS
import com.labteto.dshmobile.local.runtime.DEFAULT_MODEL
import com.labteto.dshmobile.local.runtime.DEFAULT_MODEL_ATTEMPTS
import com.labteto.dshmobile.local.runtime.DEFAULT_SUBAGENT_MAX_STEPS
import com.labteto.dshmobile.local.runtime.DEFAULT_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.FOREGROUND_TURN_TIMEOUT_MILLIS
import com.labteto.dshmobile.local.runtime.FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.KEY_ATTACHMENT_GC_AT
import com.labteto.dshmobile.local.runtime.KEY_BASE_URL
import com.labteto.dshmobile.local.runtime.KEY_MODEL
import com.labteto.dshmobile.local.runtime.KEY_SESSION_ID
import com.labteto.dshmobile.local.runtime.LOCAL_PROJECT_ID
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalAgentProgressTracker
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.runtime.LocalAgentRunContext
import com.labteto.dshmobile.local.runtime.LocalAgentRunCoordinator
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.LocalAgentRunResourceBudget
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsPort
import com.labteto.dshmobile.local.runtime.LocalEnvironmentInfoCoordinator
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalProcessExitStatus
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.MAX_DOWNLOAD_BYTES
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.runtime.MAX_HANDOFF_CHARS
import com.labteto.dshmobile.local.runtime.MAX_PATCH_CHARS
import com.labteto.dshmobile.local.runtime.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.runtime.MAX_STREAM_PREVIEW_CHARS
import com.labteto.dshmobile.local.runtime.MAX_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL
import com.labteto.dshmobile.local.runtime.PARALLEL_SUBAGENT_TOOLS
import com.labteto.dshmobile.local.runtime.PERSONA_CORRECTION_UNDO_MILLIS
import com.labteto.dshmobile.local.runtime.PLAN_MODE_BLOCKED_TOOLS
import com.labteto.dshmobile.local.runtime.PROJECTION_BASELINE_EVENT
import com.labteto.dshmobile.local.runtime.STREAM_PREVIEW_INTERVAL_MS
import com.labteto.dshmobile.local.runtime.adaptiveAgentStepLimit
import com.labteto.dshmobile.local.runtime.adaptiveToolResultBudget
import com.labteto.dshmobile.local.runtime.appendLocalDiagnosticDetails
import com.labteto.dshmobile.local.runtime.approvalImpact
import com.labteto.dshmobile.local.runtime.canAutoApproveSafely
import com.labteto.dshmobile.local.runtime.canUseDeviceApprovalLease
import com.labteto.dshmobile.local.runtime.isolatedParallelMap
import com.labteto.dshmobile.local.runtime.localForegroundStepLimitExtender
import com.labteto.dshmobile.local.runtime.prepareLocalHarnessStartup
import com.labteto.dshmobile.local.runtime.projectExecutionJobs
import com.labteto.dshmobile.local.work.LocalWorkRecoveryContextPolicy
import com.labteto.dshmobile.local.work.structuredWorkState
import com.labteto.dshmobile.local.runtime.toLocalHarnessResourceState
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.prepareLocalSend
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalSessionDomainCreateSpec
import com.labteto.dshmobile.local.session.LocalSessionDomainModeCommand
import com.labteto.dshmobile.local.session.LocalSessionLifecyclePort
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionAccessCoordinator
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.session.LocalSessionArchiveMaintenance
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.LocalTranscriptRuntime
import com.labteto.dshmobile.local.session.buildLocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.session.localSessionPersistenceSnapshot
import com.labteto.dshmobile.local.session.localSessionSummariesOrEmpty
import com.labteto.dshmobile.local.session.projectionReplayCursor
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.settings.LocalHarnessSettingsCoordinator
import com.labteto.dshmobile.local.tools.LocalFileInspector
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import com.labteto.dshmobile.local.tools.LocalPluginCompositionFactory
import com.labteto.dshmobile.local.tools.LocalRunToolSurface
import com.labteto.dshmobile.local.tools.LocalShellTool
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import com.labteto.dshmobile.local.tools.LocalToolsManagementPort
import com.labteto.dshmobile.local.tools.boolean
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.long
import com.labteto.dshmobile.local.tools.optionalString
import com.labteto.dshmobile.local.tools.pendingToolSettlements
import com.labteto.dshmobile.local.tools.startedToolCallIdsForActiveStep
import com.labteto.dshmobile.local.tools.string
import com.labteto.dshmobile.local.usage.LocalTokenUsageContextBridge
import com.labteto.dshmobile.local.vision.LocalVisionRoute
import com.labteto.dshmobile.local.work.LocalForegroundRecoveryCoordinator
import com.labteto.dshmobile.local.work.LocalWorkTurnPort
import com.labteto.dshmobile.local.work.LocalWorkAgentTurnExecutor
import com.labteto.dshmobile.local.work.LocalWorkAgentControlBuiltinRuntime
import com.labteto.dshmobile.local.work.LocalWorkBuiltinToolRuntime
import com.labteto.dshmobile.local.work.LocalWorkMemoryRuntime
import com.labteto.dshmobile.local.work.LocalWorkModelHistoryRuntime
import com.labteto.dshmobile.local.work.LocalWorkToolResultRuntime
import com.labteto.dshmobile.local.work.LocalWorkTurnStarter
import com.labteto.dshmobile.local.work.LocalWorkTurnToolRuntime
import com.labteto.dshmobile.local.work.LocalRuntimeOwnershipPolicy
import com.labteto.dshmobile.local.work.LocalWorkExecutionControl
import com.labteto.dshmobile.local.work.LocalWorkRequestContextPolicy
import com.labteto.dshmobile.local.work.asModelAdmissionPort
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import com.labteto.dshmobile.local.work.LocalWorkSubagentRuntime
import com.labteto.dshmobile.local.work.LocalWorkflowCoordinator
import com.labteto.dshmobile.local.work.LocalWorkflowProgress
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.work.guardWorkCompletionDelivery
import com.labteto.dshmobile.local.work.localAggregateWorkStatePort
import com.labteto.dshmobile.local.work.toLocalWorkRunState
import com.labteto.dshmobile.local.work.toEnvironmentRunSnapshot
import com.labteto.dshmobile.local.work.toEnvironmentWorkBudget
import com.labteto.dshmobile.local.work.toEnvironmentWorkContextAssessment
import com.labteto.dshmobile.local.work.queueAutomaticWorkContinuation
import com.labteto.dshmobile.local.work.recordWorkCompletionQuality
import com.labteto.dshmobile.local.work.shouldAutoContinueWorkFailure
import com.labteto.dshmobile.local.work.shouldProactivelyCompactBeforeModelStep
import com.labteto.dshmobile.local.work.validateWorkspacePatchPaths
import com.labteto.dshmobile.local.work.withWorkTurnContext
import com.labteto.dshmobile.local.work.workSteadyStateHistoryBudget
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.observability.DiagnosticReport
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import com.labteto.dshmobile.runtime.PersistentPipeTerminalProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A native Android implementation of the DeepSeek Harness execution loop.
 *
 * The official Harness keeps model-visible state in a durable session log and composes capabilities
 * around an agent loop. This engine preserves those two properties while replacing Node-specific
 * providers with Android providers: an app-private filesystem, `/system/bin/sh`, OkHttp and Android
 * Keystore. Remote mode remains separate and unchanged.
 */
@Singleton
class LocalHarnessEngine @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val modelGateway: LocalModelGateway,
    private val modelConfiguration: LocalModelConfigurationCoordinator,
    private val usageTracker: DeepSeekUsageTracker,
    private val bundledRuntimeManager: LocalBundledRuntimeManager,
    private val json: Json,
    private val modelRequestCoordinator: LocalModelRequestCoordinator,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val contextComposer: ContextComposer,
    private val chatPersistence: LocalChatPersistence,
    private val chatComposition: LocalChatComposition,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val approvalPreferences: LocalApprovalPreferences,
    private val sessionStorageRuntime: LocalSessionStorageRuntime,
    private val toolCompositionRoot: LocalToolCompositionRoot,
    private val workComposition: com.labteto.dshmobile.local.work.LocalWorkComposition,
    private val foregroundWake: LocalForegroundTurnWakeCoordinator,
    private val foregroundSessionLoader: LocalForegroundSessionLoader,
) {
    private val root = File(context.filesDir, "local-harness").apply { mkdirs() }
    private val chatPersonaStore get() = chatPersistence.personaStore
    private val chatPersonaGalleryStore get() = chatPersistence.galleryStore
    private val chatDiaryStore get() = chatPersistence.diaryStore
    private val chatTurnCoordinator get() = chatComposition.turnCoordinator
    private val chatSessionLifecyclePlanner by lazy { LocalChatSessionLifecyclePlanner(chatPersonaStore) }
    private val memoryClassMb get() = runtimeStateStore.memoryClassMb
    private val workspace: LocalWorkspace
        get() = sessionStorageRuntime.files.workspace
    private val toolOutputStore get() = toolCompositionRoot.toolOutputStore
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val webTools get() = toolCompositionRoot.webTools
    private val sessionsRoot = File(root, "sessions").apply { mkdirs() }
    private val eventLogRegistry: LocalSessionEventLogRegistry
        get() = sessionStorageRuntime.eventLogs
    private val sessionCoordinator: LocalSessionCoordinator
        get() = sessionStorageRuntime.coordinator
    private val sessionAccessCoordinator by lazy {
        LocalSessionAccessCoordinator(
            summaries = ::sessionSummaries,
            currentSessionId = { currentSessionId },
            currentScope = {
                _state.value.let { current ->
                    LocalSessionAccessScope(
                        projectId = current.projectId,
                        lineageId = current.lineageId.ifBlank { current.sessionId },
                    )
                }
            },
            activeScope = { sessionId ->
                workRunRegistry.state(sessionId)?.let { active ->
                    LocalSessionAccessScope(
                        projectId = active.projectId,
                        lineageId = active.lineageId.ifBlank { active.sessionId },
                    )
                }
            },
            eventLogFor = ::eventLogFor,
        )
    }
    private val agentRunCoordinator
        get() = sessionStorageRuntime.agentRunCoordinator
    private val foregroundRecoveryCoordinator by lazy {
        LocalForegroundRecoveryCoordinator(agentRunCoordinator, modelGateway::hasCredential)
    }
    private val toolRegistry get() = toolCompositionRoot.registry
    private val toolExecutionCoordinator get() = toolCompositionRoot.execution
    private val toolSchemaProjection get() = toolCompositionRoot.schemas
    private val runtimeProcess get() = toolCompositionRoot.process
    private val pluginComposition get() = toolCompositionRoot.plugins
    private val handoffBuilder = ConversationHandoffBuilder(MAX_HANDOFF_CHARS)
    private val modelHistoryCheckpointCodec = ModelHistoryCheckpointCodec()
    private val historyCompactor = LocalHistoryCompactor()
    private val requestPressureStore
        get() = runtimeStateStore.requestPressureStore
    private val initialSessionId = preferences.getString(KEY_SESSION_ID, null)
        ?: UUID.randomUUID().toString()
    private val eventLog: LocalSessionEventLog
        get() = eventLogFor(currentSessionId)
    private val conversationFilesCoordinator
        get() = sessionStorageRuntime.files.coordinator
    private var transcriptProjectionCursor: Long?
        get() = runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor
        set(value) {
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor = value
        }
    private val modelHistory: LocalModelHistoryBuffer
        get() = runtimeStateStore.foregroundRunHandle.modelHistory
    private val _state = runtimeStateStore.initialize(
        LocalHarnessState(
            workspacePath = workspace.path,
            sessionId = initialSessionId,
            usage = usageTracker.state.value,
            chatStyleGuardEnabled = preferences.getBoolean(
                LocalHarnessSettingsCoordinator.KEY_CHAT_STYLE_GUARD,
                true,
            ),
            chatStyleGuardCustomPhrases =
                LocalHarnessSettingsCoordinator.loadChatStyleGuardCustomPhrases(preferences),
        ),
    )

    private val currentSessionId: String
        get() = runtimeStateStore.currentSessionId

    /**
     * Architecture 3.0 migration bridge kept only at the composition root.
     *
     * Work owns LocalWorkRunState; legacy Engine execution code may request an ephemeral aggregate
     * snapshot, but no Feature stores or receives a writable LocalHarnessState.
     */
    private interface LocalAggregateRunState {
        val value: LocalHarnessState
        fun update(transform: (LocalHarnessState) -> LocalHarnessState)
    }

    private val foregroundAggregateRunState = object : LocalAggregateRunState {
        override val value: LocalHarnessState
            get() = _state.value

        override fun update(transform: (LocalHarnessState) -> LocalHarnessState) {
            _state.update(transform)
        }
    }

    private fun aggregateRunState(binding: LocalWorkRunBinding?): LocalAggregateRunState =
        binding?.let { workBinding ->
            object : LocalAggregateRunState {
                override val value: LocalHarnessState
                    get() = workBinding.aggregateSnapshot()

                override fun update(transform: (LocalHarnessState) -> LocalHarnessState) {
                    workBinding.state.value = transform(workBinding.aggregateSnapshot()).toLocalWorkRunState()
                }
            }
        } ?: foregroundAggregateRunState

    private val transcriptRuntime by lazy {
        LocalTranscriptRuntime(
            state = _state,
            pruneToolResult = ::pruneToolResult,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
            onProjected = { sequence ->
                transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, sequence)
            },
        )
    }

    private val chatContextRefreshCoordinator get() = chatComposition.contextRefresh
    private val chatReplyCoordinator get() = chatComposition.replyCoordinator

    // SupervisorJob keeps one failed child from cancelling unrelated engine work. The handler is the
    // final visibility boundary; operation-specific busy/loading state is still owned by each launch.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                AppLog.error("LocalHarnessEngine", "engine coroutine failure", throwable)
                _state.update { current ->
                    current.copy(
                        error = throwable.message?.takeIf(String::isNotBlank)
                            ?: "本机 Harness 后台任务失败：${throwable::class.java.simpleName}",
                    )
                }
            },
    )
    private val resourceBudget get() = runtimeStateStore.resourceBudget
    private val resourceScheduler get() = runtimeStateStore.resourceScheduler
    private val imageCapabilities get() = runtimeStateStore.imageCapabilities
    private val imageRequestBudget =
        localImageRequestBudgetForModelConcurrency(resourceBudget.maxModelRequests)
    private fun liveWorkRun(sessionId: String): LocalWorkRunBinding? =
        workRunRegistry.live(sessionId)
    private val jobs: LocalJobManager
        get() = runtimeStateStore.jobManager

    private val memoryTools = LocalMemoryTools(memoryStore, memoryManager, { _state.value }, { currentSessionId })

    private fun memoryTools(binding: LocalWorkRunBinding?): LocalMemoryTools =
        binding?.let { runBinding ->
            LocalMemoryTools(
                memoryStore,
                memoryManager,
                { runBinding.aggregateSnapshot() },
                { runBinding.sessionId },
            )
        } ?: memoryTools

    private val subagentRunnerFactory by lazy {
        LocalSubagentRunnerFactory(
            modelGateway = modelGateway,
            jobs = jobs,
            modelHistory = modelHistory,
            toolOutputStore = toolOutputStore,
            workspacePath = workspace.path,
            imageRequestBudget = imageRequestBudget,
            imageCapabilities = imageCapabilities,
            usageTracker = usageTracker,
            resourceScheduler = resourceScheduler,
            virtualDisplayProvider = pluginComposition.virtualDisplayProvider,
            memoryClassMb = memoryClassMb,
            agentRunCoordinator = agentRunCoordinator,
            contextComposer = contextComposer,
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            eventLogFor = ::eventLogFor,
            defaultState = runtimeStateStore.state,
            defaultSessionId = { currentSessionId },
        )
    }

    private val subagents by lazy {
        subagentRunnerFactory.create(
            eventLogProvider = { eventLog },
            contextSnapshotProvider = { query ->
                val snapshot = _state.value
                contextComposer.compose(
                    ContextRequest(
                        query = query,
                        mode = snapshot.conversationMode,
                        projectId = snapshot.projectId,
                        lineageId = snapshot.lineageId,
                        handoffSummary = snapshot.handoffSummary,
                    ),
                )
            },
            schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
                toolSchemaProjection.subagentSchemas(
                    allowMutation = allowMutation,
                    allowVirtualScreen = allowVirtualScreen,
                    enabledOptional = enabledOptional,
                )
            },
            executeTool = { call, allowMutation, enabledOptional ->
                val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
                if (canonical.name == "capability_search") {
                    AgentToolResult(
                        searchCapabilities(canonical.arguments.string("query"), enabledOptional),
                    )
                } else {
                    executeSafely(canonical, allowMutation)
                }
            },
        )
    }

    private fun persistentSubagentRunner(
        sessionId: String,
        boundState: LocalHarnessState,
        historySnapshot: () -> List<JsonObject> = modelHistory::snapshot,
    ): LocalSubagentRunner = subagentRunnerFactory.createBound(
        sessionId = sessionId,
        boundState = boundState,
        runKind = LocalAgentRunKind.SUBAGENT,
        schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
            toolSchemaProjection.subagentSchemas(
                allowMutation = allowMutation,
                allowVirtualScreen = allowVirtualScreen,
                enabledOptional = enabledOptional,
            )
        },
        executeTool = { call, allowMutation, memoryTools, enabledOptional ->
            executePersistentSubagentTool(
                call = call,
                allowMutation = allowMutation,
                sessionId = sessionId,
                memoryTools = memoryTools,
                enabledOptionalTools = enabledOptional,
            )
        },
        historySnapshot = historySnapshot,
        modelAdmission = (workRunRegistry[sessionId]?.executionControl ?: LocalWorkExecutionControl()).asModelAdmissionPort(),
    )
    /**
     * Detached work runner for scheduled/webhook work.
     *
     * It owns a Work-mode session log but never swaps the UI's current session. Interactive
     * approvals are deliberately unavailable: when global auto-approval is enabled, every
     * approval-gated tool may proceed; otherwise the task is marked blocked so a background task
     * cannot surface a work prompt inside Chat mode.
     */

    private val runStateLock: Any
        get() = runtimeStateStore.foregroundRunHandle.lock
    private val pendingInputs: AgentInputQueue
        get() = runtimeStateStore.foregroundRunHandle.pendingInputs
    private val sessionTransitionMutex = Mutex()
    private val sessionTransitioning: Boolean
        get() = runtimeStateStore.sessionTransitioning
    private var activeJob: Job?
        get() = runtimeStateStore.foregroundRunHandle.job
        set(value) {
            runtimeStateStore.foregroundRunHandle.job = value
        }


    init {
        preferences.edit().putString(KEY_SESSION_ID, currentSessionId).apply()
        val initialResources = resourceScheduler.snapshot()
        _state.update {
            it.copy(
                kernel = it.kernel.copy(
                    resources = initialResources.toLocalHarnessResourceState(it.usageMode),
                    contextBudgetChars = localHistoryBudgetFor(memoryClassMb, initialResources.pressure).maxHistoryChars,
                ),
            )
        }
        scope.launch {
            usageTracker.state.collect { usage ->
                _state.update { it.copy(usage = usage) }
            }
        }
        scope.launch {
            runCatching {
                seedLocalWorkspaceGuide(workspace.path)
                migrateLegacySessionFiles(root, sessionsRoot, currentSessionId)
                prepareLocalHarnessStartup(
                    prepareRuntime = bundledRuntimeManager::prepare,
                    installPlugins = { pluginComposition.installStartup() },
                    restoreSession = { foregroundSessionLoader.loadStartup(deferReady = true) },
                )
                _state.update { current ->
                    if (current.loading) current.copy(loading = false) else current
                }
                foregroundWake.startNextIfIdle()?.start()
                workComposition.schedulePersistentRecovery()
                scope.launch { maybeCleanupUnreferencedLocalImages() }
                scope.launch { LocalSessionArchiveMaintenance(sessionsRoot, json, { currentSessionId }).run() }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = "本机 Harness 初始化失败：${error.message ?: error::class.java.simpleName}",
                    )
                }
            }
        }
    }

    private fun recordStyleGuardHits(violations: List<String>) {
        if (violations.isEmpty()) return
        _state.update { current ->
            current.copy(
                styleGuardHits = (current.styleGuardHits + violations)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeLast(LocalHarnessSettingsCoordinator.MAX_STYLE_GUARD_HITS),
            )
        }
    }


    private fun appendUserToModelHistory(
        message: JsonObject,
        binding: LocalWorkRunBinding? = null,
    ) {
        (binding?.runHandle?.modelHistory ?: modelHistory).append(message)
        updateContextMetrics(binding)
    }

    internal suspend fun diagnoseNetwork(target: String): String = webTools.diagnose(target)

    private fun refreshGroupModelSystemPrompt() {
        val system = buildJsonObject {
            put("role", "system")
            put("content", groupChatSystemPrompt())
        }
        if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            modelHistory.replaceSystem(system)
        } else {
            modelHistory.prepend(system)
        }
        updateContextMetrics()
    }

    private fun AgentToolCall.toLocalToolCall(): LocalToolCall = LocalToolCall(
        id = id,
        name = name,
        arguments = arguments,
        rawArguments = rawArguments,
    )

    private suspend fun executeSafely(
        call: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding? = null,
    ): AgentToolResult = try {
        executeRegistered(call, allowMutation, binding)
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        toolFailureResult(call, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消；同批其他任务继续运行")
    } catch (error: LocalWebException) {
        toolFailureResult(call, error.code, error.message ?: "网页工具失败")
    } catch (error: LocalModelException) {
        toolFailureResult(call, error.code, error.message ?: "模型请求失败")
    } catch (error: Exception) {
        toolFailureResult(call, "TOOL_ERROR", error.message ?: error::class.java.simpleName)
    }

    private suspend fun executePersistentSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        return try {
            when (canonical.name) {
                "capability_search" -> AgentToolResult(
                    searchCapabilities(canonical.arguments.string("query"), enabledOptionalTools),
                )
                "memory_search", "memory_list" -> AgentToolResult(
                    memoryTools.execute(canonical.name, canonical.arguments, allowMutation = false),
                )
                "tool_output_read" -> AgentToolResult(
                    toolOutputStore.read(
                        sessionId = sessionId,
                        callId = canonical.arguments.string("call_id"),
                        startByte = canonical.arguments.int("start_byte", 0),
                        maxBytes = canonical.arguments.int(
                            "max_bytes",
                            LocalToolOutputStore.DEFAULT_READ_BYTES,
                        ),
                    ),
                )
                else -> executePersistentRegistered(canonical, allowMutation, sessionId)
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            toolFailureResult(canonical, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消")
        } catch (error: LocalWebException) {
            toolFailureResult(canonical, error.code, error.message ?: "网页工具失败")
        } catch (error: LocalModelException) {
            toolFailureResult(canonical, error.code, error.message ?: "模型请求失败")
        } catch (error: Exception) {
            toolFailureResult(canonical, "TOOL_ERROR", error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun executePersistentRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
    ): AgentToolResult = toolExecutionCoordinator.executeScoped(
        original = original,
        sessionId = sessionId,
        allowMutation = allowMutation,
        planModeEnabled = false,
        approval = { call, tool, summary ->
            if (sessionId == currentSessionId) {
                approve(call, summary, tool)
            } else {
                approvalPreferences.isSafeAutoApprovalEnabled()
            }
        },
    )


    private fun formatToolFailure(call: LocalToolCall, code: String, detail: String): String =
        "[${call.name}][$code] 工具执行失败：$detail\n调用 id：${call.id}"

    private fun toolFailureResult(call: LocalToolCall, code: String, detail: String): AgentToolResult {
        val retryable = code in setOf(
            "MODEL_TIMEOUT",
            "MODEL_NETWORK",
            "TASK_CANCELLED",
            "PARALLEL_TASK_ERROR",
        ) || code.startsWith("MODEL_HTTP_5") || code.contains("TIMEOUT") || code.contains("NETWORK")
        val access = toolRegistry.get(LocalToolPolicy.canonical(call.name))?.access
        val sideEffect = if (
            access in setOf(
                ToolAccess.WORKSPACE_WRITE,
                ToolAccess.SESSION_WRITE,
                ToolAccess.PROCESS,
                ToolAccess.AGENT_CONTROL,
                ToolAccess.DEVICE,
                ToolAccess.PRIVILEGED,
            )
        ) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE
        val recoveryHint = when {
            sideEffect == AgentToolSideEffect.POSSIBLE ->
                "该调用可能已产生部分副作用；先检查当前状态，再决定是否重试。"
            retryable ->
                "该错误允许重试；网络类错误可先运行 network_diagnose。"
            else ->
                "检查参数、权限或前置状态后再选择其他方案。"
        }
        return AgentToolResult(
            content = formatToolFailure(call, code, detail),
            isError = true,
            errorCode = code,
            retryable = retryable,
            sideEffect = sideEffect,
            recoveryHint = recoveryHint,
        )
    }

    private suspend fun executeRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding? = null,
    ): AgentToolResult = if (binding == null) {
        toolExecutionCoordinator.execute(original, allowMutation)
    } else {
        toolExecutionCoordinator.executeScoped(
            original = original,
            sessionId = binding.sessionId,
            allowMutation = allowMutation,
            planModeEnabled = binding.state.value.work.planMode,
            approval = { call, tool, summary ->
                approve(binding, call, summary, tool)
            },
        )
    }

    private fun searchCapabilities(query: String): String =
        toolExecutionCoordinator.searchCapabilities(query)

    private fun searchCapabilities(query: String, target: MutableSet<String>): String =
        toolExecutionCoordinator.searchCapabilities(query, target)

    private suspend fun approve(
        call: LocalToolCall,
        summary: String,
        tool: HarnessTool,
    ): Boolean {
        if (runtimeStateStore.foregroundInteractions.deviceApprovalLeaseEnabled() && canUseDeviceApprovalLease(tool)) {
            eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("mode", "device-turn-lease")
            })
            return true
        }
        if (approvalPreferences.isSafeAutoApprovalEnabled()) {
            eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("impact", approvalImpact(tool).name.lowercase())
                put("mode", "global")
            })
            return true
        }
        return runtimeStateStore.foregroundInteractions.awaitApproval(
            LocalApproval(
                callId = call.id,
                toolName = call.name,
                summary = summary,
                arguments = call.rawArguments,
                access = tool.access.name.lowercase(),
                impact = approvalImpact(tool),
                canAutoApproveSafely = canAutoApproveSafely(tool),
                canApproveDeviceTurn = canUseDeviceApprovalLease(tool),
            ),
        )
    }

    private suspend fun approve(
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        summary: String,
        tool: HarnessTool,
    ): Boolean {
        if (binding.interactions.deviceApprovalLeaseEnabled() && canUseDeviceApprovalLease(tool)) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("mode", "device-turn-lease")
            })
            return true
        }
        if (approvalPreferences.isSafeAutoApprovalEnabled()) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("impact", approvalImpact(tool).name.lowercase())
                put("mode", "global")
            })
            return true
        }
        return binding.interactions.awaitApproval(
            LocalApproval(
                callId = call.id,
                toolName = call.name,
                summary = summary,
                arguments = call.rawArguments,
                access = tool.access.name.lowercase(),
                impact = approvalImpact(tool),
                canAutoApproveSafely = canAutoApproveSafely(tool),
                canApproveDeviceTurn = canUseDeviceApprovalLease(tool),
            ),
        )
    }

    private fun cancelChatPostTurn() {
        chatContextRefreshCoordinator.cancelScheduledRefresh()
    }

    private suspend fun modelRequestMarkerOrNull(): String? {
        val snapshot = _state.value
        val profileId = snapshot.modelState.modelSelection.activeProfileId ?: return null
        return modelGateway.profileForRoute(
            profileId = profileId,
            model = snapshot.modelState.model,
            baseUrl = snapshot.modelState.baseUrl,
        ).id
    }

    private suspend fun completeWithRetry(
        key: String,
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        toolsOverride: JsonArray? = null,
        publishPreview: Boolean = true,
        maxAttemptsOverride: Int? = null,
        allowContextOverflowRecovery: Boolean = true,
        persistOverflowHistory: Boolean = false,
        streamFilterPhrases: List<String> = emptyList(),
        requestLog: LocalSessionEventLog? = null,
        temperature: Double? = null, profile: LocalModelProfile? = null,
        binding: LocalWorkRunBinding? = null,
    ): LocalModelReply = modelRequestCoordinator.complete(
        snapshot = snapshot,
        messages = messages,
        step = step,
        toolsOverride = toolsOverride,
        publishPreviewEnabled = publishPreview,
        maxAttemptsOverride = maxAttemptsOverride,
        allowContextOverflowRecovery = allowContextOverflowRecovery,
        persistOverflowHistory = persistOverflowHistory,
        streamFilterPhrases = streamFilterPhrases,
        requestLog = requestLog ?: binding?.eventLog,
        temperature = temperature,
        profile = profile,
        previewGuard = {
            currentSessionId == snapshot.sessionId &&
                _state.value.sessionId == snapshot.sessionId
        },
        overflowPersister = binding?.let { runBinding ->
            { snapshot, mode -> persistOverflowCompaction(snapshot, mode, runBinding) }
        },
        contextPolicy = binding?.let { LocalWorkRequestContextPolicy },
        admission = binding?.executionControl?.asModelAdmissionPort(),
    )

    private fun persistOverflowCompaction(
        snapshot: LocalHarnessState,
        summaryMode: LocalHistorySummaryMode,
        binding: LocalWorkRunBinding?,
    ) {
        if (binding != null && snapshot.sessionId != binding.sessionId) return
        val history = binding?.runHandle?.modelHistory ?: modelHistory
        val log = binding?.eventLog ?: eventLog
        val compaction = history.compactOverflow(
            compactor = historyCompactor,
            summaryMode = summaryMode,
        ) ?: return
        requestPressureStore.advanceGeneration(snapshot.sessionId, compaction.estimatedTokensAfter)
        log.append("session/compaction", buildJsonObject {
            put("trigger", "context-overflow")
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
        })
        checkpointModelHistory("session/context-overflow", binding)
        updateContextMetrics(binding)
        persist(binding)
    }

    private fun currentHistoryBudget(binding: LocalWorkRunBinding? = null): LocalHistoryBudget {
        val snapshot = binding?.aggregateSnapshot() ?: _state.value
        return localHistoryBudgetFor(
            memoryClassMb = memoryClassMb,
            pressure = resourceScheduler.snapshot().pressure,
            model = snapshot.modelState.model,
            baseUrl = snapshot.modelState.baseUrl,
            contextWindowTokensOverride = snapshot.modelState.modelSelection.activeProfile?.contextWindowTokensOverride,
        )
    }

    private fun updateContextMetrics(binding: LocalWorkRunBinding? = null) {
        val budget = currentHistoryBudget(binding)
        val history = binding?.runHandle?.modelHistory ?: modelHistory
        val targetState = aggregateRunState(binding)
        targetState.update {
            it.copy(
                kernel = it.kernel.copy(
                    contextChars = history.encodedChars,
                    contextBudgetChars = budget.maxHistoryChars,
                ),
            )
        }
    }

    private fun pruneToolResult(result: String): String =
        retainToolResult(
            sessionId = currentSessionId,
            callId = null,
            result = result,
        )

    private fun retainToolResult(
        sessionId: String,
        callId: String?,
        result: String,
        binding: LocalWorkRunBinding? = null,
        retention: com.labteto.dshmobile.harness.tools.ToolResultRetention =
            com.labteto.dshmobile.harness.tools.ToolResultRetention.DURABLE,
    ): String {
        val history = binding?.runHandle?.modelHistory ?: modelHistory
        val budget = adaptiveToolResultBudget(
            base = currentHistoryBudget(binding),
            currentHistoryChars = history.encodedChars,
            currentHistoryTokens = history.estimatedTokens,
        )
        return projectRecoverableToolResult(
            value = result,
            retention = retention,
            usageMode = if (binding != null) LocalUsageMode.WORK else _state.value.usageMode,
            budget = budget,
            callId = callId,
            spill = { id, value -> toolOutputStore.store(sessionId, id, value) != null },
        ).text
    }

    private fun systemPrompt(binding: LocalWorkRunBinding? = null): String {
        val snapshot = binding?.aggregateSnapshot() ?: _state.value
        return when {
            snapshot.usageMode != LocalUsageMode.CHAT ->
                workSystemPrompt(workspace.path, snapshot.work.planMode)
            snapshot.chat.groupChat.enabled -> groupChatSystemPrompt()
            else -> chatSystemPrompt()
        }
    }

    /**
     * Restore model history from the newest valid checkpoint tail whenever possible.
     *
     * New sessions checkpoint model-visible history regularly. Reading the entire event archive
     * here would undo the projection-cursor startup optimization for long-lived sessions. Legacy
     * history still provides the one-time fallback when no valid checkpoint exists.
     */
    private fun maybeCleanupUnreferencedLocalImages() {
        val now = System.currentTimeMillis()
        val last = preferences.getLong(KEY_ATTACHMENT_GC_AT, 0L)
        if (now - last < ATTACHMENT_GC_INTERVAL_MILLIS) return
        runCatching { cleanupUnreferencedLocalImagesNow(sessionsRoot, currentSessionId, ::eventLogFor, modelHistory.snapshot(), workspace.path, eventLog) }
            .onSuccess {
                preferences.edit().putLong(KEY_ATTACHMENT_GC_AT, now).apply()
            }
    }

    private fun checkpointModelHistory(
        reason: String,
        binding: LocalWorkRunBinding? = null,
    ) {
        val log = binding?.eventLog ?: eventLog
        val history = binding?.runHandle?.modelHistory ?: modelHistory
        log.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            modelHistoryCheckpointCodec.encode(durableModelHistorySnapshot(history.snapshot()), reason),
        )
        if (binding != null) {
            binding.runHandle.turnsSinceModelHistoryCheckpoint = 0
        } else {
            runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint = 0
        }
    }

    private fun persistenceSnapshot(binding: LocalWorkRunBinding? = null): LocalHarnessSession =
        binding?.persistenceSnapshot() ?: localSessionPersistenceSnapshot(
            sessionCoordinator = sessionCoordinator,
            currentSessionId = currentSessionId,
            currentState = { _state.value },
            eventLog = eventLog,
            transcriptProjectionCursor = transcriptProjectionCursor,
        )

    private fun persist(binding: LocalWorkRunBinding? = null) {
        sessionCoordinator.enqueue(persistenceSnapshot(binding))
    }

    private fun eventLogFor(id: String) = eventLogRegistry.get(id)

    private fun sessionSummaries(): List<LocalSessionSummary> =
        localSessionSummariesOrEmpty(sessionCoordinator) { future ->
            _state.update { it.copy(error = future.message) }
        }
}
