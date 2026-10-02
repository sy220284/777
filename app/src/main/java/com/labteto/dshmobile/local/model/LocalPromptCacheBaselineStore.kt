package com.labteto.dshmobile.local

import java.util.LinkedHashMap

/** Bounded process-local response-id baselines used only for prompt-cache diagnostics. */
internal class LocalPromptCacheBaselineStore(
    maxEntries: Int = 128,
) {
    private data class Key(
        val sessionId: String,
        val profileId: String,
    )

    private val capacity = maxEntries.coerceAtLeast(1)
    private val entries = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, String>?,
        ): Boolean = size > capacity
    }

    @Synchronized
    fun get(sessionId: String, profileId: String): String? =
        entries[Key(sessionId, profileId)]

    @Synchronized
    fun put(sessionId: String, profileId: String, responseId: String) {
        val normalized = responseId.trim().takeIf(String::isNotBlank) ?: return
        entries[Key(sessionId, profileId)] = normalized.take(512)
    }

    @Synchronized
    fun clearSession(sessionId: String) {
        entries.keys.removeAll { it.sessionId == sessionId }
    }

    @Synchronized
    internal fun size(): Int = entries.size
}
