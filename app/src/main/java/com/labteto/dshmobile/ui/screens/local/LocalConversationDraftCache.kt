package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

private const val MAX_LOCAL_SESSION_DRAFTS = 12

/**
 * Small UI-only LRU for per-session composer drafts.
 *
 * SnapshotStateMap deliberately does not define iteration order, so recency is tracked separately
 * and persisted in that order. Blank drafts release their entry immediately.
 */
internal class LocalSessionDraftCache {
    private val values = mutableStateMapOf<String, String>()
    private val recency = mutableStateListOf<String>()

    operator fun get(sessionId: String): String? = values[sessionId]

    fun putBoundedLocalDraft(sessionId: String, value: String) {
        recency.remove(sessionId)
        if (value.isBlank()) {
            values.remove(sessionId)
            return
        }
        values[sessionId] = value
        recency += sessionId
        while (recency.size > MAX_LOCAL_SESSION_DRAFTS) {
            values.remove(recency.removeAt(0))
        }
    }

    fun save(): List<String> = recency.flatMap { sessionId ->
        values[sessionId]?.let { value -> listOf(sessionId, value) }.orEmpty()
    }

    companion object {
        fun restore(saved: List<String>): LocalSessionDraftCache =
            LocalSessionDraftCache().apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size == 2) putBoundedLocalDraft(pair[0], pair[1])
                }
            }
    }
}
