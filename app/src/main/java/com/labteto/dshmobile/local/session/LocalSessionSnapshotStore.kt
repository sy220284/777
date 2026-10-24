package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.harness.session.SessionDocument
import com.labteto.dshmobile.harness.session.SessionLoadResult
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Durable snapshot boundary. The Android app uses SQLite; the file adapter remains
 * available for portable JVM storage-contract tests until the event-log migration lands.
 *
 * Model-visible events are deliberately not routed through this snapshot API.
 */
internal interface LocalSessionSnapshotStore {
    fun read(id: String): SessionLoadResult?
    fun write(id: String, payload: JsonObject, updatedAt: Long): SessionDocument
    fun delete(id: String): Boolean
    fun ids(): List<String>
    /** Monotonic storage revision used to validate the derived summary cache. */
    fun lastModified(id: String): Long?
    fun hasRecoverableSnapshot(id: String): Boolean
    /** Native summaries share the same transaction as the session snapshot. */
    val nativeSummaries: Boolean get() = false
    fun readSummaries(): List<LocalSessionSummary> = emptyList()
    /** Small, read-only foreground preview; never used as the model's canonical context. */
    fun recentMessages(id: String, limit: Int): List<LocalHarnessMessage> = emptyList()
    fun evict(id: String) {}
}

internal class FileSessionSnapshotStore(root: File, json: Json) : LocalSessionSnapshotStore {
    private val catalog = VersionedSessionStore(root, json)
    private val sessionsRoot = root
    private val codec = json
    private val stores = ConcurrentHashMap<String, VersionedSessionStore>()

    private fun store(id: String): VersionedSessionStore =
        stores.computeIfAbsent(id) { VersionedSessionStore(sessionsRoot, codec) }

    override fun read(id: String): SessionLoadResult? = store(id).read(id)
    override fun write(id: String, payload: JsonObject, updatedAt: Long): SessionDocument =
        store(id).write(id, payload, updatedAt)
    override fun delete(id: String): Boolean = store(id).delete(id)
    override fun ids(): List<String> = catalog.ids()
    override fun lastModified(id: String): Long? = store(id).lastModified(id)
    override fun hasRecoverableSnapshot(id: String): Boolean = store(id).hasRecoverableSnapshot(id)
    override fun evict(id: String) { stores.remove(id) }
}
