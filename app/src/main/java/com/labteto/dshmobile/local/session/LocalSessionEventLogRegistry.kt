package com.labteto.dshmobile.local

import java.io.File
import java.util.LinkedHashMap
import kotlinx.serialization.json.Json

/**
 * Process-local owner for Session EventLog adapters.
 *
 * One session path maps to one Android adapter in the hot set. The core log also shares its
 * sequence cursor per durable path, so cache eviction never reintroduces a tail scan on each append.
 */
internal class LocalSessionEventLogRegistry(
    private val sessionsRoot: File,
    private val json: Json,
    private val maxEntries: Int = 64,
) {
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
    fun get(sessionId: String): LocalSessionEventLog =
        logs.getOrPut(sessionId) {
            LocalSessionEventLog(
                File(sessionsRoot, "$sessionId.events.jsonl"),
                json,
                sessionId = sessionId,
            )
        }

    /**
     * Reset a deleted path before evicting it so the core shared sequence cursor cannot survive a
     * delete/recreate cycle for the same session id.
     */
    @Synchronized
    fun clearAndEvict(sessionIds: Set<String>) {
        sessionIds.forEach { id ->
            logs.remove(id)?.let { log ->
                log.clear()
                log.close()
            }
        }
    }
}
