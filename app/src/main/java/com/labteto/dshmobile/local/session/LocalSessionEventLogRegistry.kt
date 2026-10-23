package com.labteto.dshmobile.local.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** A bounded historical stream listing, independent of the current foreground adapter LRU. */
internal data class LocalHistoricalSessionSource(
    val id: String,
    val identity: String,
    val size: Long,
    val modifiedAt: Long,
)

/**
 * Process-local owner for Session EventLog adapters.
 *
 * One session path maps to one Android adapter in the hot set. The core log also shares its
 * sequence cursor per durable path, so cache eviction never reintroduces a tail scan on each append.
 */
@Singleton
class LocalSessionEventLogRegistry internal constructor(
    private val sessionsRoot: File,
    private val json: Json,
    private val maxEntries: Int = 64,
) {
    @Inject
    internal constructor(@ApplicationContext context: Context, json: Json) : this(
        sessionsRoot = File(context.filesDir, "local-harness/sessions").apply { mkdirs() },
        json = json,
    )

    init {
        require(maxEntries in 8..512) { "EventLog 注册表容量必须在 8..512 之间" }
    }

    private val logs = object : LinkedHashMap<String, LocalSessionEventLog>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LocalSessionEventLog>?,
        ): Boolean {
            val evict = size > maxEntries
            if (evict) eldest?.value?.close()
            return evict
        }
    }

    @Synchronized
    internal fun get(sessionId: String): LocalSessionEventLog =
        logs.getOrPut(sessionId) {
            LocalSessionEventLog(
                File(sessionsRoot, "$sessionId.events.jsonl"),
                json,
                sessionId = sessionId,
            )
        }

    /** Report the authority's durable historical sessions without touching the active adapter LRU. */
    internal fun historicalSources(): List<LocalHistoricalSessionSource> =
        sessionsRoot.listFiles().orEmpty().asSequence()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                it.name.endsWith(".events.jsonl") }
            .map { file ->
                val identity = java.nio.file.Files.readAttributes(
                    file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ).fileKey()?.toString() ?: file.canonicalPath
                LocalHistoricalSessionSource(file.name.removeSuffix(".events.jsonl"),
                    identity, file.length(), file.lastModified())
            }.sortedBy { it.id }.toList()

    /** Scoped native adapter; historical scans must not evict a live foreground event log. */
    internal fun <T> readHistorical(sessionId: String, block: (LocalSessionEventLog) -> T): T {
        require(sessionId.isNotBlank() && '/' !in sessionId && '\\' !in sessionId && ".." !in sessionId) {
            "Invalid historical session identity"
        }
        val reader = LocalSessionEventLog(
            File(sessionsRoot, "${sessionId}.events.jsonl"), json, sessionId = sessionId,
        )
        return try { block(reader) } finally { reader.close() }
    }

    /**
     * Reset a deleted path before evicting it so the core shared sequence cursor cannot survive a
     * delete/recreate cycle for the same session id.
     */
    @Synchronized
    internal fun clearAndEvict(sessionIds: Set<String>) {
        sessionIds.forEach { id ->
            logs.remove(id)?.let { log ->
                log.clear()
                log.close()
            }
        }
    }
}
