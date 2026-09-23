package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
            for (ignored in wakeups) {
                while (true) {
                    val snapshot = synchronized(lock) {
                        pending.keys.firstOrNull()?.let { pending.remove(it) }
                    } ?: break
                    runCatching {
                        store.write(snapshot.id, json.parseToJsonElement(
                            json.encodeToString(LocalHarnessSession.serializer(), snapshot),
                        ).jsonObject, updatedAt = snapshot.updatedAt)
                        onWritten()
                    }.onFailure(onError)
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
