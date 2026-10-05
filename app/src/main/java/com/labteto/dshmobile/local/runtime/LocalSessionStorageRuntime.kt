package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.local.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.LocalSessionCoordinator
import com.labteto.dshmobile.local.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.LocalSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Shared owner of durable Session snapshot storage.
 *
 * EventLog remains the durable fact stream through [LocalSessionEventLogRegistry]. This runtime owns
 * only the snapshot repository/coordinator and publishes derived session summaries into the shared
 * runtime state. Features can persist their own state without routing through LocalHarnessEngine.
 */
@Singleton
class LocalSessionStorageRuntime @Inject internal constructor(
    @ApplicationContext context: Context,
    json: Json,
    private val runtimeStateStore: LocalRuntimeStateStore,
    internal val eventLogs: LocalSessionEventLogRegistry,
) {
    private val sessionsRoot = File(context.filesDir, "local-harness/sessions").apply { mkdirs() }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                runtimeStateStore.mutableState.update { current ->
                    current.copy(
                        error = throwable.message?.takeIf(String::isNotBlank)
                            ?: "会话存储后台任务失败：${throwable::class.java.simpleName}",
                    )
                }
            },
    )

    private val repository: LocalSessionRepository
    internal val coordinator: LocalSessionCoordinator

    init {
        repository = LocalSessionRepository(
            root = sessionsRoot,
            json = json,
            scope = scope,
            onWritten = ::publishSummaries,
            onError = { error ->
                runtimeStateStore.mutableState.update {
                    it.copy(error = error.message ?: "会话写入失败")
                }
            },
        )
        coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = eventLogs::get,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
        )
    }

    internal fun enqueueCurrentSnapshot(expectedSessionId: String): Boolean {
        val eventLog = eventLogs.get(expectedSessionId)
        val controlProjectedThroughSequence = eventLog.latestSequence()
        val transcriptProjectedThroughSequence =
            runtimeStateStore.foregroundTranscriptProjectionCursor
        val state = runtimeStateStore.state.value
        if (state.sessionId != expectedSessionId) return false
        coordinator.enqueue(
            coordinator.snapshot(
                sessionId = expectedSessionId,
                state = state,
                controlProjectedThroughSequence = controlProjectedThroughSequence,
                transcriptProjectedThroughSequence = transcriptProjectedThroughSequence,
            ),
        )
        return true
    }

    internal suspend fun writeCurrentSnapshotNow(expectedSessionId: String): Boolean {
        val eventLog = eventLogs.get(expectedSessionId)
        val controlProjectedThroughSequence = eventLog.latestSequence()
        val transcriptProjectedThroughSequence =
            runtimeStateStore.foregroundTranscriptProjectionCursor
        val state = runtimeStateStore.state.value
        if (state.sessionId != expectedSessionId) return false
        coordinator.writeNow(
            coordinator.snapshot(
                sessionId = expectedSessionId,
                state = state,
                controlProjectedThroughSequence = controlProjectedThroughSequence,
                transcriptProjectedThroughSequence = transcriptProjectedThroughSequence,
            ),
        )
        return true
    }

    private fun publishSummaries() {
        runCatching { coordinator.summaries() }
            .onSuccess { summaries ->
                runtimeStateStore.mutableState.update { it.copy(sessions = summaries) }
            }
            .onFailure { error ->
                runtimeStateStore.mutableState.update {
                    it.copy(error = error.message ?: "会话摘要读取失败")
                }
            }
    }
}
