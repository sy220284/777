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
import com.labteto.dshmobile.local.automation.LocalAutomationChatCoordinator
import com.labteto.dshmobile.local.automation.LocalAutomationRunResult
import com.labteto.dshmobile.local.automation.LocalAutomationWorkCoordinator
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatPersonaCorrectionNotice
import com.labteto.dshmobile.local.chat.ChatStyleGuard
import com.labteto.dshmobile.local.chat.LocalCharacterBehaviorTuningCoordinator
import com.labteto.dshmobile.local.chat.LocalChatBranchCoordinator
import com.labteto.dshmobile.local.chat.LocalChatBranchNode
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalChatDirectTurnExecutor
import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalChatMemoryRuntime
import com.labteto.dshmobile.local.chat.LocalChatPersistence
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.chat.LocalChatStatePort
import com.labteto.dshmobile.local.chat.LocalChatPersonaCorrectionCoordinator
import com.labteto.dshmobile.local.chat.LocalChatRelationshipHydrator
import com.labteto.dshmobile.local.chat.LocalChatTurnDispatcher
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
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalPersistentJobRecoveryCoordinator
import com.labteto.dshmobile.local.lsp.parseLanguageServerCommand
import com.labteto.dshmobile.local.memory.LocalMemoryCoordinator
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
import com.labteto.dshmobile.local.runtime.structuredWorkState
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
import com.labteto.dshmobile.local.settings.LocalAgentRuntimeLimits
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
import com.labteto.dshmobile.local.work.LocalWorkTurnStarter
import com.labteto.dshmobile.local.work.LocalWorkTurnHistoryRuntime
import com.labteto.dshmobile.local.work.LocalRuntimeOwnershipPolicy
import com.labteto.dshmobile.local.work.LocalWorkExecutionControl
import com.labteto.dshmobile.local.work.LocalWorkRequestContextPolicy
import com.labteto.dshmobile.local.work.asModelAdmissionPort
import com.labteto.dshmobile.local.work.LocalWorkProgressCoordinator
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import com.labteto.dshmobile.local.work.LocalWorkReplyRegenerator
import com.labteto.dshmobile.local.work.LocalWorkSubagentRuntime
import com.labteto.dshmobile.local.work.LocalWorkerModelRouter
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
import com.labteto.dshmobile.local.work.exitWorkPlanMode
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
    private val pluginCompositionFactory: LocalPluginCompositionFactory,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val contextComposer: ContextComposer,
    private val chatPersistence: LocalChatPersistence,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val approvalPreferences: LocalApprovalPreferences,
    private val sessionStorageRuntime: LocalSessionStorageRuntime,
) {
    private val root = File(context.filesDir, "local-harness").apply { mkdirs() }
    private val chatPersonaStore get() = chatPersistence.personaStore
    private val chatPersonaGalleryStore get() = chatPersistence.galleryStore
    private val chatDiaryStore get() = chatPersistence.diaryStore
    private val memoryClassMb get() = runtimeStateStore.memoryClassMb
    private val workspace: LocalWorkspace
        get() = sessionStorageRuntime.files.workspace
    private val fileInspector = LocalFileInspector(File(workspace.path))
    private val toolOutputStore = LocalToolOutputStore(
        File(context.noBackupFilesDir, "local-harness/tool-output"),
    )
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val webTools = pluginCompositionFactory.createWebTools(
        searchKeyProvider = modelConfiguration::resolveDeepSeekSearchCredential,
        workspace = workspace,
    )
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
    private val toolRegistry
        get() = pluginComposition.tools
    private val toolExecutionCoordinator by lazy {
        LocalToolExecutionCoordinator(
            registry = toolRegistry,
            currentSessionId = { currentSessionId },
            planMode = { _state.value.work.planMode },
            requestApproval = { call, tool, summary -> approve(call, summary, tool) },
            recordExecutionStarted = { sessionId, call ->
                eventLogFor(sessionId).append("tool/execution-started", buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                })
            },
        )
    }
    private val toolSchemaProjection by lazy {
        LocalToolSchemaProjection(toolRegistry, toolExecutionCoordinator)
    }
    private val runtimeProcess = AndroidProcessRuntime(
        defaultWorkingDirectory = File(workspace.path),
        dynamicSearchPaths = bundledRuntimeManager::searchPaths,
        baseEnvironment = bundledRuntimeManager::environment,
    )
    private val automaticLanguageServerResolver = AutomaticLanguageServerResolver(
        root = File(workspace.path),
        commandAvailable = runtimeProcess::isCommandAvailable,
        legacyCommand = {
            parseLanguageServerCommand(
                preferences.getString("language_server_command", "").orEmpty(),
            )
        },
    )
    private val runtimeTerminal = PersistentPipeTerminalProvider(
        defaultWorkingDirectory = File(workspace.path),
        extraSearchPaths = bundledRuntimeManager::searchPaths,
        baseEnvironment = bundledRuntimeManager::environment,
    )
    private val handoffBuilder = ConversationHandoffBuilder(MAX_HANDOFF_CHARS)
    private val modelHistoryCheckpointCodec = ModelHistoryCheckpointCodec()
    private val historyCompactor = LocalHistoryCompactor()
    private val requestPressureStore
        get() = runtimeStateStore.requestPressureStore
    private val environmentInfoCoordinator by lazy {
        LocalEnvironmentInfoCoordinator(
            workspacePath = { workspace.path },
            resourceSnapshot = resourceScheduler::snapshot,
            foregroundHistoryBudgetChars = { currentHistoryBudget().maxHistoryChars },
            requestPressureStore = requestPressureStore,
            usageTracker = usageTracker,
            toolExecutionCoordinator = toolExecutionCoordinator,
            commandAvailable = runtimeProcess::isCommandAvailable,
            runtimeStatuses = bundledRuntimeManager::statuses,
            diagnostics = AppLog::snapshot,
            storageStatus = { LocalSessionArchiveMaintenance.storageStatus(sessionsRoot) },
            processExitStatus = { LocalProcessExitStatus.read(context) },
            foregroundSessionId = { currentSessionId },
            foregroundHistory = { modelHistory },
            foregroundPendingInputs = { pendingInputs.size() },
            pendingInputLimit = MAX_PENDING_INPUTS,
            workContextAssessment = { sessionId ->
                requestPressureStore.workAssessment(sessionId)?.toEnvironmentWorkContextAssessment()
            },
            foregroundWorkBudget = { sessionId ->
                workRunRegistry[sessionId]?.executionControl?.budget?.snapshot()?.toEnvironmentWorkBudget()
            },
        )
    }
    private val tokenUsageBridge by lazy { LocalTokenUsageContextBridge(_state, workRunRegistry, ::eventLogFor) { currentSessionId } }
    private val pluginComposition by lazy {
        pluginCompositionFactory.create(
            workspaceRoot = File(workspace.path),
            runtimeProcess = runtimeProcess,
            runtimeTerminal = runtimeTerminal,
            languageServerCommand = automaticLanguageServerResolver::resolve,
            resourceScheduler = resourceScheduler,
            routeProvider = {
                val current = _state.value
                current.modelState.modelSelection.activeProfile?.takeIf { current.modelState.configured }
                    ?.let { LocalVisionRoute(it.baseUrl, it.model, profile = it) }
            },
            imageSupportProvider = { route ->
                when (imageCapabilities.state(route.baseUrl, route.model)) {
                    LocalImageCapability.SUPPORTED -> true
                    LocalImageCapability.UNSUPPORTED -> false
                    LocalImageCapability.UNKNOWN -> null
                }
            },
            executeBuiltin = ::executeBuiltin,
            usageContextProvider = { sessionId, callId -> tokenUsageBridge.resolve(sessionId, callId, TokenUsageAction.VISION) },
        )
    }
    private val initialSessionId = preferences.getString(KEY_SESSION_ID, null)
        ?: UUID.randomUUID().toString()
    // Opening the log scans its latest segment. The startup coroutine initializes it after any
    // legacy migration, before the loading screen admits session actions.
    @Volatile private lateinit var eventLog: LocalSessionEventLog
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

    private val engineChatStatePort by lazy { LocalChatStatePort(_state) }
    private val engineChatPersonaCorrections by lazy {
        LocalChatPersonaCorrectionCoordinator(
            chatState = engineChatStatePort,
            personaStore = chatPersonaStore,
            eventLogs = eventLogRegistry,
            sessionStorage = sessionStorageRuntime,
        )
    }
    private val engineChatRelationshipHydrator by lazy {
        LocalChatRelationshipHydrator(
            chatState = engineChatStatePort,
            memoryStore = memoryStore,
            eventLogs = eventLogRegistry,
            sessionStorage = sessionStorageRuntime,
        )
    }
    private val engineChatMemoryRuntime by lazy {
        LocalChatMemoryRuntime(
            runtimeStateStore = runtimeStateStore,
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            persistence = chatPersistence,
            sessionStorage = sessionStorageRuntime,
        )
    }

    private val chatTurnDispatcher by lazy {
        LocalChatTurnDispatcher(
            context = context,
            runtimeStateStore = runtimeStateStore,
            personaCorrections = engineChatPersonaCorrections,
            relationshipHydrator = engineChatRelationshipHydrator,
            groupExecutor = groupChatTurnExecutor,
            directTurn = { input, sourceMessageId ->
                engineChatDirectTurnExecutor.run(
                    input = input,
                    sourceMessageId = sourceMessageId,
                )
            },
        )
    }

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

    private val engineForegroundModelHistoryRuntime by lazy {
        LocalForegroundModelHistoryRuntime(
            runtimeStateStore = runtimeStateStore,
            sessionStorage = sessionStorageRuntime,
        )
    }

    private val engineChatBranchCoordinator by lazy {
        LocalChatBranchCoordinator(
            runtimeStateStore = runtimeStateStore,
            chatState = engineChatStatePort,
            sessionStorage = sessionStorageRuntime,
            modelHistoryRuntime = engineForegroundModelHistoryRuntime,
        )
    }

    private val chatContextRefreshCoordinator by lazy {
        LocalChatContextRefreshCoordinator(
            runtimeStateStore = runtimeStateStore,
            chatState = engineChatStatePort,
            chatTurnCoordinator = chatTurnCoordinator,
            persistence = chatPersistence,
            modelRequests = modelRequestCoordinator,
            usageTracker = usageTracker,
            sessionStorage = sessionStorageRuntime,
            branchCoordinator = engineChatBranchCoordinator,
        )
    }

    private val chatReplyCoordinator by lazy {
        LocalChatReplyCoordinator(
            chatTurnCoordinator = chatTurnCoordinator,
            usageTracker = usageTracker,
            runtimeStateStore = runtimeStateStore,
        )
    }

    private val engineChatDirectTurnExecutor by lazy {
        LocalChatDirectTurnExecutor(
            runtimeStateStore = runtimeStateStore,
            chatState = engineChatStatePort,
            sessionStorage = sessionStorageRuntime,
            modelHistory = engineForegroundModelHistoryRuntime,
            modelRequests = modelRequestCoordinator,
            chatTurnCoordinator = chatTurnCoordinator,
            chatMemory = engineChatMemoryRuntime,
            replyCoordinator = chatReplyCoordinator,
            branchCoordinator = engineChatBranchCoordinator,
            postTurn = chatContextRefreshCoordinator,
        )
    }

    private val groupChatTurnExecutor by lazy {
        LocalGroupChatTurnExecutor(
            _state, modelGateway, chatPersonaStore, chatPersonaGalleryStore, chatReplyCoordinator,
            chatTurnCoordinator, chatDiaryStore, usageTracker, json, modelHistory, transcriptRuntime,
            imageCapabilities, imageRequestBudget, workspace.path, { eventLog },
            { key, snapshot, messages, step, tools, preview, attempts, overflow, temperature ->
                completeWithRetry(
                    key = key, snapshot = snapshot, messages = messages, step = step,
                    toolsOverride = tools, publishPreview = preview, maxAttemptsOverride = attempts,
                    allowContextOverflowRecovery = overflow, temperature = temperature,
                )
            },
            ::ensureSystemMessage,
            { text, sourceMessageId -> captureAutoMemoryDirective(text, sourceMessageId) },
            { query, snapshot, subjectKey, viewerName ->
                memoryCoordinator.chatMemoryContext(
                    query = query,
                    snapshot = snapshot,
                    viewerSubjectKey = subjectKey,
                    viewerName = viewerName,
                    groupAudience = true,
                )
            },
            { extraTokens -> compactHistoryIfNeeded(extraTokens) },
            ::updateContextMetrics,
            ::persistChatBranchState,
            ::checkpointModelHistory,
            ::persist,
            ::persistNow,
            { completedJob ->
                synchronized(runStateLock) { if (activeJob === completedJob) activeJob = null }
                startNextQueuedTurnIfIdle()?.start()
            },
        )
    }

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
    private val workReplyRegenerator by lazy {
        LocalWorkReplyRegenerator(
            runtimeStateStore = runtimeStateStore,
            sessionStorage = sessionStorageRuntime,
            modelRequests = modelRequestCoordinator,
            usageTracker = usageTracker,
        )
    }

    private val workTurnHistoryRuntime by lazy {
        LocalWorkTurnHistoryRuntime(
            runtimeStateStore = runtimeStateStore,
            sessionStorage = sessionStorageRuntime,
            workspacePath = workspace.path,
            toolOutputStore = toolOutputStore,
        )
    }

    private val workTurnStarter by lazy {
        LocalWorkTurnStarter(
            runtimeStateStore = runtimeStateStore,
            sessionStorage = sessionStorageRuntime,
            workRunRegistry = workRunRegistry,
            pruneToolResult = ::pruneToolResult,
            runTurn = { input, memoryInput, sourceMessageId, binding, preownedLease ->
                runWorkAgentTurn(
                    input = input,
                    memoryInput = memoryInput,
                    sourceMessageId = sourceMessageId,
                    binding = binding,
                    preownedLease = preownedLease,
                )
            },
        )
    }

    private val workSubagentRuntime by lazy {
        LocalWorkSubagentRuntime(
            factory = subagentRunnerFactory,
            schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
                toolSchemaProjection.subagentSchemas(
                    allowMutation = allowMutation,
                    allowVirtualScreen = allowVirtualScreen,
                    enabledOptional = enabledOptional,
                )
            },
            executeTool = { binding, call, allowMutation, boundMemoryTools, enabledOptional ->
                executePersistentSubagentTool(
                    call = call,
                    allowMutation = allowMutation,
                    sessionId = binding.sessionId,
                    memoryTools = boundMemoryTools,
                    enabledOptionalTools = enabledOptional,
                )
            },
            pruneOutput = ::pruneToolResult,
        )
    }

    private val persistentJobRecoveryCoordinator by lazy {
        LocalPersistentJobRecoveryCoordinator(
            scope = scope,
            jobs = jobs,
            json = json,
            modelGateway = modelGateway,
            webTools = webTools,
            currentSessionId = { currentSessionId },
            currentState = { _state.value },
            defaultHistory = modelHistory::snapshot,
            subagentRunner = { sessionId, boundState, history ->
                persistentSubagentRunner(sessionId, boundState, history)
            },
            eventLogFor = ::eventLogFor,
        )
    }

    internal val automationWorkCoordinator by lazy {
        LocalAutomationWorkCoordinator(
            state = runtimeStateStore.state,
            sessionCoordinator = sessionCoordinator,
            eventLogFor = ::eventLogFor,
            agentRunCoordinator = agentRunCoordinator,
            runnerFactory = { sessionId, boundState, onApprovalBlocked ->
                automationSubagentRunner(
                    sessionId = sessionId,
                    boundState = boundState,
                    onApprovalBlocked = onApprovalBlocked,
                )
            },
            onSessionReleased = { sessionId ->
                if (currentSessionId == sessionId && _state.value.sessionId == sessionId) startNextQueuedTurnIfIdle()?.start()
            },
        )
    }

    internal val automationChatCoordinator by lazy {
        LocalAutomationChatCoordinator(
            state = runtimeStateStore.state,
            sessionCoordinator = sessionCoordinator,
            eventLogFor = ::eventLogFor,
            chatPersonaStore = chatPersonaStore,
            chatTurnCoordinator = chatTurnCoordinator,
            chatReplyCoordinator = chatReplyCoordinator,
            usageTracker = usageTracker,
            modelGateway = modelGateway,
            modelRequestCoordinator = modelRequestCoordinator,
            chatRelationshipMemoryContext = ::chatRelationshipMemoryContext,
            recordStyleGuardHits = ::recordStyleGuardHits,
            acquireVisibleTurn = ::acquireAutomationChatVisibleTurn,
            commitVisibleReply = ::commitVisibleAutomationChatReply,
            releaseVisibleTurn = ::releaseAutomationChatVisibleTurn,
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
    private fun automationSubagentRunner(
        sessionId: String,
        boundState: LocalHarnessState,
        onApprovalBlocked: (String) -> Unit,
    ): LocalSubagentRunner = subagentRunnerFactory.createBound(
        sessionId = sessionId,
        boundState = boundState,
        runKind = LocalAgentRunKind.AUTOMATION,
        schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
            toolSchemaProjection.subagentSchemas(
                allowMutation = allowMutation,
                allowVirtualScreen = allowVirtualScreen,
                enabledOptional = enabledOptional,
            )
        },
        executeTool = { call, allowMutation, memoryTools, enabledOptional ->
            executeAutomationSubagentTool(
                call = call,
                allowMutation = allowMutation,
                sessionId = sessionId,
                memoryTools = memoryTools,
                enabledOptionalTools = enabledOptional,
                onApprovalBlocked = onApprovalBlocked,
            )
        },
        modelAdmission = LocalWorkExecutionControl().asModelAdmissionPort(),
    )

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
    private val memoryCoordinator by lazy {
        LocalMemoryCoordinator(
            state = _state,
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            diaryStore = chatDiaryStore,
            currentSessionId = { currentSessionId },
            eventLog = { eventLog },
            persist = ::persist,
        )
    }
    private val sessionLifecycle by lazy {
        LocalSessionLifecycleCoordinator(
            scope = scope,
            state = _state,
            transitionMutex = sessionTransitionMutex,
            jobs = jobs,
            sessionCoordinator = sessionCoordinator,
            chatPersonaStore = chatPersonaStore,
            approvalPreferences = approvalPreferences,
            resourceScheduler = resourceScheduler,
            handoffBuilder = handoffBuilder,
            toolOutputStore = toolOutputStore,
            sessionsRoot = sessionsRoot,
            conversationFilesCoordinator = conversationFilesCoordinator,
            memoryStore = memoryStore,
            diaryStore = chatDiaryStore,
            currentSessionId = { currentSessionId },
            activateSession = { id, transcriptCursor ->
                runtimeStateStore.activateSession(id)
                preferences.edit().putString(KEY_SESSION_ID, id).apply()
                eventLog = eventLogFor(id)
                transcriptProjectionCursor = transcriptCursor
            },
            beginTransition = ::beginSessionTransition,
            endTransition = ::endSessionTransition,
            navigationBusy = { synchronized(runStateLock) { localSessionNavigationBusy(sessionTransitioning, activeJob?.isCompleted == false) } },
            cancelActiveRunAndJoin = ::cancelActiveRunAndJoin,
            cancelWorkRunsAndJoin = ::cancelWorkRunsAndJoin,
            resetModelHistory = { modelHistory.reset() },
            persist = ::persist,
            loadSession = { id ->
                loadSession(id)
                syncVisibleWorkRun(id)
            },
            restartInterruptedSafeJobs = persistentJobRecoveryCoordinator::schedule,
            startNextQueuedTurnIfIdle = ::startNextQueuedTurnIfIdle,
            sessionSummaries = ::sessionSummaries,
            beforeEventLogsDeleted = eventLogRegistry::clearAndEvict,
            localProjectId = LOCAL_PROJECT_ID,
        )
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
                // Migration may have copied an event log after the field was first constructed.
                // Reopen it before any session load or tool can append to the migrated log.
                eventLog = eventLogFor(currentSessionId)
                prepareLocalHarnessStartup(
                    prepareRuntime = bundledRuntimeManager::prepare,
                    installPlugins = { pluginComposition.installStartup() },
                    restoreSession = { load(deferReady = true) },
                )
                _state.update { current ->
                    if (current.loading) current.copy(loading = false) else current
                }
                startNextQueuedTurnIfIdle()?.start()
                persistentJobRecoveryCoordinator.schedule()
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

    private fun chatStreamFilterPhrases(
        snapshot: LocalHarnessState,
        persona: PersonaProfile = snapshot.chat.chatPersona,
    ): List<String> = ChatStyleGuard.activePhrases(
        customPhrases = snapshot.chatStyleGuardCustomPhrases,
        personaPhrases = persona.bannedPhrases,
        enabled = snapshot.usageMode == LocalUsageMode.CHAT && snapshot.chatStyleGuardEnabled,
    )

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

    /** Feature execution and lifecycle ports are exposed to Hilt from the composition root below. */
    internal val sessionLifecyclePort: LocalSessionLifecyclePort = object : LocalSessionLifecyclePort {
        override fun createSession(
            mode: LocalConversationMode,
            usageMode: LocalUsageMode,
            domainSpec: LocalSessionDomainCreateSpec?,
        ): Boolean = sessionLifecycle.createSession(mode, usageMode, domainSpec)

        override fun switchDomainMode(command: LocalSessionDomainModeCommand) {
            sessionLifecycle.switchDomainMode(command)
        }

        override fun switchUsageMode(mode: LocalUsageMode) {
            sessionLifecycle.switchUsageMode(mode)
        }

        override fun switchSession(sessionId: String): Boolean =
            sessionLifecycle.switchSession(sessionId)

        override suspend fun deleteSessions(ids: Set<String>): Int =
            sessionLifecycle.deleteSessions(ids)
    }

    internal val diagnosticsPort: LocalDiagnosticsPort = object : LocalDiagnosticsPort {
        override suspend fun environmentInfo(): String = withContext(Dispatchers.IO) {
            environmentInfoCoordinator.build(null)
        }

        override suspend fun diagnosticReport(): String = withContext(Dispatchers.IO) {
            val appLogs = AppLog.exportSnapshot()
            val baseReport = DiagnosticReport.build(appLogs, environmentInfoCoordinator.build(null))
            appendLocalDiagnosticDetails(
                baseReport = baseReport,
                sessionId = currentSessionId,
                eventLog = eventLogFor(currentSessionId),
                usageTracker = usageTracker,
                appLogs = appLogs,
            )
        }
    }

    internal val toolsManagementPort: LocalToolsManagementPort = object : LocalToolsManagementPort {
        override suspend fun servers(): List<McpServerSnapshot> = pluginComposition.mcpServers()

        override fun installedPluginIds(): List<String> = pluginComposition.installedPluginIds()

        override suspend fun connectHttp(serverId: String, endpoint: String): String =
            pluginComposition.connectMcpHttp(serverId, endpoint)

        override suspend fun connectStdio(
            serverId: String,
            command: List<String>,
            workingDirectory: String?,
        ): String = pluginComposition.connectMcpStdio(serverId, command, workingDirectory)

        override suspend fun disconnect(serverId: String): String =
            pluginComposition.disconnectMcp(serverId)
    }

    internal val workTurnPort: LocalWorkTurnPort = object : LocalWorkTurnPort {
        override fun startPrepared(
            prepared: LocalPreparedSend,
            sessionLease: LocalSessionRuntimeLease,
        ): Job = workTurnStarter.startFresh(
            content = prepared.content,
            memoryInput = prepared.memoryInput,
            modelMessage = prepared.modelMessage,
            sessionLease = sessionLease,
        )

        override fun startRegeneration(messageId: String): Job {
            val sessionId = currentSessionId
            return scope.launch(start = CoroutineStart.LAZY) {
                LocalSessionRuntimeRegistry.withOwner(
                    sessionId,
                    LocalSessionRuntimeKind.FOREGROUND,
                ) { ownedSessionId ->
                    if (currentSessionId != ownedSessionId) {
                        throw CancellationException("会话已切换")
                    }
                    LocalExecutionService.withTurn(
                        context,
                        ownedSessionId,
                        { _state.value.error },
                    ) {
                        workReplyRegenerator.regenerate(messageId)
                    }
                }
            }
        }
    }

    internal val chatTurnPort: LocalChatTurnPort = object : LocalChatTurnPort {
        override fun start(
            content: String,
            memoryInput: String,
            sourceMessageId: String,
        ): Job = scope.launch(start = CoroutineStart.LAZY) {
            chatTurnDispatcher.run(content, memoryInput, sourceMessageId)
        }

        override fun startRegeneration(
            prompt: String,
            replacingMessageId: String,
        ): Job {
            val sessionId = currentSessionId
            return scope.launch(start = CoroutineStart.LAZY) {
                LocalExecutionService.withTurn(
                    context,
                    sessionId,
                    { _state.value.error },
                ) {
                    engineChatDirectTurnExecutor.run(
                        input = prompt,
                        replacingMessageId = replacingMessageId,
                    )
                }
            }
        }
    }

    private fun persistChatBranchState(reason: String) {
        val state = _state.value
        eventLog.append("chat/branch-state", JsonObject(
            encodeChatBranchStateEvent(state.chat.chatBranches) + ("reason" to JsonPrimitive(reason)),
        ))
        val activeTranscript = activeChatBranchMessages(state.chat.chatBranches)
            .ifEmpty { state.messages }
        val transcriptEvent = eventLog.append("chat/active-transcript", buildJsonObject {
            put("reason", reason)
            put("transcript", encodeTranscriptMessages(activeTranscript))
        })
        transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, transcriptEvent.sequence)
    }

    private fun syncVisibleWorkRun(
        sessionId: String,
        ownedBinding: LocalWorkRunBinding? = null,
    ) {
        val binding = ownedBinding ?: liveWorkRun(sessionId) ?: return
        val liveState = binding.aggregateSnapshot()
        liveState.modelState.modelSelection.activeProfile
            ?.takeIf { liveState.modelState.configured }
            ?.let(modelGateway::activate)
        val resources = resourceScheduler.snapshot()
        _state.value = liveState.copy(
            loading = _state.value.loading,
            usage = usageTracker.state.value,
            sessions = sessionSummaries(),
            kernel = liveState.kernel.copy(
                resources = resources.toLocalHarnessResourceState(liveState.usageMode),
                contextBudgetChars = localHistoryBudgetFor(memoryClassMb, resources.pressure).maxHistoryChars,
            ),
        )
        modelHistory.reset(binding.runHandle.modelHistory.snapshot())
        transcriptProjectionCursor = binding.runHandle.transcriptProjectionCursor
    }

    private fun appendUserToModelHistory(
        message: JsonObject,
        binding: LocalWorkRunBinding? = null,
    ) {
        (binding?.runHandle?.modelHistory ?: modelHistory).append(message)
        updateContextMetrics(binding)
    }

    private suspend fun acquireAutomationChatVisibleTurn(
        targetSessionId: String,
        automationJob: Job?,
    ): Boolean {
        if (_state.value.sessionId != targetSessionId) return false
        var ownsVisibleTurn = false
        withTimeout(60_000L) {
            while (!ownsVisibleTurn) {
                ownsVisibleTurn = synchronized(runStateLock) {
                    val busy = sessionTransitioning ||
                        _state.value.kernel.running ||
                        activeJob?.isCompleted == false
                    if (!busy) {
                        activeJob = automationJob
                        true
                    } else {
                        false
                    }
                }
                if (!ownsVisibleTurn) delay(100)
            }
        }
        _state.update { current ->
            if (current.sessionId == targetSessionId) {
                current.copy(kernel = current.kernel.copy(running = true), error = null)
            } else {
                current
            }
        }
        return ownsVisibleTurn
    }

    private fun commitVisibleAutomationChatReply(
        session: LocalHarnessSession,
        reply: LocalModelReply,
        content: String,
        proactiveMessage: LocalHarnessMessage,
        assistantEventSequence: Long,
    ) {
        val beforeProactive = _state.value
        val nextChatState = chatTurnCoordinator.applyDeterministicInteractionState(
            previous = beforeProactive.chat.chatState.withoutLegacyConversationContext(),
            userMessage = "",
            assistantMessage = content,
        ).withoutLegacyConversationContext()
        modelHistory.append(reply.message)
        updateContextMetrics()
        transcriptRuntime.applyMessages(
            listOf(proactiveMessage),
            assistantEventSequence,
        )
        _state.update { current ->
            if (current.sessionId != session.id) {
                current
            } else {
                val baseContext = current.chat.chatContext
                    .applySceneTurn(
                        userMessage = "",
                        assistantMessage = content,
                        sequence = assistantEventSequence,
                    )
                val pending = ChatPendingTurn(
                    sequence = assistantEventSequence,
                    assistantMessageId = proactiveMessage.id,
                    branchHeadId = proactiveMessage.id,
                    userMessage = "",
                    assistantMessage = content,
                    generation = baseContext.generation,
                )
                current.copy(
                    chat = current.chat.copy(
                        chatState = nextChatState,
                        chatContext = baseContext.enqueuePendingDurably(pending, eventLog),
                    ),
                )
            }
        }
        if (
            pendingInputs.size() == 0 &&
            beforeProactive.transcriptIndex.branchingEligible &&
            beforeProactive.chat.chatBranches.nodes.isNotEmpty()
        ) {
            _state.update { current ->
                current.copy(
                    chat = current.chat.copy(
                        chatBranches = appendMaterializedChatBranchMessage(
                            current = current.chat.chatBranches,
                            activeMessages = emptyList(),
                            message = proactiveMessage,
                            parentId = beforeProactive.transcriptIndex.latestDialogueMessageId,
                            chatState = current.chat.chatState,
                            chatContext = current.chat.chatContext,
                            replySuggestions = current.chat.replySuggestions,
                        ),
                    ),
                )
            }
        }
        checkpointModelHistory("chat/proactive-automation")
        persist()
    }

    private fun releaseAutomationChatVisibleTurn(
        targetSessionId: String,
        automationJob: Job?,
    ) {
        _state.update { current ->
            if (current.sessionId == targetSessionId) {
                current.copy(
                    work = current.work.copy(
                        pendingApproval = null,
                        pendingQuestion = null,
                        deviceApprovalLease = false,
                    ),
                    kernel = current.kernel.copy(running = false),
                )
            } else {
                current
            }
        }
        synchronized(runStateLock) {
            if (activeJob === automationJob) activeJob = null
        }
        startNextQueuedTurnIfIdle()?.start()
    }


    internal suspend fun diagnoseNetwork(target: String): String = webTools.diagnose(target)

    private fun beginSessionTransition(): Boolean =
        runtimeStateStore.beginSessionTransition().also { started ->
            if (started) cancelChatPostTurn()
        }

    private fun endSessionTransition() {
        runtimeStateStore.endSessionTransition()
    }

    private suspend fun cancelWorkRunsAndJoin(sessionIds: Set<String>) {
        val cancelled = synchronized(runStateLock) {
            workRunRegistry.detachAll(sessionIds)
        }
        withContext(NonCancellable) {
            var failure: Throwable? = null
            cancelled.forEach { binding ->
                try {
                    binding.cancelAndJoin()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure?.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private suspend fun cancelActiveRunAndJoin() {
        runtimeStateStore.cancelForegroundRunAndJoin(eventLog)
    }

    private suspend fun captureAutoMemoryDirective(
        text: String,
        sourceMessageId: String? = null,
        binding: LocalWorkRunBinding? = null,
    ) {
        if (binding == null) {
            memoryCoordinator.captureAutoMemoryDirective(text, sourceMessageId)
            return
        }
        LocalMemoryCoordinator(
            state = MutableStateFlow(binding.aggregateSnapshot()),
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            diaryStore = chatDiaryStore,
            currentSessionId = { binding.sessionId },
            eventLog = { binding.eventLog },
            persist = { persist(binding) },
        ).captureAutoMemoryDirective(text, sourceMessageId)
    }

    private fun chatRelationshipMemoryContext(
        query: String,
        snapshot: LocalHarnessState,
    ): String = memoryCoordinator.chatRelationshipMemoryContext(query, snapshot)

    private suspend fun drainPendingInputsIntoHistory(binding: LocalWorkRunBinding? = null) {
        val targetPending = binding?.runHandle?.pendingInputs ?: pendingInputs
        val targetHistory = binding?.runHandle?.modelHistory ?: modelHistory
        val targetState = aggregateRunState(binding)
        val targetLog = binding?.eventLog ?: eventLog
        val queued = targetPending.drain()
        if (queued.isEmpty()) return
        val durableMessages = mutableListOf<JsonObject>()
        queued.forEach { input ->
            val durableMessage = input.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", input.content)
            }
            targetHistory.append(durableMessage)
            durableMessages += durableMessage
            captureAutoMemoryDirective(input.memoryInput, input.id, binding)
        }
        targetState.update { it.copy(kernel = it.kernel.copy(queuedInputCount = targetPending.size())) }
        targetLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "claimed",
                pending = targetPending.snapshot(),
                affected = queued,
                modelMessages = durableMessages,
            ),
        )
        updateContextMetrics(binding)
        persist(binding)
    }

    private fun startNextQueuedTurnIfIdle(): Job? = synchronized(runStateLock) {
        val liveWorkOwner = liveWorkRun(currentSessionId) != null
        if (
            !LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn(
                sessionTransitioning = sessionTransitioning,
                visibleRunActive = activeJob?.isCompleted == false,
                liveWorkOwner = liveWorkOwner,
            )
        ) return@synchronized null

        val workLease = if (_state.value.usageMode == LocalUsageMode.WORK) {
            LocalSessionRuntimeRegistry.tryAcquire(
                currentSessionId,
                LocalSessionRuntimeKind.FOREGROUND,
            ) ?: return@synchronized null
        } else {
            null
        }
        val next = pendingInputs.poll()
        if (next == null) {
            workLease?.close()
            return@synchronized null
        }
        if (_state.value.usageMode == LocalUsageMode.WORK) {
            workTurnStarter.startResumed(
                input = next,
                sessionLease = requireNotNull(workLease),
            )
        } else {
            val durableMessage = next.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", next.content)
            }
            _state.update { it.copy(kernel = it.kernel.copy(queuedInputCount = pendingInputs.size())) }
            appendUserToModelHistory(durableMessage)
            eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "resumed",
                    pending = pendingInputs.snapshot(),
                    affected = listOf(next),
                    modelMessages = listOf(durableMessage),
                ),
            )
            persist()
            scope.launch(start = CoroutineStart.LAZY) {
                chatTurnDispatcher.run(next.content, next.memoryInput, next.id)
            }.also { activeJob = it }
        }
    }

    private fun rebuildGroupModelHistoryFromTranscript(messages: List<LocalHarnessMessage>) {
        val rebuilt = buildList {
            add(buildJsonObject {
                put("role", "system")
                put("content", groupChatSystemPrompt())
            })
            messages.forEach { message ->
                if (message.role == "user" || message.role == "assistant") {
                    add(buildJsonObject {
                        put("role", message.role)
                        put(
                            "content",
                            if (message.role == "assistant") groupTranscriptLine(message) else message.content,
                        )
                    })
                }
            }
        }
        modelHistory.reset(rebuilt)
        updateContextMetrics()
    }

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

    private suspend fun runWorkAgentTurn(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
        binding: LocalWorkRunBinding,
        preownedLease: LocalSessionRuntimeLease? = null,
    ): Unit = LocalSessionRuntimeRegistry.withOwner(
        binding.sessionId,
        LocalSessionRuntimeKind.FOREGROUND,
        preownedLease,
    ) {
        val runState = aggregateRunState(binding)
        val runEventLog = binding.eventLog
        val runHistory = binding.runHandle.modelHistory
        val runTranscript = binding.transcriptRuntime
        val runSessionId = binding.sessionId
        check(runState.value.usageMode == LocalUsageMode.WORK) {
            "Work Agent 回合只能处理 Work 模式"
        }
        val runPolicy = localAgentRunPolicy(LocalUsageMode.WORK)
        clearRunCapabilities(binding)
        toolExecutionCoordinator.prepareWorkTurnCapabilities(
            input, runHistory.snapshot(), pluginComposition::githubConfigured, binding.enabledOptionalTools,
        )
        val foregroundSessionId = runSessionId
        var foregroundOutcome = LocalExecutionService.OUTCOME_COMPLETED
        LocalExecutionService.holdTurn(context, foregroundSessionId)
        runState.update {
            it.copy(
                work = it.work.copy(
                    workflowProgress = null,
                    deviceApprovalLease = false,
                ),
                kernel = it.kernel.copy(running = true),
                error = null,
            )
        }
        val repliesByStep = mutableMapOf<Int, LocalModelReply>()
        var modelStep = 0
        val modelToolStepSurface = LocalModelToolStepSurface()
        var requestPrepared = false
        var workPromptContext = LocalWorkTurnPromptContext()
        val runSnapshot = runState.value
        val runToolSurface = LocalRunToolSurface(
            runSnapshot.modelState.modelSelection.activeProfile?.toRunModelSurface(),
        )
        val mainMaxSteps = runSnapshot.mainMaxSteps
        val mainStepLimit = if (runPolicy.allowToolExecution) {
            adaptiveAgentStepLimit(
                configuredBase = mainMaxSteps,
                task = input,
                contextChars = runSnapshot.kernel.contextChars,
                contextBudgetChars = runSnapshot.kernel.contextBudgetChars,
                pressure = resourceScheduler.snapshot().pressure,
                kind = LocalAgentRunKind.FOREGROUND,
            )
        } else 1
        val continuationParentRunId = binding.continuationParentRunId
        binding.continuationParentRunId = null
        val runContext = agentRunCoordinator.start(
            sessionId = foregroundSessionId,
            usageMode = runSnapshot.usageMode,
            model = runSnapshot.modelState.model,
            baseUrl = runSnapshot.modelState.baseUrl,
            routeProfile = runSnapshot.modelState.modelSelection.activeProfile,
            planMode = runSnapshot.work.planMode,
            policy = runPolicy,
            safeAutoApprovalEnabled = runSnapshot.safeAutoApprovalEnabled,
            maxSteps = mainStepLimit,
            input = input,
            memoryInput = memoryInput,
            allowMutation = runPolicy.allowToolExecution,
            resourceBudget = LocalAgentRunResourceBudget(
                maxModelRequests = resourceScheduler.budget.maxModelRequests,
                maxAgents = resourceScheduler.budget.maxAgents,
                maxTerminals = resourceScheduler.budget.maxTerminals,
                maxVirtualDisplays = resourceScheduler.budget.maxVirtualDisplays,
                maxLanguageServers = resourceScheduler.budget.maxLanguageServers,
            ),
            toolNames = runToolNames(runPolicy, binding),
            contextChars = runSnapshot.kernel.contextChars,
            parentRunId = continuationParentRunId,
        )
        var lastModelError: LocalModelException? = null
        val progressTracker = LocalAgentProgressTracker()
        var activeStep: Int? = null
        var activeToolCalls = emptyList<AgentToolCall>()
        val completedToolCallIds = linkedSetOf<String>()

        fun settlePendingTools(reason: String) {
            val actuallyStarted = startedToolCallIdsForActiveStep(runEventLog, activeToolCalls)
            val settlements = pendingToolSettlements(
                calls = activeToolCalls,
                startedCallIds = actuallyStarted,
                completedCallIds = completedToolCallIds,
            )
            if (settlements.isEmpty()) return
            settlements.forEach { settlement ->
                val result = SessionRecovery.interruptedToolResult(
                    callId = settlement.call.id,
                    name = settlement.call.name,
                    step = activeStep,
                    started = settlement.started,
                )
                runEventLog.append("tool/result", buildJsonObject {
                    result.step?.let { put("step", it) }
                    put("id", result.callId)
                    result.name?.let { put("name", it) }
                    put("content", result.content)
                    put("model_content", result.modelContent)
                    put("is_error", true)
                    put("error_code", result.code)
                    put("retryable", result.code == SessionRecovery.TOOL_NOT_STARTED)
                    put(
                        "side_effect",
                        if (result.code == SessionRecovery.TOOL_OUTCOME_UNKNOWN) "possible" else "none",
                    )
                    put("runtime_settlement", true)
                    put("reason", reason)
                })
                runHistory.append(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId)
                        put("content", result.modelContent)
                    },
                )
                completedToolCallIds += result.callId
            }
        }

        val loop = AgentLoop(
            model = AgentModel {
                agentRunCoordinator.ensureCurrentOwner(runContext)
                // Persistent history stays compact; user rules, recalled memory and handoff are
                // assembled per request and are deliberately never written back into runHistory.
                if (!requestPrepared) {
                    workTurnHistoryRuntime.ensureSystemMessage(binding)
                    val snapshot = runState.value
                    workPromptContext = contextComposer.composeWorkTurnContext(input, snapshot, workspace.path)
                    captureAutoMemoryDirective(memoryInput, sourceMessageId, binding)
                    requestPrepared = true
                }
                drainPendingInputsIntoHistory(binding)
                val key = modelRequestMarker()
                val snapshot = runState.value
                val tools = modelToolStepSurface.capture(
                    runToolSurface.next(modelToolSchemas(runPolicy, binding), snapshot),
                )
                val productContextTokens =
                    estimateModelTokens(workPromptContext.stable) +
                        estimateModelTokens(workPromptContext.dynamic)
                if (shouldProactivelyCompactBeforeModelStep(snapshot.usageMode, modelStep)) {
                    workTurnHistoryRuntime.compactIfNeeded(
                        binding = binding,
                        extraTokens = productContextTokens + estimateModelTokens(tools.toString()),
                    )
                }
                val durableRequestMessages = withWorkTurnContext(
                    runHistory.snapshot(),
                    workPromptContext.stable,
                    workPromptContext.dynamic,
                )
                val selectedMode = resolveLocalImageInputMode(
                    snapshot.modelState.imageInputMode,
                    imageCapabilities,
                    snapshot.modelState.baseUrl,
                    snapshot.modelState.model,
                )
                if (hasLocalImageRefs(durableRequestMessages) &&
                    imageCapabilities.state(snapshot.modelState.baseUrl, snapshot.modelState.model) == LocalImageCapability.UNSUPPORTED) {
                    throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
                }
                val requestMessages = prepareLocalMultimodalMessages(
                    messages = durableRequestMessages,
                    workspaceRoot = File(workspace.path),
                    mode = selectedMode,
                    budget = imageRequestBudget,
                    maxImageBytes = LocalModelPresets.maxNativeImageBytesFor(snapshot.modelState.model, snapshot.modelState.baseUrl),
                )
                val nativeImagesSent = hasMaterializedImageUrls(requestMessages)
                agentRunCoordinator.ensureCurrentOwner(runContext)
                val rawReply = try {
                    completeWithRetry(
                        key = key,
                        snapshot = snapshot,
                        messages = requestMessages,
                        step = modelStep + 1,
                        toolsOverride = tools,
                        publishPreview = true,
                        persistOverflowHistory = true,
                        binding = binding,
                    ).also {
                        if (nativeImagesSent) {
                            imageCapabilities.markSupported(snapshot.modelState.baseUrl, snapshot.modelState.model)
                        }
                    }
                } catch (error: Throwable) {
                    if (error is LocalModelException) lastModelError = error
                    val nativeImageRejected =
                        nativeImagesSent &&
                            imageInputUnsupported(error)
                    if (nativeImageRejected) {
                        imageCapabilities.markUnsupported(snapshot.modelState.baseUrl, snapshot.modelState.model)
                    }
                    if (nativeImageRejected) {
                        throw IllegalStateException(
                            "当前模型不支持图片理解，请切换支持图片的模型后重试。",
                            error,
                        )
                    } else {
                        throw error
                    }
                }
                val reply = rawReply.also { completed ->
                    usageTracker.recordForeground(
                        snapshot = snapshot,
                        reply = completed,
                        action = TokenUsageAction.WORK_MAIN,
                        turnId = sourceMessageId ?: runContext.runId,
                        runId = runContext.runId,
                        taskLabel = input,
                        step = modelStep + 1,
                    )
                }
                val deliveryReply = guardWorkCompletionDelivery(reply, runState.value, runEventLog)
                modelStep += 1
                repliesByStep[modelStep] = deliveryReply
                AgentModelReply(
                    content = deliveryReply.content.orEmpty(),
                    toolCalls = if (runPolicy.allowToolExecution) {
                        deliveryReply.toolCalls.map { call ->
                            AgentToolCall(
                                id = call.id,
                                name = call.name,
                                arguments = call.arguments,
                                rawArguments = call.rawArguments,
                            )
                        }
                    } else {
                        emptyList()
                    },
                )
            },
            tools = AgentToolExecutor { call ->
                agentRunCoordinator.ensureCurrentOwner(runContext)
                if (!modelToolStepSurface.allows(call.name)) {
                    modelToolStepSurface.hiddenCallResult(call.name)
                } else {
                    executeSafely(call.toLocalToolCall(), allowMutation = true, binding = binding)
                }
            },
            toolBatch = AgentToolBatchExecutor { calls ->
                agentRunCoordinator.ensureCurrentOwner(runContext)
                executeToolBatch(
                    calls = calls.map { it.toLocalToolCall() },
                    allowMutation = true,
                    binding = binding,
                ).map { (_, result) -> result }
            },
            isParallelTool = { call ->
                modelToolStepSurface.allows(call.name) &&
                    call.name in PARALLEL_SUBAGENT_TOOLS
            },
            eventSink = AgentEventSink { event ->
                agentRunCoordinator.ensureCurrentOwner(runContext)
                when (event) {
                    is AgentEvent.TurnStarted -> {
                        runEventLog.append("turn/start", buildJsonObject {
                            put("model", runState.value.modelState.model)
                        })
                    }
                    is AgentEvent.StepStarted -> {
                        activeStep = event.step
                        LocalExecutionService.holdTurn(context, foregroundSessionId, event.step)
                        activeToolCalls = emptyList()
                        completedToolCallIds.clear()
                        runEventLog.append("step/start", buildJsonObject {
                            put("step", event.step)
                        })
                    }
                    is AgentEvent.AssistantObserved -> {
                        val reply = repliesByStep.remove(event.step)
                            ?: error("缺少第 ${event.step} 步模型响应")
                        progressTracker.recordAssistant(reply.content.orEmpty(), reply.toolCalls.size)
                        val beforeAssistant = runState.value
                        val transcriptMessages = buildList {
                            reply.reasoning?.takeIf(String::isNotBlank)?.let { reasoning ->
                                add(runTranscript.newMessage("reasoning", reasoning))
                            }
                            reply.content?.takeIf(String::isNotBlank)?.let { content ->
                                add(
                                    runTranscript.newMessage(
                                        role = if (event.toolCalls.isEmpty()) "assistant" else "progress",
                                        content = content,
                                    ),
                                )
                            }
                        }
                        activeToolCalls = event.toolCalls
                        completedToolCallIds.clear()
                        val assistantEvent = runEventLog.append(
                            "assistant/message", runTranscript.withTranscript(reply.message, transcriptMessages)
                                .withModelToolCallEventData(reply.toolCalls),
                        )
                        runHistory.append(reply.message)
                        workTurnHistoryRuntime.updateContextMetrics(binding)
                        runTranscript.applyMessages(transcriptMessages, assistantEvent.sequence)
                        workTurnHistoryRuntime.persist(binding)
                    }
                    is AgentEvent.ToolStarted -> {
                        runEventLog.append("tool/call", buildJsonObject {
                            put("step", event.step)
                            put("id", event.call.id)
                            put("name", event.call.name)
                            put("arguments", event.call.arguments)
                            put("execution_started", false)
                        })
                    }
                    is AgentEvent.ToolFinished -> {
                        progressTracker.recordToolResult(event.call, event.output, event.isError)
                        val boundedContent = workTurnHistoryRuntime.retainToolResult(
                            binding = binding,
                            callId = event.call.id,
                            result = event.output,
                            retention = event.retention,
                        )
                        val modelOutput = AgentToolResult(
                            content = boundedContent,
                            isError = event.isError,
                            errorCode = event.errorCode,
                            retryable = event.retryable,
                            sideEffect = event.sideEffect,
                            recoveryHint = event.recoveryHint,
                        ).modelVisibleContent()
                        val transcriptMessage = runTranscript.newMessage(
                            role = "tool",
                            content = durableToolResultContent(boundedContent, event.retention),
                            toolName = event.call.name,
                            contentAlreadyBounded = true,
                            toolIsError = event.isError,
                            toolErrorCode = event.errorCode,
                        )
                        val toolEvent = runEventLog.append("tool/result", buildJsonObject {
                            put("step", event.step)
                            put("id", event.call.id)
                            put("name", event.call.name)
                            put(
                                "content",
                                truncateWithoutSplittingSurrogatePair(
                                    durableToolResultContent(event.output, event.retention), MAX_EVENT_CHARS,
                                ),
                            )
                            put("model_content", durableToolResultContent(modelOutput, event.retention))
                            put("is_error", event.isError)
                            put("retention", event.retention.name.lowercase())
                            event.errorCode?.let { put("error_code", it) }
                            put("retryable", event.retryable)
                            put("side_effect", event.sideEffect.name.lowercase())
                            event.recoveryHint?.let { put("recovery_hint", it) }
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        runHistory.append(localToolHistoryMessage(event.call.id, modelOutput, event.retention))
                        completedToolCallIds += event.call.id
                        workTurnHistoryRuntime.updateContextMetrics(binding)
                        runTranscript.applyMessages(listOf(transcriptMessage), toolEvent.sequence)
                        workTurnHistoryRuntime.persist(binding)
                    }
                    is AgentEvent.StepFinished -> {
                        runEventLog.append("step/end", buildJsonObject {
                            put("step", event.step)
                        })
                        activeStep = null
                        activeToolCalls = emptyList()
                        completedToolCallIds.clear()
                    }
                    is AgentEvent.TurnCompleted -> {
                        recordWorkCompletionQuality(event.answer, runState.value, runEventLog)
                        runEventLog.append("turn/end", buildJsonObject {
                            put("reason", "completed")
                            put("steps", event.steps)
                            put("messages", runState.value.transcriptIndex.totalMessageCount)
                        })
                        workTurnHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/completed")
                    }
                    is AgentEvent.TurnStepLimit -> {
                        val transcriptMessage = runTranscript.newMessage(
                            "system",
                            "当前任务已无法继续扩展执行预算，已在第 ${event.steps} 步暂停；已有进度已保留。",
                        )
                        val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                            put("reason", "step_limit")
                            put("steps", event.steps)
                            put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                        workTurnHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/step-limit")
                        workTurnHistoryRuntime.persist(binding)
                    }
                    is AgentEvent.TurnFailed -> {
                        settlePendingTools("failed")
                        val detail = event.reason.take(2_000)
                        val continuationEligible =
                            shouldAutoContinueWorkFailure(
                                error = lastModelError,
                                automaticContinuationCount = binding.automaticContinuationCount,
                                pendingInputs = binding.runHandle.pendingInputs.size(),
                            )
                        if (continuationEligible) {
                            runEventLog.append("turn/end", buildJsonObject {
                                put("reason", "stream_interrupted_continuation")
                                put("detail", detail)
                                put("messages", runState.value.transcriptIndex.totalMessageCount)
                            })
                            workTurnHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/stream-interrupted")
                        } else {
                            val transcriptMessage = runTranscript.newMessage("system", "执行失败：$detail")
                            val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                                put("reason", "error")
                                put("detail", detail)
                                put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                            workTurnHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/failed")
                        }
                        workTurnHistoryRuntime.persist(binding)
                    }
                    is AgentEvent.TurnCancelled -> {
                        settlePendingTools("cancelled")
                        val transcriptMessage = runTranscript.newMessage("system", "本轮已停止。")
                        val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                            put("reason", "aborted")
                            put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                        workTurnHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/cancelled")
                        workTurnHistoryRuntime.persist(binding)
                    }
                }
                agentRunCoordinator.recordEvent(runContext, event)
            },
            maxSteps = mainStepLimit,
            stepLimitExtender = localForegroundStepLimitExtender(
                enabled = runPolicy.allowToolExecution,
                configuredBase = mainMaxSteps,
                task = input,
                state = { runState.value },
                pressure = { resourceScheduler.snapshot().pressure },
                onExtended = { runEventLog.append("turn/budget-extended", it) },
                canExtend = progressTracker::claimExtensionProgress,
            ),
            idFactory = { runContext.runId },
        )

        try {
            withTimeout(FOREGROUND_TURN_TIMEOUT_MILLIS) {
                modelGateway.withFrozenRoute(
                    profileId = runSnapshot.modelState.modelSelection.activeProfileId,
                    model = runSnapshot.modelState.model,
                    baseUrl = runSnapshot.modelState.baseUrl,
                ) { loop.run(input) }
            }
        } catch (_: TimeoutCancellationException) {
            foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
            val timeoutError = LocalModelException(
                code = "WORK_SLICE_TIMEOUT",
                message = "本轮执行达到 15 分钟切片上限",
                retryable = false,
                continuationEligible = true,
            )
            val queued = queueAutomaticWorkContinuation(binding, runContext.runId, timeoutError)
            if (!queued) {
                runState.update { it.copy(error = "本轮执行超过 15 分钟，已暂停并保留已有进度") }
            }
        } catch (_: CancellationException) {
            foregroundOutcome = LocalExecutionService.OUTCOME_CANCELLED
            // TurnCancelled durably records and projects the visible stop message.
        } catch (error: Exception) {
            foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
            val modelError = error as? LocalModelException
            val queued = if (modelError != null) {
                queueAutomaticWorkContinuation(binding, runContext.runId, modelError)
            } else {
                false
            }
            if (!queued) {
                runState.update { it.copy(error = error.message ?: "本机执行失败") }
            }
            // TurnFailed has already settled tool side effects and checkpointed model-visible state.
        } finally {
            binding.interactions.cancelAll()
            runState.update {
                it.copy(
                    work = it.work.copy(
                        pendingApproval = null,
                        pendingQuestion = null,
                        deviceApprovalLease = false,
                    ),
                    kernel = it.kernel.copy(running = false),
                )
            }
            workTurnHistoryRuntime.persist(binding)
            val completedJob = currentCoroutineContext()[Job]
            LocalExecutionService.releaseTurn(context, foregroundSessionId, foregroundOutcome)
            workRunRegistry.finishTurn(binding, completedJob) { next, ownedBinding ->
                scope.launch(start = CoroutineStart.LAZY) {
                    runWorkAgentTurn(
                        input = next.content,
                        memoryInput = next.memoryInput,
                        sourceMessageId = next.id,
                        binding = ownedBinding,
                    )
                }
            }?.start()
        }
    }

    private fun AgentToolCall.toLocalToolCall(): LocalToolCall = LocalToolCall(
        id = id,
        name = name,
        arguments = arguments,
        rawArguments = rawArguments,
    )

    private suspend fun executeToolBatch(
        calls: List<LocalToolCall>,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding? = null,
    ): List<Pair<LocalToolCall, AgentToolResult>> {
        val parallelSubagents = calls.size > 1 && calls.all { it.name in PARALLEL_SUBAGENT_TOOLS }
        if (!parallelSubagents) {
            return calls.map { call -> call to executeSafely(call, allowMutation, binding) }
        }
        return isolatedParallelMap(calls) { call ->
            call to executeSafely(call, allowMutation, binding)
        }.mapIndexed { index, result ->
            result.getOrElse { error ->
                val call = calls[index]
                call to toolFailureResult(
                    call,
                    "PARALLEL_TASK_ERROR",
                    error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

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

    private suspend fun executeAutomationSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        val normalized = if (
            canonical.name in setOf("bash", "run_shell", "web_fetch") &&
            canonical.arguments["run_in_background"]?.jsonPrimitive?.booleanOrNull == true
        ) {
            canonical.copy(
                arguments = JsonObject(
                    canonical.arguments + ("run_in_background" to JsonPrimitive(false)),
                ),
            )
        } else {
            canonical
        }
        val log = eventLogFor(sessionId)
        log.append("tool/call", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("arguments", normalized.arguments)
            put("execution_started", false)
            put("automation", true)
        })
        val result = try {
            when (normalized.name) {
                "capability_search" -> AgentToolResult(
                    searchCapabilities(normalized.arguments.string("query"), enabledOptionalTools),
                )
                "memory_search", "memory_list" -> AgentToolResult(
                    memoryTools.execute(normalized.name, normalized.arguments, allowMutation = false),
                )
                "tool_output_read" -> AgentToolResult(
                    toolOutputStore.read(
                        sessionId = sessionId,
                        callId = normalized.arguments.string("call_id"),
                        startByte = normalized.arguments.int("start_byte", 0),
                        maxBytes = normalized.arguments.int(
                            "max_bytes",
                            LocalToolOutputStore.DEFAULT_READ_BYTES,
                        ),
                    ),
                )
                else -> executeAutomationRegistered(
                    original = normalized,
                    allowMutation = allowMutation,
                    sessionId = sessionId,
                    onApprovalBlocked = onApprovalBlocked,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            toolFailureResult(
                normalized,
                "AUTOMATION_TOOL_ERROR",
                error.message ?: error::class.java.simpleName,
            )
        }
        log.append("tool/result", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("content", truncateWithoutSplittingSurrogatePair(result.content, MAX_EVENT_CHARS))
            put("is_error", result.isError)
            result.errorCode?.let { put("error_code", it) }
            put("retryable", result.retryable)
            put("side_effect", result.sideEffect.name.lowercase())
            result.recoveryHint?.let { put("recovery_hint", it) }
            put("automation", true)
        })
        return result
    }

    private suspend fun executeAutomationRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val result = toolExecutionCoordinator.executeScoped(
            original = original,
            sessionId = sessionId,
            allowMutation = allowMutation,
            planModeEnabled = false,
            approval = { call, tool, _ ->
                if (approvalPreferences.isSafeAutoApprovalEnabled()) {
                    eventLogFor(sessionId).append("approval/auto", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-global")
                    })
                    true
                } else {
                    val reason = "后台任务需要人工审批：" + tool.name
                    onApprovalBlocked(reason)
                    eventLogFor(sessionId).append("approval/blocked", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-noninteractive")
                    })
                    false
                }
            },
        )
        return if (result.isError) {
            result.copy(
                recoveryHint = "后台任务不能弹出人工审批；可在工作模式中打开该任务继续处理。" +
                    result.recoveryHint?.let { " " + it }.orEmpty(),
            )
        } else {
            result
        }
    }

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

    private fun modelToolSchemas(
        runPolicy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding? = null,
    ): JsonArray {
        val enabledOptional = binding?.let { run ->
            synchronized(run.enabledOptionalTools) { run.enabledOptionalTools.toSet() }
        }
        return toolSchemaProjection.modelSchemas(
            policy = runPolicy,
            state = binding?.aggregateSnapshot() ?: _state.value,
            history = binding?.runHandle?.modelHistory?.snapshot() ?: modelHistory.snapshot(),
            enabledOptional = enabledOptional,
        )
    }

    private fun runToolNames(
        runPolicy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding?,
    ): List<String> = toolSchemaProjection.names(modelToolSchemas(runPolicy, binding))

    private fun clearRunCapabilities(binding: LocalWorkRunBinding?) {
        binding?.enabledOptionalTools?.let(toolExecutionCoordinator::clearTurnCapabilities)
            ?: toolExecutionCoordinator.clearTurnCapabilities()
    }

    private fun searchCapabilities(query: String): String =
        toolExecutionCoordinator.searchCapabilities(query)

    private fun searchCapabilities(query: String, target: MutableSet<String>): String =
        toolExecutionCoordinator.searchCapabilities(query, target)

    private suspend fun executeBuiltin(
        call: LocalToolCall,
        allowMutation: Boolean,
        executionSessionId: String?,
    ): String {
        val args = call.arguments
        val binding = executionSessionId?.let(workRunRegistry::get)
        val executionState = aggregateRunState(binding)
        val boundSessionId = binding?.sessionId ?: currentSessionId
        if (executionState.value.work.planMode && call.name in PLAN_MODE_BLOCKED_TOOLS) {
            return "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。"
        }
        return when (call.name) {
            "read", "read_file" -> workspace.read(
                relativePath = args.string("path"),
                startLine = args.int("start_line", 1),
                endLine = args.int("end_line", args.int("start_line", 1) + 399),
            )
            "tool_output_read" -> toolOutputStore.read(
                sessionId = boundSessionId,
                callId = args.string("call_id"),
                startByte = args.int("start_byte", 0),
                maxBytes = args.int("max_bytes", LocalToolOutputStore.DEFAULT_READ_BYTES),
            )
            "file_inspect" -> fileInspector.inspect(args.string("path"))
            "write", "write_file" -> {
                if (!allowMutation) return "子代理无写入权限"
                val path = args.string("path")
                workspace.write(path, args.string("content"))
            }
            "edit", "edit_file" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val path = args.string("path")
                workspace.requireFreshObservation(path)
                workspace.edit(path, args.string("old_text"), args.string("new_text"))
            }
            "apply_patch" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val patch = args.string("patch")
                require(patch.length <= MAX_PATCH_CHARS) { "补丁超过 ${MAX_PATCH_CHARS} 字符上限" }
                validateWorkspacePatchPaths(patch)
                val check = runtimeProcess.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--check", "--whitespace=nowarn", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                if (check.exitCode != 0) {
                    return "[apply_patch][CHECK_FAILED] 补丁预检失败：\n" +
                        (check.stderr.ifBlank { check.stdout }).take(20_000)
                }
                val stat = runtimeProcess.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--stat", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                val applied = runtimeProcess.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--whitespace=nowarn", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                if (applied.exitCode != 0) {
                    return "[apply_patch][APPLY_FAILED] 补丁应用失败：\n" +
                        (applied.stderr.ifBlank { applied.stdout }).take(20_000)
                }
                "补丁已应用" + stat.stdout.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
            }
            "list_files" -> workspace.list(args.optionalString("path") ?: ".", args.int("depth", 3))
            "glob", "glob_files" -> workspace.glob(args.string("pattern"), args.optionalString("path") ?: ".")
            "grep", "search_text" -> workspace.search(
                args.string("query"),
                args.optionalString("path") ?: ".",
                args.boolean("regex", false),
            )
            "bash", "run_shell" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                LocalShellTool.execute(args, workspace, jobs, boundSessionId)
            }
            "job_list" -> jobs.list(boundSessionId)
            "job_output" -> jobs.output(args.string("job_id"), boundSessionId)
            "job_kill" -> jobs.kill(args.string("job_id"), boundSessionId)
            "web_search" -> {
                val queries = args["queries"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                webTools.search(queries, tokenUsageBridge.resolve(boundSessionId, call.id, TokenUsageAction.WEB_SEARCH, queries.firstOrNull()))
            }
            "web_fetch" -> {
                val input = args.string("url")
                val maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES).coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES)
                val format = args.optionalString("format") ?: "text"
                val background = args.boolean("run_in_background", false)
                val readOnlyScope = !allowMutation || executionState.value.work.planMode
                if (background && readOnlyScope) {
                    return "当前为只读/规划作用域，不能创建后台网页抓取任务"
                }
                val timeout = if (background) BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS else FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS
                if (background) {
                    startPersistentWebFetch(input, maxBytes, format, timeout, boundSessionId)
                } else {
                    webTools.fetch(
                        input,
                        maxBytes,
                        format,
                        timeout,
                        allowArtifactWrite = !readOnlyScope,
                    )
                }
            }
            "http_request" -> {
                if (
                    (!allowMutation || executionState.value.work.planMode) &&
                    args.string("method").uppercase() !in setOf("GET", "HEAD")
                ) {
                    return "只读/规划作用域仅允许 GET/HEAD 请求"
                }
                val headers = args["headers"]?.jsonObject?.mapValues { (_, value) ->
                    value.jsonPrimitive.content
                }.orEmpty()
                webTools.httpRequest(
                    method = args.string("method"),
                    url = args.string("url"),
                    headers = headers,
                    body = args.optionalString("body"),
                    maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES)
                        .coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES),
                    timeoutSeconds = FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS,
                )
            }
            "download_file" -> {
                if (!allowMutation) return "只读子任务不能下载写入文件"
                webTools.download(
                url = args.string("url"),
                path = args.string("path"),
                maxBytes = args.int("max_bytes", DEFAULT_DOWNLOAD_BYTES)
                    .coerceIn(1_024, MAX_DOWNLOAD_BYTES)
                    .toLong(),
                timeoutSeconds = BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS,
            )
            }
            "json_query" -> webTools.jsonQuery(
                path = args.string("path"),
                query = args.optionalString("query").orEmpty(),
                allowArtifactWrite = allowMutation && !executionState.value.work.planMode,
            )
            "network_diagnose" -> webTools.diagnose(args.string("url"))
            "environment_info" -> environmentInfoCoordinator.build(
                binding?.toEnvironmentRunSnapshot(currentHistoryBudget(binding).maxHistoryChars),
            )
            "capability_search" -> if (binding == null) {
                searchCapabilities(args.string("query"))
            } else {
                searchCapabilities(args.string("query"), binding.enabledOptionalTools)
            }
            "update_plan" -> workProgress(binding).updatePlan(args)
            "exit_plan_mode" -> exitWorkPlanMode(
                call = call,
                plan = args.string("plan"),
                state = binding?.workState ?: localAggregateWorkStatePort(_state),
                interactions = binding?.interactions ?: runtimeStateStore.foregroundInteractions,
                aggregateSnapshot = { binding?.aggregateSnapshot() ?: _state.value },
                history = binding?.runHandle?.modelHistory ?: modelHistory,
                eventLog = binding?.eventLog ?: eventLog,
                persist = { persist(binding) },
                updateContextMetrics = { updateContextMetrics(binding) },
            )
            "todo_write" -> workProgress(binding).updateTodos(args)
            "create_goal" -> workProgress(binding).createGoal(args.string("description"))
            "get_goal" -> workProgress(binding).getGoal()
            "update_goal" -> workProgress(binding).updateGoal(args.string("status"), args.optionalString("note"))
            "ask_user_question" -> askUser(
                call,
                args.string("question"),
                args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                binding,
            )
            "skill" -> args.optionalString("name")?.takeIf(String::isNotBlank)?.let(workspace::readSkill)
                ?: workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "list_skills" -> workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "read_skill" -> workspace.readSkill(args.string("name"))
            "subagent", "spawn_subagent" -> {
                val task = args.string("task")
                val model = LocalWorkerModelRouter.resolve(args.optionalString("model"), executionState.value)
                val maxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(
                    args.int("max_steps", executionState.value.subagentMaxSteps),
                )
                val virtualScreen = args.boolean("virtual_screen", false)
                if (args.boolean("run_in_background", false)) {
                    persistentJobRecoveryCoordinator.startReadonlySubagent(
                        task = task,
                        model = model,
                        maxSteps = maxSteps,
                        virtualScreen = virtualScreen,
                        sessionId = boundSessionId,
                        boundState = executionState.value,
                        historySnapshot = binding?.runHandle?.modelHistory?.let { history -> history::snapshot }
                            ?: modelHistory::snapshot,
                    )
                } else (binding?.let(workSubagentRuntime::runner) ?: subagents).run(
                    task = task,
                    inheritHistory = false,
                    allowMutation = false,
                    modelOverride = model,
                    maxSteps = maxSteps,
                    virtualScreen = virtualScreen,
                )
            }
            "subagent_fork", "fork_subagent" ->
                (binding?.let(workSubagentRuntime::runner) ?: subagents).run(
                    task = args.string("task"),
                    inheritHistory = true,
                    allowMutation = allowMutation,
                    parentCallId = call.id,
                    modelOverride = LocalWorkerModelRouter.resolve(null, executionState.value),
                    maxSteps = executionState.value.subagentMaxSteps,
                )
            "list_subagent_models" -> modelGateway.availableProfiles().joinToString("\n") { "${it.id} | ${it.model} | ${it.provider} | ${it.authKind} | ${it.baseUrl}" }
            "list_agents" -> jobs.listAgents(boundSessionId)
            "send_message" -> jobs.send(args.string("agent_id"), args.string("message"), boundSessionId)
            "interrupt_agent" -> jobs.kill(args.string("agent_id"), boundSessionId)
            "workflow" -> workSubagentRuntime.runWorkflow(
                tasks = args["tasks"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                mode = args.optionalString("mode") ?: "parallel",
                requiredEvidence = args["required_evidence"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                modelOverride = args.optionalString("model"),
                state = binding?.workState ?: localAggregateWorkStatePort(_state),
                snapshot = { binding?.aggregateSnapshot() ?: _state.value },
                sessionId = binding?.sessionId ?: currentSessionId,
                runner = binding?.let(workSubagentRuntime::runner) ?: subagents,
            )
            "session_search" -> sessionAccessCoordinator.search(args.string("query"), boundSessionId)
            "memory_search", "memory_list", "memory_remember", "memory_update", "memory_forget" ->
                memoryTools(binding).execute(call.name, args, allowMutation)
            "session_event_search" -> sessionAccessCoordinator.authorizedLog(args.optionalString("session_id"), boundSessionId).search(
                query = args.string("query"),
                limit = args.int("limit", 50),
                afterSequence = args.long("after_sequence", -1L),
            )
            "session_trace" -> sessionAccessCoordinator.authorizedLog(args.optionalString("session_id"), boundSessionId).tail(args.int("limit", 40))
            "session_event_trace" -> sessionAccessCoordinator.authorizedLog(args.optionalString("session_id"), boundSessionId)
                .read(args.int("seq", -1).toLong(), before = 1, after = 1)
            "session_event_read" -> sessionAccessCoordinator.authorizedLog(args.optionalString("session_id"), boundSessionId).read(
                sequence = args.int("seq", -1).toLong(),
                before = args.int("before", 0),
                after = args.int("after", 0), offsetChars = args.int("offset_chars", 0),
            )
            "present" -> workspace.present(args.string("path"))
            else -> "未知工具：${call.name}"
        }
    }

    private fun startPersistentWebFetch(
        url: String,
        maxBytes: Int,
        format: String,
        timeoutSeconds: Long,
        sessionId: String = currentSessionId,
    ): String {
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("url", url)
            put("max_bytes", maxBytes)
            put("format", format)
            put("timeout_seconds", timeoutSeconds)
        }.toString()
        return jobs.startPersistent(
            label = "网页抓取：${url.take(120)}",
            resumeKind = "web_fetch",
            resumePayload = payload,
            ownerSessionId = sessionId,
        ) { _, report ->
            report("正在抓取：$url")
            webTools.fetch(url, maxBytes, format, timeoutSeconds)
        }
    }

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

    private fun workProgress(binding: LocalWorkRunBinding?) = LocalWorkProgressCoordinator(
        state = binding?.workState ?: localAggregateWorkStatePort(_state),
        eventLog = binding?.eventLog ?: eventLog,
        persist = { persist(binding) },
    )

    private suspend fun askUser(
        call: LocalToolCall,
        question: String,
        options: List<String>,
        binding: LocalWorkRunBinding? = null,
    ): String = (binding?.interactions ?: runtimeStateStore.foregroundInteractions).awaitQuestion(
        LocalQuestion(call.id, question.take(2_000), options.take(6)),
    )

    private fun cancelChatPostTurn() {
        chatContextRefreshCoordinator.cancelScheduledRefresh()
    }

    private suspend fun enforceChatStyle(
        key: String,
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        reply: LocalModelReply,
        userMessage: String, usage: ForegroundTokenUsageSeed,
    ): LocalModelReply = chatReplyCoordinator.finalizeDirect(
        snapshot = snapshot,
        reply = reply,
        userMessage = userMessage,
        step = step, usage = usage,
        retryRaw = { repairHint ->
            completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = withEphemeralContext(messages, repairHint),
                step = step,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = 1,
                allowContextOverflowRecovery = false,
                temperature = CHAT_ROLEPLAY_TEMPERATURE,
            )
        },
        appendEvent = { type, data ->
            eventLog.append(type, data)
        },
    )

    private suspend fun modelRequestMarkerOrNull(): String? {
        val snapshot = _state.value
        val profileId = snapshot.modelState.modelSelection.activeProfileId ?: return null
        return modelGateway.profileForRoute(
            profileId = profileId,
            model = snapshot.modelState.model,
            baseUrl = snapshot.modelState.baseUrl,
        ).id
    }

    private suspend fun modelRequestMarker(): String =
        modelRequestMarkerOrNull() ?: error("请先配置模型账户或 API Key")
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

    private fun compactHistoryIfNeeded(
        extraTokens: Int = 0,
        binding: LocalWorkRunBinding? = null,
    ) {
        val baseBudget = currentHistoryBudget(binding)
        val history = binding?.runHandle?.modelHistory ?: modelHistory
        val targetState = aggregateRunState(binding)
        val log = binding?.eventLog ?: eventLog
        val workMode = targetState.value.usageMode == LocalUsageMode.WORK
        val budget = if (workMode) {
            workSteadyStateHistoryBudget(baseBudget, history.estimatedTokens, extraTokens, targetState.value)
        } else {
            baseBudget
        }
        val workSteadyStateApplied = workMode && budget.maxHistoryTokens != baseBudget.maxHistoryTokens
        val summaryMode = if (targetState.value.usageMode == LocalUsageMode.CHAT) {
            LocalHistorySummaryMode.CHAT
        } else {
            LocalHistorySummaryMode.WORK
        }
        val compaction = history.compact(
            compactor = historyCompactor,
            budget = budget,
            extraTokens = extraTokens,
            summaryMode = summaryMode,
            structuredWorkState = if (summaryMode == LocalHistorySummaryMode.WORK) {
                structuredWorkState(targetState.value, log)
            } else {
                null
            },
        ) ?: run {
            updateContextMetrics(binding)
            return
        }
        requestPressureStore.advanceGeneration(
            binding?.sessionId ?: targetState.value.sessionId,
            compaction.estimatedTokensAfter,
        )
        log.append(
            "session/compaction",
            buildJsonObject {
                put("omitted_messages", compaction.omittedMessages)
                put("summary", compaction.summary)
                put("estimated_tokens_before", compaction.estimatedTokensBefore)
                put("estimated_tokens_after", compaction.estimatedTokensAfter)
                put("extra_request_tokens", extraTokens)
                put("work_steady_state", workSteadyStateApplied)
                budget.maxHistoryTokens?.let { put("history_budget_tokens", it) }
                budget.tailTokens?.let { put("tail_budget_tokens", it) }
            },
        )
        checkpointModelHistory("session/compaction", binding)
        updateContextMetrics(binding)
        persist(binding)
    }

    private fun ensureSystemMessage(binding: LocalWorkRunBinding? = null) {
        val history = binding?.runHandle?.modelHistory ?: modelHistory
        val log = binding?.eventLog ?: eventLog
        if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") return
        val prompt = systemPrompt(binding)
        history.prepend(
            buildJsonObject {
                put("role", "system")
                put("content", prompt)
            },
        )
        log.append("system/prompt", buildJsonObject { put("content", prompt) })
        updateContextMetrics(binding)
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

    private suspend fun load(deferReady: Boolean = false) {
        val storedModel = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val baseUrl = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val model = migrateOfficialClaudeModel(modelConfiguration.normalizeModel(storedModel), baseUrl)
        if (model != storedModel) preferences.edit().putString(KEY_MODEL, model).apply()
        modelConfiguration.prepareStartup(model, baseUrl)
        loadSession(currentSessionId, model, baseUrl, deferReady = deferReady)
    }

    private suspend fun loadSession(
        sessionId: String,
        model: String = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
        baseUrl: String = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
        deferReady: Boolean = false,
    ) {
        liveWorkRun(sessionId)?.let { liveBinding ->
            pendingInputs.clear()
            syncVisibleWorkRun(sessionId, liveBinding)
            return
        }

        val loaded = try {
            sessionCoordinator.readWithLegacyApproval(sessionId)
        } catch (future: FutureSessionVersionException) {
            _state.update {
                it.copy(
                    loading = false,
                    sessionId = sessionId,
                    error = future.message,
                )
            }
            return
        }
        val recovery = eventLog.repairInterruptedTail()
        recoverPendingTimelineRewriteProjection(eventLog, memoryStore, chatPersonaGalleryStore, chatDiaryStore)
        val stored = loaded?.session ?: LocalHarnessSession(id = sessionId)
        val legacyProjectionBaseline = if (stored.controlProjectedThroughSequence == null && loaded != null) {
            eventLog.latest(PROJECTION_BASELINE_EVENT)?.sequence ?: eventLog.append(
                PROJECTION_BASELINE_EVENT,
                buildJsonObject { put("source", "legacy-session-snapshot") },
            ).sequence
        } else {
            null
        }
        val projectionCursor = projectionReplayCursor(
            snapshot = stored,
            persistedSnapshotExists = loaded != null,
            legacyBaselineSequence = legacyProjectionBaseline,
        )
        val projectedControls = projectSessionControlTail(
            snapshot = stored,
            events = eventLog.snapshotAfter(projectionCursor),
            sequenceExclusive = projectionCursor,
        )
        val restoredTranscript = sessionCoordinator.restoreTranscript(
            stored = stored,
            persistedSnapshotExists = loaded != null,
        )
        transcriptProjectionCursor = restoredTranscript.projectedThroughSequence
        val restoredHistory = restoreLocalModelHistory(
            events = loadModelHistoryReplayEvents(eventLog, modelHistoryCheckpointCodec, stored.legacyModelHistory),
            legacyFallback = stored.legacyModelHistory,
            codec = modelHistoryCheckpointCodec,
        )
        modelHistory.reset(restoredHistory.messages)
        buildRecoveredToolResultMessages(modelHistory.snapshot(), recovery).forEach { modelHistory.append(it) }
        val restoredInbox = eventLog.latest(LOCAL_AGENT_INBOX_EVENT_TYPE)
            ?.let { event -> decodeLocalAgentInboxPending(event.data) }
            .orEmpty()
        pendingInputs.restore(restoredInbox)
        val modelProfiles = modelConfiguration.readProfiles()
        val recoveryDecision = agentRunCoordinator.recoveryDecision(sessionId, recovery)
        val recoveryState = foregroundRecoveryCoordinator.restore(
            sessionId, recoveryDecision, modelProfiles, pendingInputs, eventLog,
        )
        val recoveredRunProfile = recoveryState.profile
        val runRecoveryError = recoveryState.error
        val profile = contextComposer.userProfile()
        val restoredBehavior = withContext(Dispatchers.IO) {
            reconcileCharacterBehaviorTuning(
                chatPersonaStore,
                chatPersonaGalleryStore,
                projectedControls.personaId,
                projectedControls.galleryId,
                projectedControls.chatState,
            )
        }
        val restoredGroupChat = if (stored.usageMode == LocalUsageMode.CHAT) {
            projectedControls.groupChat.copy(
                context = projectedControls.groupChat.context.boundDurablePending(eventLog, "group"),
            )
        } else LocalGroupChatState()
        val restoredLineageId = stored.lineageId.ifBlank { stored.id.ifBlank { sessionId } }
        val restoredProjectId = stored.projectId ?: when (stored.conversationMode) {
            LocalConversationMode.INDEPENDENT -> null
            LocalConversationMode.PROJECT,
            LocalConversationMode.CONTINUATION -> LOCAL_PROJECT_ID
        }
        val activeModelProfile = recoveredRunProfile
            ?: modelConfiguration.activeProfile(model, baseUrl, modelProfiles)
        val modelConfigured = activeModelProfile != null && modelGateway.hasCredential(activeModelProfile)
        activeModelProfile?.takeIf { modelConfigured }?.let(modelGateway::activate)
        val restoredModel = activeModelProfile?.model ?: model
        val restoredBaseUrl = activeModelProfile?.baseUrl ?: baseUrl
        _state.value = LocalHarnessState(
            loading = deferReady,
            modelState = LocalModelState(
                configured = modelConfigured,
                model = restoredModel,
                baseUrl = restoredBaseUrl,
                modelSelection = LocalModelSelectionState.restored(
                    modelProfiles, activeModelProfile?.id,
                    preferences.getString(LocalHarnessSettingsCoordinator.KEY_WORKER_PROFILE_ID, null),
                ),
                modelAttempts = LocalAgentRuntimeLimits.normalizeModelAttempts(
                    preferences.getInt(LocalHarnessSettingsCoordinator.KEY_MODEL_ATTEMPTS, DEFAULT_MODEL_ATTEMPTS),
                ),
                imageInputMode = runCatching {
                    LocalImageInputMode.valueOf(
                        preferences.getString(LocalModelSettingsCoordinator.KEY_IMAGE_INPUT_MODE, LocalImageInputMode.AUTO.name)
                            ?: LocalImageInputMode.AUTO.name,
                    )
                }.getOrDefault(LocalImageInputMode.AUTO),
            ),
            mainMaxSteps = LocalAgentRuntimeLimits.normalizeMainSteps(
                preferences.getInt(LocalHarnessSettingsCoordinator.KEY_MAIN_MAX_STEPS, DEFAULT_MAIN_MAX_STEPS),
            ),
            subagentMaxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(
                preferences.getInt(LocalHarnessSettingsCoordinator.KEY_SUBAGENT_MAX_STEPS, DEFAULT_SUBAGENT_MAX_STEPS),
            ),
            workspacePath = workspace.path,
            sessionId = sessionId,
            usageMode = stored.usageMode,
            chat = LocalChatState(
                personaId = projectedControls.personaId,
                galleryId = projectedControls.galleryId,
                galleryStoryId = projectedControls.galleryStoryId,
                gallerySaveSuppressedThrough = projectedControls.gallerySaveSuppressedThrough,
                chatPersona = restoredBehavior.persona,
                chatState = restoredBehavior.chatState,
                chatContext = projectedControls.chatContext.boundDurablePending(eventLog),
                replySuggestions = projectedControls.replySuggestions,
                chatBranches = if (stored.usageMode == LocalUsageMode.CHAT && !projectedControls.groupChat.enabled) {
                    restoreMaterializedChatBranchState(
                        current = projectedControls.chatBranches,
                        activeMessages = restoredTranscript.messages,
                        chatState = restoredBehavior.chatState,
                        replySuggestions = projectedControls.replySuggestions,
                    )
                } else {
                    LocalChatBranchState()
                },
                groupChat = reconcileGroupCharacterBehaviorTuning(
                    restoredGroupChat, chatPersonaStore, chatPersonaGalleryStore,
                ),
            ),
            conversationMode = stored.conversationMode,
            parentSessionId = stored.parentSessionId,
            lineageId = restoredLineageId,
            projectId = restoredProjectId,
            handoffSummary = projectedControls.handoffSummary,
            userRules = profile.customRules,
            autoRecall = profile.autoRecall,
            autoMemory = profile.autoMemory,
            usage = usageTracker.state.value,
            sessions = sessionSummaries(),
            messages = restoredTranscript.messages,
            transcriptIndex = restoredTranscript.index,
            work = com.labteto.dshmobile.local.work.LocalWorkState(
                plan = projectedControls.plan,
                todos = projectedControls.todos,
                goal = projectedControls.goal,
                planMode = projectedControls.planMode,
                jobs = projectExecutionJobs(stored.usageMode, stored.id, jobs.snapshotInfos()),
            ),
            safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(
                loaded?.legacySafeAutoApproval == true,
            ),
            kernel = com.labteto.dshmobile.local.runtime.LocalKernelState(
                queuedInputCount = pendingInputs.size(),
                resources = resourceScheduler.snapshot().toLocalHarnessResourceState(stored.usageMode),
                contextChars = modelHistory.encodedChars,
                contextBudgetChars = currentHistoryBudget().maxHistoryChars,
            ),
            error = runRecoveryError,
        )
        LocalSessionRuntimeRegistry.submitWhenIdle(sessionId) {
            var wroteHistoryCheckpoint = false
            if (_state.value.chat.groupChat.enabled) {
                projectGroupGalleryState(_state.value.chat.groupChat, chatPersonaGalleryStore).failures.forEach { failure ->
                    AppLog.warn(
                        "LocalHarnessEngine",
                        "群聊人物库投影恢复失败 galleryId=${failure.galleryId} detail=${failure.detail}",
                    )
                }
                // Group model history is already restored from its durable checkpoint/event tail.
                // Rebuilding it from the bounded UI transcript would silently discard older context.
                refreshGroupModelSystemPrompt()
                checkpointModelHistory("load/group-system-refresh")
                wroteHistoryCheckpoint = true
            } else if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
                modelHistory.replaceSystem(
                    buildJsonObject { put("role", "system"); put("content", systemPrompt()) },
                )
                updateContextMetrics()
                checkpointModelHistory("load/system-refresh")
                wroteHistoryCheckpoint = true
            } else if (recovery.repaired || restoredHistory.checkpointRecommended) {
                checkpointModelHistory(
                    if (restoredHistory.usedLegacyFallback) "load/legacy-history-migration"
                    else "load/event-replay",
                )
                wroteHistoryCheckpoint = true
            }
            if (restoredHistory.usedLegacyFallback && !wroteHistoryCheckpoint) {
                checkpointModelHistory("load/legacy-history-migration")
            }
            if (
                restoredHistory.usedLegacyFallback ||
                restoredTranscript.needsPersist ||
                restoredBehavior.changed
            ) {
                // Materialize migrated/replayed projections so later restarts only fold the new tail.
                persist()
            }
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

    private fun checkpointModelHistoryAtTurnBoundary(
        reason: String,
        binding: LocalWorkRunBinding? = null,
    ) {
        if (binding != null || _state.value.usageMode == LocalUsageMode.WORK) {
            compactHistoryIfNeeded(binding = binding)
        }
        if (binding != null) {
            binding.runHandle.turnsSinceModelHistoryCheckpoint += 1
            if (binding.runHandle.turnsSinceModelHistoryCheckpoint >= MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL) {
                checkpointModelHistory(reason, binding)
            }
        } else {
            runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint += 1
            if (runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint >= MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL) {
                checkpointModelHistory(reason)
            }
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

    private suspend fun persistNow() {
        sessionCoordinator.writeNow(persistenceSnapshot())
    }


    private fun sessionFileFor(id: String) = File(sessionsRoot, "$id.json")

    private fun eventLogFor(id: String) = eventLogRegistry.get(id)

    private fun sessionSummaries(): List<LocalSessionSummary> =
        localSessionSummariesOrEmpty(sessionCoordinator) { future ->
            _state.update { it.copy(error = future.message) }
        }
}
