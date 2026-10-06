package com.labteto.dshmobile.local

import android.content.Context
import com.labteto.dshmobile.harness.session.ConversationHandoffBuilder
import com.labteto.dshmobile.local.chat.LocalChatComposition
import com.labteto.dshmobile.local.chat.LocalChatPersistence
import com.labteto.dshmobile.local.chat.LocalChatSessionLifecyclePlanner
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.runtime.LOCAL_PROJECT_ID
import com.labteto.dshmobile.local.runtime.MAX_HANDOFF_CHARS
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalSessionDomainCreateSpec
import com.labteto.dshmobile.local.session.LocalSessionDomainModeCommand
import com.labteto.dshmobile.local.session.LocalSessionLifecyclePort
import com.labteto.dshmobile.local.work.LocalWorkComposition
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** App composition owner for Session lifecycle transactions across Feature boundaries. */
@Singleton
internal class LocalSessionComposition @Inject constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    sessionStorage: LocalSessionStorageRuntime,
    chatPersistence: LocalChatPersistence,
    private val chatComposition: LocalChatComposition,
    approvalPreferences: LocalApprovalPreferences,
    private val workRuns: LocalWorkRunRegistry,
    private val workComposition: LocalWorkComposition,
    tools: LocalToolCompositionRoot,
    loader: LocalForegroundSessionLoader,
    wake: LocalForegroundTurnWakeCoordinator,
    memoryStore: MemoryStore,
) : LocalSessionLifecyclePort {
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val sessionsRoot = File(context.filesDir, "local-harness/sessions").apply { mkdirs() }
    private val transitionMutex = Mutex()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            runtimeStateStore.projection.publishError(
                error.message?.takeIf(String::isNotBlank)
                    ?: "会话生命周期后台任务失败：${error::class.java.simpleName}",
            )
        },
    )
    private val coordinator = LocalSessionLifecycleCoordinator(
        scope = scope,
        state = runtimeStateStore.projection,
        transitionMutex = transitionMutex,
        jobs = runtimeStateStore.jobManager,
        sessionCoordinator = sessionStorage.coordinator,
        chatSessionLifecycle = LocalChatSessionLifecyclePlanner(chatPersistence.personaStore),
        approvalPreferences = approvalPreferences,
        resourceScheduler = runtimeStateStore.resourceScheduler,
        handoffBuilder = ConversationHandoffBuilder(MAX_HANDOFF_CHARS),
        toolOutputStore = tools.toolOutputStore,
        sessionsRoot = sessionsRoot,
        conversationFilesCoordinator = sessionStorage.files.coordinator,
        memoryStore = memoryStore,
        diaryStore = chatPersistence.diaryStore,
        currentSessionId = runtimeStateStore::currentSessionId,
        activateSession = { id, transcriptCursor ->
            runtimeStateStore.activateSession(id)
            preferences.edit()
                .putString(LocalSessionLifecycleCoordinator.SESSION_ID_PREFERENCE, id)
                .apply()
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor = transcriptCursor
        },
        beginTransition = {
            runtimeStateStore.beginSessionTransition().also { started ->
                if (started) chatComposition.cancelPostTurn()
            }
        },
        endTransition = runtimeStateStore::endSessionTransition,
        navigationBusy = {
            val handle = runtimeStateStore.foregroundRunHandle
            synchronized(handle.lock) {
                localSessionNavigationBusy(
                    sessionTransitioning = runtimeStateStore.sessionTransitioning,
                    visibleRunActive = handle.job?.isCompleted == false,
                )
            }
        },
        cancelActiveRunAndJoin = {
            val log = sessionStorage.eventLogs.get(runtimeStateStore.currentSessionId)
            runtimeStateStore.cancelForegroundRunAndJoin(log)
        },
        cancelWorkRunsAndJoin = { sessionIds -> cancelWorkRunsAndJoin(sessionIds) },
        resetModelHistory = { runtimeStateStore.foregroundRunHandle.modelHistory.reset() },
        persist = { loader.persistCurrent() },
        loadSession = loader::loadSession,
        restartInterruptedSafeJobs = workComposition::schedulePersistentRecovery,
        startNextQueuedTurnIfIdle = wake::startNextIfIdle,
        sessionSummaries = loader::summaries,
        beforeEventLogsDeleted = sessionStorage.eventLogs::clearAndEvict,
        localProjectId = LOCAL_PROJECT_ID,
    )

    override fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec?,
    ): Boolean = coordinator.createSession(mode, usageMode, domainSpec)

    override fun switchDomainMode(command: LocalSessionDomainModeCommand) {
        coordinator.switchDomainMode(command)
    }

    override fun switchUsageMode(mode: LocalUsageMode) {
        coordinator.switchUsageMode(mode)
    }

    override fun switchSession(sessionId: String): Boolean = coordinator.switchSession(sessionId)

    override suspend fun deleteSessions(ids: Set<String>): Int = coordinator.deleteSessions(ids)

    private suspend fun cancelWorkRunsAndJoin(sessionIds: Set<String>) {
        val cancelled = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            workRuns.detachAll(sessionIds)
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
}
