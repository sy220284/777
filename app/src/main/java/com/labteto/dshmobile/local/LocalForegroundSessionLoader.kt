package com.labteto.dshmobile.local

import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.model.LocalModelSettingsCoordinator
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.model.migrateOfficialClaudeModel
import com.labteto.dshmobile.local.model.workSystemPrompt
import com.labteto.dshmobile.local.runtime.DEFAULT_BASE_URL
import com.labteto.dshmobile.local.runtime.DEFAULT_MODEL
import com.labteto.dshmobile.local.runtime.DEFAULT_MODEL_ATTEMPTS
import com.labteto.dshmobile.local.runtime.LOCAL_PROJECT_ID
import com.labteto.dshmobile.local.work.LocalForegroundRecoveryCoordinator
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.PROJECTION_BASELINE_EVENT
import com.labteto.dshmobile.local.runtime.toLocalHarnessResourceState
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.local.session.projectionReplayCursor
import com.labteto.dshmobile.local.settings.LocalHarnessSettingsCoordinator
import com.labteto.dshmobile.local.work.LocalWorkRecoveryContextPolicy
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Cross-Feature foreground Session restore/composition owner extracted from the legacy Engine. */
@Singleton
internal class LocalForegroundSessionLoader @Inject constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val workRuns: LocalWorkRunRegistry,
    private val modelGateway: LocalModelGateway,
    private val modelConfiguration: LocalModelConfigurationCoordinator,
    private val usageTracker: DeepSeekUsageTracker,
    private val contextComposer: ContextComposer,
    private val chatRestore: com.labteto.dshmobile.local.chat.LocalChatSessionRestorer,
    private val approvalPreferences: LocalApprovalPreferences,
) {
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val workspace get() = sessionStorage.files.workspace
    private val coordinator get() = sessionStorage.coordinator
    private val agentRunCoordinator get() = sessionStorage.agentRunCoordinator
    private val modelHistory get() = runtimeStateStore.foregroundRunHandle.modelHistory
    private val pendingInputs: AgentInputQueue get() = runtimeStateStore.foregroundRunHandle.pendingInputs
    private val eventLog get() = sessionStorage.eventLogs.get(runtimeStateStore.currentSessionId)
    private val codec = ModelHistoryCheckpointCodec()
    private val recovery by lazy {
        LocalForegroundRecoveryCoordinator(agentRunCoordinator, modelGateway::hasCredential)
    }

    internal suspend fun loadStartup(deferReady: Boolean = false) {
        val storedModel = preferences.getString(com.labteto.dshmobile.local.runtime.KEY_MODEL, DEFAULT_MODEL)
            ?: DEFAULT_MODEL
        val baseUrl = preferences.getString(com.labteto.dshmobile.local.runtime.KEY_BASE_URL, DEFAULT_BASE_URL)
            ?: DEFAULT_BASE_URL
        val model = migrateOfficialClaudeModel(modelConfiguration.normalizeModel(storedModel), baseUrl)
        if (model != storedModel) {
            preferences.edit().putString(com.labteto.dshmobile.local.runtime.KEY_MODEL, model).apply()
        }
        modelConfiguration.prepareStartup(model, baseUrl)
        loadSession(runtimeStateStore.currentSessionId, model, baseUrl, deferReady)
    }

    internal suspend fun loadSession(
        sessionId: String,
        model: String = preferences.getString(com.labteto.dshmobile.local.runtime.KEY_MODEL, DEFAULT_MODEL)
            ?: DEFAULT_MODEL,
        baseUrl: String = preferences.getString(com.labteto.dshmobile.local.runtime.KEY_BASE_URL, DEFAULT_BASE_URL)
            ?: DEFAULT_BASE_URL,
        deferReady: Boolean = false,
    ) {
        workRuns.live(sessionId)?.let { liveBinding ->
            pendingInputs.clear()
            syncVisibleWorkRun(sessionId, liveBinding)
            return
        }

        val log = sessionStorage.eventLogs.get(sessionId)
        val loaded = try {
            coordinator.readWithLegacyApproval(sessionId)
        } catch (future: FutureSessionVersionException) {
            runtimeStateStore.projection.update {
                it.copy(loading = false, sessionId = sessionId, error = future.message)
            }
            return
        }
        val repaired = log.repairInterruptedTail()
        chatRestore.repairTimeline(log)
        val stored = loaded?.session ?: LocalHarnessSession(id = sessionId)
        val legacyProjectionBaseline = if (stored.controlProjectedThroughSequence == null && loaded != null) {
            log.latest(PROJECTION_BASELINE_EVENT)?.sequence ?: log.append(
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
            events = log.snapshotAfter(projectionCursor),
            sequenceExclusive = projectionCursor,
        )
        val restoredTranscript = coordinator.restoreTranscript(
            stored = stored,
            persistedSnapshotExists = loaded != null,
        )
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
            restoredTranscript.projectedThroughSequence
        val restoredHistory = restoreLocalModelHistory(
            events = loadModelHistoryReplayEvents(log, codec, stored.legacyModelHistory),
            legacyFallback = stored.legacyModelHistory,
            codec = codec,
        )
        modelHistory.reset(restoredHistory.messages)
        buildRecoveredToolResultMessages(modelHistory.snapshot(), repaired).forEach(modelHistory::append)
        val restoredInbox = log.latest(LOCAL_AGENT_INBOX_EVENT_TYPE)
            ?.let { decodeLocalAgentInboxPending(it.data) }
            .orEmpty()
        pendingInputs.restore(restoredInbox)

        val modelProfiles = modelConfiguration.readProfiles()
        val recoveryDecision = agentRunCoordinator.recoveryDecision(
            sessionId = sessionId,
            repair = repaired,
            contextPolicy = LocalWorkRecoveryContextPolicy,
        )
        val recoveryState = recovery.restore(
            sessionId,
            recoveryDecision,
            modelProfiles,
            pendingInputs,
            log,
        )
        val profile = contextComposer.userProfile()
        val restoredChat = chatRestore.restore(
            usageMode = stored.usageMode,
            controls = projectedControls,
            messages = restoredTranscript.messages,
            log = log,
        )
        val restoredLineageId = stored.lineageId.ifBlank { stored.id.ifBlank { sessionId } }
        val restoredProjectId = stored.projectId ?: when (stored.conversationMode) {
            LocalConversationMode.INDEPENDENT -> null
            LocalConversationMode.PROJECT,
            LocalConversationMode.CONTINUATION -> LOCAL_PROJECT_ID
        }
        val activeModelProfile = recoveryState.profile
            ?: modelConfiguration.activeProfile(model, baseUrl, modelProfiles)
        val modelConfigured = activeModelProfile != null && modelGateway.hasCredential(activeModelProfile)
        activeModelProfile?.takeIf { modelConfigured }?.let(modelGateway::activate)
        val restoredModel = activeModelProfile?.model ?: model
        val restoredBaseUrl = activeModelProfile?.baseUrl ?: baseUrl
        val resources = runtimeStateStore.resourceSnapshot()
        val restoredState = LocalHarnessState(
            loading = deferReady,
            modelState = LocalModelState(
                configured = modelConfigured,
                model = restoredModel,
                baseUrl = restoredBaseUrl,
                modelSelection = LocalModelSelectionState.restored(
                    modelProfiles,
                    activeModelProfile?.id,
                    preferences.getString(LocalHarnessSettingsCoordinator.KEY_WORKER_PROFILE_ID, null),
                ),
                modelAttempts = LocalAgentRuntimeLimits.normalizeModelAttempts(
                    preferences.getInt(LocalHarnessSettingsCoordinator.KEY_MODEL_ATTEMPTS, DEFAULT_MODEL_ATTEMPTS),
                ),
                imageInputMode = runCatching {
                    LocalImageInputMode.valueOf(
                        preferences.getString(
                            LocalModelSettingsCoordinator.KEY_IMAGE_INPUT_MODE,
                            LocalImageInputMode.AUTO.name,
                        ) ?: LocalImageInputMode.AUTO.name,
                    )
                }.getOrDefault(LocalImageInputMode.AUTO),
            ),
            mainMaxSteps = LocalAgentRuntimeLimits.normalizeMainSteps(
                preferences.getInt(LocalHarnessSettingsCoordinator.KEY_MAIN_MAX_STEPS, com.labteto.dshmobile.local.runtime.DEFAULT_MAIN_MAX_STEPS),
            ),
            subagentMaxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(
                preferences.getInt(LocalHarnessSettingsCoordinator.KEY_SUBAGENT_MAX_STEPS, com.labteto.dshmobile.local.runtime.DEFAULT_SUBAGENT_MAX_STEPS),
            ),
            workspacePath = workspace.path,
            sessionId = sessionId,
            usageMode = stored.usageMode,
            chat = restoredChat.chat,
            conversationMode = stored.conversationMode,
            parentSessionId = stored.parentSessionId,
            lineageId = restoredLineageId,
            projectId = restoredProjectId,
            handoffSummary = projectedControls.handoffSummary,
            userRules = profile.customRules,
            autoRecall = profile.autoRecall,
            autoMemory = profile.autoMemory,
            usage = usageTracker.state.value,
            sessions = summaries(),
            messages = restoredTranscript.messages,
            transcriptIndex = restoredTranscript.index,
            work = com.labteto.dshmobile.local.work.LocalWorkSessionLifecyclePlanner.restoreState(
                projectedControls, stored.usageMode, stored.id, runtimeStateStore.jobManager.snapshotInfos(),
            ),
            safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(
                loaded?.legacySafeAutoApproval == true,
            ),
            kernel = LocalKernelState(
                queuedInputCount = pendingInputs.size(),
                resources = resources.toLocalHarnessResourceState(stored.usageMode),
                contextChars = modelHistory.encodedChars,
                contextBudgetChars = runtimeStateStore.historyBudgetFor(
                    runtimeStateStore.state.value,
                    resources,
                ).maxHistoryChars,
            ),
            error = recoveryState.error,
        )
        runtimeStateStore.projection.update { restoredState }

        LocalSessionRuntimeRegistry.submitWhenIdle(sessionId) {
            var wroteHistoryCheckpoint = false
            val state = runtimeStateStore.state.value
            if (state.chat.groupChat.enabled) {
                chatRestore.restoreGalleryProjection(state.chat)
                refreshSystemPrompt()
                checkpointModelHistory("load/group-system-refresh")
                wroteHistoryCheckpoint = true
            } else if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
                modelHistory.replaceSystem(
                    buildJsonObject { put("role", "system"); put("content", systemPrompt()) },
                )
                updateContextMetrics()
                checkpointModelHistory("load/system-refresh")
                wroteHistoryCheckpoint = true
            } else if (repaired.repaired || restoredHistory.checkpointRecommended) {
                checkpointModelHistory(
                    if (restoredHistory.usedLegacyFallback) "load/legacy-history-migration"
                    else "load/event-replay",
                )
                wroteHistoryCheckpoint = true
            }
            if (restoredHistory.usedLegacyFallback && !wroteHistoryCheckpoint) {
                checkpointModelHistory("load/legacy-history-migration")
            }
            if (restoredHistory.usedLegacyFallback || restoredTranscript.needsPersist || restoredChat.needsPersist) {
                persistCurrent()
            }
        }
    }

    internal fun syncVisibleWorkRun(
        sessionId: String,
        ownedBinding: LocalWorkRunBinding? = null,
    ) {
        val binding = ownedBinding ?: workRuns.live(sessionId) ?: return
        val liveState = binding.aggregateSnapshot()
        liveState.modelState.modelSelection.activeProfile
            ?.takeIf { liveState.modelState.configured }
            ?.let(modelGateway::activate)
        val resources = runtimeStateStore.resourceSnapshot()
        val visibleState = liveState.copy(
            loading = runtimeStateStore.state.value.loading,
            usage = usageTracker.state.value,
            sessions = summaries(),
            kernel = liveState.kernel.copy(
                resources = resources.toLocalHarnessResourceState(liveState.usageMode),
                contextBudgetChars = runtimeStateStore.historyBudgetFor(liveState, resources).maxHistoryChars,
            ),
        )
        runtimeStateStore.projection.update { visibleState }
        modelHistory.reset(binding.runHandle.modelHistory.snapshot())
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
            binding.runHandle.transcriptProjectionCursor
    }

    internal fun persistCurrent(): Boolean =
        sessionStorage.enqueueCurrentSnapshot(runtimeStateStore.currentSessionId)

    internal fun summaries(): List<LocalSessionSummary> = try {
        coordinator.summaries()
    } catch (future: FutureSessionVersionException) {
        runtimeStateStore.projection.update { it.copy(error = future.message) }
        emptyList()
    }

    private fun checkpointModelHistory(reason: String) {
        eventLog.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            codec.encode(durableModelHistorySnapshot(modelHistory.snapshot()), reason),
        )
        runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint = 0
    }

    private fun refreshSystemPrompt() {
        val system = buildJsonObject { put("role", "system"); put("content", systemPrompt()) }
        if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            modelHistory.replaceSystem(system)
        } else {
            modelHistory.prepend(system)
        }
        updateContextMetrics()
    }

    private fun systemPrompt(): String {
        val snapshot = runtimeStateStore.state.value
        return when {
            snapshot.usageMode != LocalUsageMode.CHAT -> workSystemPrompt(workspace.path, snapshot.work.planMode)
            else -> chatRestore.systemPrompt(snapshot.chat.groupChat.enabled)
        }
    }

    private fun updateContextMetrics() {
        val snapshot = runtimeStateStore.state.value
        val resources = runtimeStateStore.resourceSnapshot()
        runtimeStateStore.projection.update {
            it.copy(
                kernel = it.kernel.copy(
                    contextChars = modelHistory.encodedChars,
                    contextBudgetChars = runtimeStateStore.historyBudgetFor(snapshot, resources).maxHistoryChars,
                ),
            )
        }
    }
}
