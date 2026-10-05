package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.local.LocalSessionCoordinator
import com.labteto.dshmobile.local.LocalSessionRepository
import com.labteto.dshmobile.local.session.LocalSessionDomainCodec
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.session.LocalSessionStorageManager
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
    domainCodecs: Set<@JvmSuppressWildcards LocalSessionDomainCodec>,
) {
    private val sessionsRoot = File(context.filesDir, "local-harness/sessions").apply { mkdirs() }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                runtimeStateStore.projection.publishError(
                    throwable.message?.takeIf(String::isNotBlank)
                        ?: "会话存储后台任务失败：${throwable::class.java.simpleName}",
                )
            },
    )

    private val repository: LocalSessionRepository
    private val storageManager = LocalSessionStorageManager(sessionsRoot, json)
    internal val coordinator: LocalSessionCoordinator

    init {
        val orderedDomainCodecs = domainCodecs.sortedBy(LocalSessionDomainCodec::id)
        repository = LocalSessionRepository(
            root = sessionsRoot,
            json = json,
            scope = scope,
            onWritten = ::publishSummaries,
            onError = { error ->
                runtimeStateStore.projection.publishError(error.message ?: "会话写入失败")
            },
            domainCodecs = orderedDomainCodecs,
        )
        coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = eventLogs::get,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
            domainCodecs = orderedDomainCodecs,
        )
    }

    internal fun enqueueCurrentSnapshot(expectedSessionId: String): Boolean {
        val eventLog = eventLogs.get(expectedSessionId)
        val controlProjectedThroughSequence = eventLog.latestSequence()
        val transcriptProjectedThroughSequence =
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor
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

    internal suspend fun storageStatus(): LocalSessionStorageStatus =
        withContext(Dispatchers.IO) { storageManager.status() }

    internal suspend fun compactStorage(): LocalSessionStorageStatus =
        withContext(Dispatchers.IO) { storageManager.compactAll() }

    internal suspend fun exportStorage(output: OutputStream): Long =
        withContext(Dispatchers.IO) { storageManager.exportAll(output) }

    internal suspend fun writeCurrentSnapshotNow(expectedSessionId: String): Boolean {
        val eventLog = eventLogs.get(expectedSessionId)
        val controlProjectedThroughSequence = eventLog.latestSequence()
        val transcriptProjectedThroughSequence =
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor
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
                runtimeStateStore.projection.publishSessions(summaries)
            }
            .onFailure { error ->
                runtimeStateStore.projection.publishError(error.message ?: "会话摘要读取失败")
            }
    }
}
