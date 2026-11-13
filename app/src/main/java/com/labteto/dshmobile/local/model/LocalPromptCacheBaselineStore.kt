package com.labteto.dshmobile.local.model

import java.util.LinkedHashMap

/** Bounded process-local response-id baselines used only for prompt-cache diagnostics. */
internal class LocalPromptCacheBaselineStore(
    maxEntries: Int = 128,
) {
    private data class Key(
        val sessionId: String,
        val routeFingerprint: String,
    )

    private val capacity = maxEntries.coerceAtLeast(1)
    private val entries = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, String>?,
        ): Boolean = size > capacity
    }

    @Synchronized
    fun get(sessionId: String, routeFingerprint: String): String? =
        entries[Key(sessionId, routeFingerprint)]

    @Synchronized
    fun put(sessionId: String, routeFingerprint: String, responseId: String) {
        val normalized = responseId.trim().takeIf(String::isNotBlank) ?: return
        entries[Key(sessionId, routeFingerprint)] = normalized.take(512)
    }

    @Synchronized
    fun clearSession(sessionId: String) {
        entries.keys.removeAll { it.sessionId == sessionId }
    }

    @Synchronized
    internal fun size(): Int = entries.size
}
