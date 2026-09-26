package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val store = VersionedSessionStore(root, json)
    private val lock = Any()
    private val storageLock = Any()
    private val deletedIds = mutableSetOf<String>()
    private val pending = linkedMapOf<String, LocalHarnessSession>()
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
                        synchronized(storageLock) {
                            if (snapshot.id !in deletedIds) store.write(
                                snapshot.id,
                                json.encodeToJsonElement(LocalHarnessSession.serializer(), snapshot).jsonObject,
                                updatedAt = snapshot.updatedAt,
                            )
                        }
                        synchronized(lock) {
                            if (snapshot.id !in deletedIds) summaryCache[snapshot.id] = snapshot.toSummary()
                        }
                        onWritten()
                        consecutiveFailures = 0
                    } catch (cancelled: CancellationException) {
                        synchronized(lock) { if (snapshot.id !in deletedIds) pending.putIfAbsent(snapshot.id, snapshot) }
                        throw cancelled
                    } catch (error: Exception) {
                        // Keep the newest snapshot for this session. A failed disk write must not
                        // silently remove the only queued copy or spin at full speed on a bad disk.
                        synchronized(lock) { if (snapshot.id !in deletedIds) pending.putIfAbsent(snapshot.id, snapshot) }
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
            pending[snapshot.id] = snapshot
        }
        wakeups.trySend(Unit)
    }

    /** Block a queued or in-flight snapshot from recreating a deleted session. */
    fun delete(id: String): Boolean = synchronized(storageLock) {
        val removed = store.delete(id)
        check(store.read(id) == null) { "会话文件删除失败：$id" }
        synchronized(lock) {
            deletedIds += id
            pending.remove(id)
            summaryCache.remove(id)
        }
        removed
    }

    fun read(id: String): LocalHarnessSession? = readWithLegacyApproval(id)?.session

    fun readWithLegacyApproval(id: String): LocalSessionRead? =
        store.read(id)?.document?.payload?.let { payload ->
            LocalSessionRead(
                session = json.decodeFromJsonElement(LocalHarnessSession.serializer(), payload),
                legacySafeAutoApproval = legacySafeAutoApproval(payload),
            )
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

    private fun loadSummaries(): List<LocalSessionSummary> = store.ids()
        .mapNotNull { id ->
            val loaded = try {
                store.read(id)
            } catch (future: FutureSessionVersionException) {
                throw future
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null

            runCatching {
                val payload = loaded.document.payload
                val messages = payload["messages"] as? JsonArray
                val transcriptIndex = (payload["transcriptIndex"] as? JsonObject)?.let { encoded ->
                    runCatching {
                        json.decodeFromJsonElement(LocalTranscriptRuntimeIndex.serializer(), encoded)
                    }.getOrNull()
                }
                LocalSessionSummary(
                    id = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: loaded.document.id,
                    title = payload["title"]?.jsonPrimitive?.contentOrNull ?: "新会话",
                    updatedAt = payload["updatedAt"]?.jsonPrimitive?.longOrNull
                        ?.takeIf { it > 0L }
                        ?: loaded.document.updatedAt,
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
                    blank = when {
                        transcriptIndex != null -> transcriptIndex.totalMessageCount == 0L
                        else -> messages?.none { element ->
                            val message = element as? JsonObject ?: return@none false
                            message["content"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                        } ?: true
                    },
                )
            }.getOrNull()
        }

    private fun LocalHarnessSession.toSummary(): LocalSessionSummary = LocalSessionSummary(
        id = id,
        title = title,
        updatedAt = updatedAt,
        usageMode = usageMode,
        chatMode = groupChat.mode,
        blank = transcriptIndex.totalMessageCount == 0L && messages.none { it.content.isNotBlank() },
    )
}
