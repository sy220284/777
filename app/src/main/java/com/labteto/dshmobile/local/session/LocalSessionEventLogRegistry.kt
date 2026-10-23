package com.labteto.dshmobile.local.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

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
    private val sqlite: SqliteSessionEventDatabase? = null,
) {
    @Inject
    internal constructor(@ApplicationContext context: Context, json: Json) : this(
        sessionsRoot = File(context.filesDir, "local-harness/sessions").apply { mkdirs() },
        json = json,
        sqlite = SqliteSessionEventDatabase(context, json),
    )

    init {
        require(maxEntries in 8..512) { "EventLog 注册表容量必须在 8..512 之间" }
    }

    internal val sqliteDatabase: SqliteSessionEventDatabase? get() = sqlite

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
                sqlite = sqlite,
            )
        }

    /**
     * Reset a deleted path before evicting it so the core shared sequence cursor cannot survive a
     * delete/recreate cycle for the same session id.
     */
    @Synchronized
    internal fun clearAndEvict(sessionIds: Set<String>) {
        sessionIds.forEach { id ->
            val cached = logs.remove(id)
            if (cached != null) {
                cached.clear()
                cached.close()
            } else {
                // An evicted session still has durable SQL rows; do not let deletion
                // resurrect it when a later session reuses the same identity.
                sqlite?.clearSession(id)
            }
        }
    }
}
