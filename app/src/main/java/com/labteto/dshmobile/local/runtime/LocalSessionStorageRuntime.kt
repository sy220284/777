package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.local.session.LocalSessionCoordinator
import com.labteto.dshmobile.local.session.SqliteSessionSnapshotStore
import com.labteto.dshmobile.local.session.LocalLegacySqliteMigration
import com.labteto.dshmobile.local.session.LocalSessionRepository
import com.labteto.dshmobile.local.session.LocalSessionDomainCodec
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.session.LocalCurrentSessionSnapshotProvider
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalPendingSessionRegistry
import com.labteto.dshmobile.local.session.localSessionSnapshotBoundary
import com.labteto.dshmobile.local.session.LocalSessionFilesRuntime
import com.labteto.dshmobile.local.session.SqliteSessionStorageManager
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
 * runtime state. Features can persist their own state without routing through the Runtime Kernel.
 */
@Singleton
class LocalSessionStorageRuntime @Inject internal constructor(
    @ApplicationContext context: Context,
    json: Json,
    private val runtimeStateStore: LocalRuntimeStateStore,
    internal val eventLogs: LocalSessionEventLogRegistry,
    internal val files: LocalSessionFilesRuntime,
    domainCodecs: Set<@JvmSuppressWildcards LocalSessionDomainCodec>,
    private val currentSnapshotProvider: LocalCurrentSessionSnapshotProvider,
    private val pendingSessions: LocalPendingSessionRegistry,
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

    private val orderedCodecs = domainCodecs.sortedBy(LocalSessionDomainCodec::id)
    private val repository: LocalSessionRepository
    private val sqliteSnapshots = SqliteSessionSnapshotStore(context, json, domainCodecs = orderedCodecs)
    private val storageManager = SqliteSessionStorageManager(
        context, sqliteSnapshots, requireNotNull(eventLogs.sqliteDatabase),
    )
    internal val coordinator: LocalSessionCoordinator
    internal val projectionRegistry = SessionProjectionRegistry()
    internal val agentRunCoordinator = LocalAgentRunCoordinator(eventLogFor = eventLogs::get)

    init {
        // Apply staged imports before any repository or event reader opens the session directory.
        try {
            storageManager.applyPendingRestore()
        } catch (failure: Exception) {
            runtimeStateStore.projection.publishError(
                "会话备份恢复未全部完成：${failure.message ?: "请检查本地存储"}")
            // Never expose a possibly half-installed pair of SQLite databases.
            throw IllegalStateException("SQLite 恢复未完成，禁止启动不一致会话", failure)
        }
        // Before any user Session is enumerated or opened, adopt released main's
        // JSON snapshots and rotated/compressed JSONL EventLogs. Never present an
        // empty SQLite editor while private historical files are still unimported.
        try {
            LocalLegacySqliteMigration(
                root = File(context.filesDir, "local-harness"),
                sessionsRoot = sessionsRoot,
                json = json,
                snapshots = sqliteSnapshots,
                events = requireNotNull(eventLogs.sqliteDatabase),
            ).migrate(runtimeStateStore.currentSessionId)
        } catch (failure: Exception) {
            runtimeStateStore.projection.publishError(
                "旧会话迁移未完成，原历史仍保留：${failure.message ?: "请检查存储"}",
            )
            throw IllegalStateException("旧会话迁移未完成，禁止开启与历史分叉的新会话", failure)
        }
        repository = LocalSessionRepository(
            root = sessionsRoot,
            json = json,
            scope = scope,
            onWritten = ::publishSummaries,
            onError = { error ->
                runtimeStateStore.projection.publishError(error.message ?: "会话写入失败")
            },
            domainCodecs = orderedCodecs,
            snapshotStore = sqliteSnapshots,
        )
        coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = eventLogs::get,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
            domainCodecs = orderedCodecs,
        )
    }

    internal fun previewMessages(sessionId: String, limit: Int = 12): List<com.labteto.dshmobile.local.session.LocalHarnessMessage> =
        repository.preview(sessionId, limit)

    internal fun enqueueSnapshot(snapshot: LocalHarnessSession) {
        coordinator.enqueue(snapshot)
    }

    internal fun currentSnapshot(expectedSessionId: String): LocalHarnessSession? {
        val boundary = localSessionSnapshotBoundary(
            eventLog = eventLogs.get(expectedSessionId),
            transcriptProjectionCursor = runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor,
        )
        return currentSnapshotProvider.snapshot(
            expectedSessionId = expectedSessionId,
            boundary = boundary,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
        )
    }

    internal fun enqueueCurrentSnapshot(expectedSessionId: String): Boolean {
        val snapshot = currentSnapshot(expectedSessionId) ?: return false
        // A new editor needs a stable runtime identity for its encrypted draft, but not a
        // durable Session catalog entry. Existing historical sessions are never discarded here.
        if (com.labteto.dshmobile.local.session.isUnstartedSessionSnapshot(snapshot, orderedCodecs) &&
            coordinator.read(expectedSessionId) == null) return true
        coordinator.enqueue(snapshot)
        return true
    }

    internal fun storageDiagnosticSummary(): String = storageManager.diagnosticSummary()

    internal suspend fun storageStatus(): LocalSessionStorageStatus =
        withContext(Dispatchers.IO) { storageManager.status() }

    internal suspend fun compactStorage(): LocalSessionStorageStatus =
        withContext(Dispatchers.IO) { storageManager.compactAll() }

    internal suspend fun stageRestoreStorage(archive: ByteArray): Int =
        withContext(Dispatchers.IO) { storageManager.stageRestore(archive) }

    internal suspend fun exportStorage(output: OutputStream): Long =
        withContext(Dispatchers.IO) { storageManager.exportAll(output) }

    internal suspend fun writeCurrentSnapshotNow(expectedSessionId: String): Boolean {
        val snapshot = currentSnapshot(expectedSessionId) ?: return false
        coordinator.writeNow(snapshot)
        return true
    }

    private fun publishSummaries() {
        runCatching { coordinator.summaries() }
            .onSuccess { summaries ->
                pendingSessions.forget(summaries.filterNot { it.blank }.map { it.id }.toSet())
                runtimeStateStore.projection.publishSessions(summaries)
            }
            .onFailure { error ->
                runtimeStateStore.projection.publishError(error.message ?: "会话摘要读取失败")
            }
    }
}
