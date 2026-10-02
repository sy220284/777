package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class LocalSessionRead(
    val session: LocalHarnessSession,
    val legacySafeAutoApproval: Boolean,
)

internal fun legacySafeAutoApproval(payload: JsonObject): Boolean =
    payload["autoApproveMutations"]?.jsonPrimitive?.booleanOrNull == true

/** Owns durable session encoding and coalesces queued snapshots per session. */
internal class LocalSessionRepository(
    root: File,
    private val json: Json,
    scope: CoroutineScope,
    private val onWritten: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val sessionsRoot = root
    private val catalogStore = VersionedSessionStore(root, json)
    private val sessionStores = ConcurrentHashMap<String, VersionedSessionStore>()
    private val sessionLocks = Array(SESSION_LOCK_STRIPES) { Any() }
    private val summaryIndex = LocalSessionSummaryIndex(root, json)
    private val lock = Any()
    private val deletedIds = mutableSetOf<String>()
    private val pending = linkedMapOf<String, LocalHarnessSession>()
    /**
     * Newest accepted snapshot that is not yet known to be durable.
     *
     * Reads prefer this snapshot so switching away and immediately reopening a session cannot
     * observe an older disk image while the coalescing writer is still catching up.
     */
    private val latestSnapshots = mutableMapOf<String, LocalHarnessSession>()
    private val summaryCache = linkedMapOf<String, LocalSessionSummary>()
    private var summariesLoaded = false
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var consecutiveFailures = 0
            for (ignored in wakeups) {
                while (true) {
                    val snapshot = synchronized(lock) {
                        pending.keys.firstOrNull()?.let { pending.remove(it) }
                    } ?: break
                    try {
                        val sourceModifiedAt = synchronized(sessionLock(snapshot.id)) {
                            val stillLatest = synchronized(lock) {
                                snapshot.id !in deletedIds && latestSnapshots[snapshot.id] === snapshot
                            }
                            if (!stillLatest) {
                                null
                            } else {
                                val store = storeFor(snapshot.id)
                                store.write(
                                    snapshot.id,
                                    json.encodeToJsonElement(LocalHarnessSession.serializer(), snapshot).jsonObject,
                                    updatedAt = snapshot.updatedAt,
                                )
                                store.lastModified(snapshot.id)
                            }
                        }
                        if (sourceModifiedAt != null) {
                            persistSummaryIndex(snapshot, sourceModifiedAt)
                            synchronized(lock) {
                                if (snapshot.id !in deletedIds) {
                                    cacheSummaryLocked(snapshot.toSummary())
                                    if (latestSnapshots[snapshot.id] === snapshot) {
                                        latestSnapshots.remove(snapshot.id)
                                    }
                                }
                            }
                            onWritten()
                        }
                        consecutiveFailures = 0
                    } catch (cancelled: CancellationException) {
                        synchronized(lock) {
                            if (snapshot.id !in deletedIds && latestSnapshots[snapshot.id] === snapshot) {
                                pending.putIfAbsent(snapshot.id, snapshot)
                            }
                        }
                        throw cancelled
                    } catch (error: Exception) {
                        // Keep the newest snapshot for this session. A failed disk write must not
                        // silently remove the only queued copy or spin at full speed on a bad disk.
                        synchronized(lock) {
                            if (snapshot.id !in deletedIds && latestSnapshots[snapshot.id] === snapshot) {
                                pending.putIfAbsent(snapshot.id, snapshot)
                            }
                        }
                        onError(error)
                        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                        delay((1_000L shl (consecutiveFailures - 1)).coerceAtMost(30_000L))
                    }
                }
            }
        }
    }

    fun enqueue(snapshot: LocalHarnessSession) {
        synchronized(lock) {
            if (snapshot.id in deletedIds) return
            latestSnapshots[snapshot.id] = snapshot
            pending[snapshot.id] = snapshot
        }
        wakeups.trySend(Unit)
    }

    /**
     * Persist one explicit user save before reporting success.
     *
     * The newest marker also prevents an older snapshot that was already dequeued from overwriting
     * this save after it acquires the storage lock.
     */
    suspend fun writeNow(snapshot: LocalHarnessSession) = withContext(Dispatchers.IO) {
        val previousLatest = synchronized(lock) {
            check(snapshot.id !in deletedIds) { "会话已删除，无法保存" }
            latestSnapshots.put(snapshot.id, snapshot)
        }
        try {
            val sourceModifiedAt = synchronized(sessionLock(snapshot.id)) {
                check(snapshot.id !in deletedIds) { "会话已删除，无法保存" }
                val store = storeFor(snapshot.id)
                store.write(
                    snapshot.id,
                    json.encodeToJsonElement(LocalHarnessSession.serializer(), snapshot).jsonObject,
                    updatedAt = snapshot.updatedAt,
                )
                requireNotNull(store.lastModified(snapshot.id)) { "会话写入后无法读取文件代际：${snapshot.id}" }
            }
            persistSummaryIndex(snapshot, sourceModifiedAt)
            synchronized(lock) {
                if (snapshot.id !in deletedIds) {
                    cacheSummaryLocked(snapshot.toSummary())
                    if (latestSnapshots[snapshot.id] === snapshot) {
                        latestSnapshots.remove(snapshot.id)
                        pending.remove(snapshot.id)
                    }
                }
            }
            onWritten()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (latestSnapshots[snapshot.id] === snapshot) {
                    if (previousLatest != null) {
                        latestSnapshots[snapshot.id] = previousLatest
                        pending[snapshot.id] = previousLatest
                        wakeups.trySend(Unit)
                    } else {
                        latestSnapshots.remove(snapshot.id)
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) {
                if (latestSnapshots[snapshot.id] === snapshot) {
                    if (previousLatest != null) {
                        latestSnapshots[snapshot.id] = previousLatest
                        pending[snapshot.id] = previousLatest
                        wakeups.trySend(Unit)
                    } else {
                        latestSnapshots.remove(snapshot.id)
                    }
                }
            }
            onError(error)
            throw error
        }
    }

    /** Block a queued or in-flight snapshot from recreating a deleted session. */
    fun delete(id: String): Boolean = synchronized(sessionLock(id)) {
        // Mark the tombstone before touching disk so a writer waiting on this same stripe cannot
        // pass an earlier "still latest" decision after deletion completes.
        synchronized(lock) {
            deletedIds += id
            pending.remove(id)
            latestSnapshots.remove(id)
            summaryCache.remove(id)
        }
        val store = storeFor(id)
        val removed = store.delete(id)
        check(store.read(id) == null) { "会话文件删除失败：$id" }
        summaryIndex.delete(id)
        removed
    }

    /**
     * Session lifecycle calls this only after active runs/jobs have been cancelled and durable
     * deletion has crossed the storage lock. At that point no old writer remains authoritative,
     * so deletion tombstones no longer need to live for the process lifetime.
     */
    fun releaseDeletionBarrier(ids: Set<String>) {
        synchronized(lock) {
            ids.forEach { id ->
                if (id !in pending && id !in latestSnapshots) deletedIds.remove(id)
            }
        }
    }

    fun read(id: String): LocalHarnessSession? = readWithLegacyApproval(id)?.session

    fun readWithLegacyApproval(id: String): LocalSessionRead? = synchronized(sessionLock(id)) {
        val (deleted, latest) = synchronized(lock) {
            (id in deletedIds) to latestSnapshots[id]
        }
        if (deleted) return@synchronized null
        val persistedPayload = storeFor(id).read(id)?.document?.payload
        if (latest != null) {
            LocalSessionRead(
                session = latest,
                legacySafeAutoApproval = persistedPayload?.let(::legacySafeAutoApproval) == true,
            )
        } else {
            persistedPayload?.let { payload ->
                LocalSessionRead(
                    session = json.decodeFromJsonElement(LocalHarnessSession.serializer(), payload),
                    legacySafeAutoApproval = legacySafeAutoApproval(payload),
                )
            }
        }
    }

    fun summaries(): List<LocalSessionSummary> {
        val needsLoad = synchronized(lock) { !summariesLoaded }
        if (needsLoad) {
            val loaded = loadSummaries()
            synchronized(lock) {
                if (!summariesLoaded) {
                    // Fresh writes may have populated entries while the disk scan was running.
                    // Keep those newer in-memory summaries and fill only missing sessions from disk.
                    loaded.forEach { summary ->
                        if (summary.id !in deletedIds) summaryCache.putIfAbsent(summary.id, summary)
                    }
                    summariesLoaded = true
                }
            }
        }
        return synchronized(lock) {
            summaryCache.values.sortedByDescending(LocalSessionSummary::updatedAt)
        }
    }

    private fun loadSummaries(): List<LocalSessionSummary> {
        val ids = catalogStore.ids()
        val validIds = ids.toSet()
        summaryIndex.prune(validIds)
        return ids.mapNotNull { id ->
            val sourceModifiedAt = storeFor(id).lastModified(id) ?: return@mapNotNull null
            summaryIndex.read(id, sourceModifiedAt)?.let { return@mapNotNull it }

            val loaded = try {
                storeFor(id).read(id)
            } catch (future: FutureSessionVersionException) {
                throw future
            } catch (error: Exception) {
                AppLog.warn(
                    "LocalSessionRepository",
                    "session/corrupt-skipped id=$id stage=read",
                    error,
                )
                onError(IllegalStateException("会话 $id 已损坏，已从列表跳过；诊断信息已记录", error))
                null
            } ?: return@mapNotNull null

            runCatching {
                val summary = summaryFromPayload(loaded.document.payload, loaded.document.id, loaded.document.updatedAt)
                storeFor(id).lastModified(id)?.let { currentGeneration ->
                    runCatching { summaryIndex.write(summary, currentGeneration) }
                        .onFailure { error ->
                            AppLog.warn("LocalSessionRepository", "session/summary-index-rebuild-failed id=$id", error)
                        }
                }
                summary
            }.getOrElse { error ->
                AppLog.warn(
                    "LocalSessionRepository",
                    "session/corrupt-skipped id=$id stage=summary",
                    error,
                )
                onError(IllegalStateException("会话 $id 摘要损坏，已从列表跳过；诊断信息已记录", error))
                null
            }
        }
    }

    private fun summaryFromPayload(
        payload: JsonObject,
        fallbackId: String,
        fallbackUpdatedAt: Long,
    ): LocalSessionSummary {
        val messages = payload["messages"] as? JsonArray
        val transcriptIndex = (payload["transcriptIndex"] as? JsonObject)?.let { encoded ->
            runCatching {
                json.decodeFromJsonElement(LocalTranscriptRuntimeIndex.serializer(), encoded)
            }.getOrNull()
        }
        return LocalSessionSummary(
            id = payload["id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: fallbackId,
            title = payload["title"]?.jsonPrimitive?.contentOrNull ?: "新会话",
            updatedAt = payload["updatedAt"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it > 0L }
                ?: fallbackUpdatedAt,
            usageMode = runCatching {
                LocalUsageMode.valueOf(
                    payload["usageMode"]?.jsonPrimitive?.contentOrNull
                        ?: LocalUsageMode.WORK.name,
                )
            }.getOrDefault(LocalUsageMode.WORK),
            chatMode = runCatching {
                val group = payload["groupChat"] as? JsonObject
                LocalChatMode.valueOf(
                    group?.get("mode")?.jsonPrimitive?.contentOrNull
                        ?: LocalChatMode.SINGLE.name,
                )
            }.getOrDefault(LocalChatMode.SINGLE),
            personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull,
            galleryId = payload["galleryId"]?.jsonPrimitive?.contentOrNull,
            blank = when {
                transcriptIndex != null -> transcriptIndex.totalMessageCount == 0L
                else -> messages?.none { element ->
                    val message = element as? JsonObject ?: return@none false
                    message["content"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                } ?: true
            },
            summaryPreview = transcriptIndex?.latestUserContent
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > 72) it.take(72) + "…" else it },
            projectId = payload["projectId"]?.jsonPrimitive?.contentOrNull,
            lineageId = payload["lineageId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank),
        )
    }

    private fun persistSummaryIndex(snapshot: LocalHarnessSession, sourceModifiedAt: Long) {
        runCatching { summaryIndex.write(snapshot.toSummary(), sourceModifiedAt) }
            .onFailure { error ->
                // The sidecar is derivative. A failed index write must not roll back a durable Session.
                AppLog.warn(
                    "LocalSessionRepository",
                    "session/summary-index-write-failed id=${snapshot.id}",
                    error,
                )
            }
    }

    private fun cacheSummaryLocked(summary: LocalSessionSummary) {
        val current = summaryCache[summary.id]
        if (current == null || current.updatedAt <= summary.updatedAt) {
            summaryCache[summary.id] = summary
        }
    }

    private fun sessionLock(id: String): Any {
        val index = (id.hashCode() and Int.MAX_VALUE) % sessionLocks.size
        return sessionLocks[index]
    }

    private fun storeFor(id: String): VersionedSessionStore =
        sessionStores.computeIfAbsent(id) { VersionedSessionStore(sessionsRoot, json) }

    private fun LocalHarnessSession.toSummary(): LocalSessionSummary = LocalSessionSummary(
        id = id,
        title = title,
        updatedAt = updatedAt,
        usageMode = usageMode,
        chatMode = groupChat.mode,
        personaId = personaId,
        galleryId = galleryId,
        blank = transcriptIndex.totalMessageCount == 0L &&
            transcriptWindow.none { it.content.isNotBlank() } &&
            messages.none { it.content.isNotBlank() },
        summaryPreview = transcriptIndex.latestUserContent
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 72) it.take(72) + "…" else it },
        projectId = projectId,
        lineageId = lineageId.ifBlank { id },
    )
    private companion object {
        const val SESSION_LOCK_STRIPES = 64
    }
}
