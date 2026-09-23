package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val pending = linkedMapOf<String, LocalHarnessSession>()
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
                        store.write(snapshot.id, json.parseToJsonElement(
                            json.encodeToString(LocalHarnessSession.serializer(), snapshot),
                        ).jsonObject, updatedAt = snapshot.updatedAt)
                        onWritten()
                        consecutiveFailures = 0
                    } catch (cancelled: CancellationException) {
                        synchronized(lock) { pending.putIfAbsent(snapshot.id, snapshot) }
                        throw cancelled
                    } catch (error: Exception) {
                        // Keep the newest snapshot for this session. A failed disk write must not
                        // silently remove the only queued copy or spin at full speed on a bad disk.
                        synchronized(lock) { pending.putIfAbsent(snapshot.id, snapshot) }
                        onError(error)
                        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                        delay((1_000L shl (consecutiveFailures - 1)).coerceAtMost(30_000L))
                    }
                }
            }
        }
    }

    fun enqueue(snapshot: LocalHarnessSession) {
        synchronized(lock) { pending[snapshot.id] = snapshot }
        wakeups.trySend(Unit)
    }

    fun read(id: String): LocalHarnessSession? = readWithLegacyApproval(id)?.session

    fun readWithLegacyApproval(id: String): LocalSessionRead? =
        store.read(id)?.document?.payload?.let { payload ->
            LocalSessionRead(
                session = json.decodeFromString(LocalHarnessSession.serializer(), payload.toString()),
                legacySafeAutoApproval = legacySafeAutoApproval(payload),
            )
        }

    fun summaries(): List<LocalSessionSummary> = store.list().mapNotNull { loaded ->
        runCatching {
            val session = json.decodeFromString(LocalHarnessSession.serializer(), loaded.document.payload.toString())
            LocalSessionSummary(
                id = session.id.ifBlank { loaded.document.id },
                title = session.title,
                updatedAt = session.updatedAt.takeIf { it > 0 } ?: loaded.document.updatedAt,
                blank = session.messages.none { it.content.isNotBlank() },
            )
        }.getOrNull()
    }
}
