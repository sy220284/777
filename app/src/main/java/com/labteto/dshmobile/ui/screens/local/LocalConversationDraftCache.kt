package com.labteto.dshmobile.ui.screens.local

private const val MAX_LOCAL_SESSION_DRAFTS = 12

/**
 * Keep only the most recently touched local-session drafts. Blank drafts do not need persistence.
 *
 * Re-inserting an existing key updates its recency even when the backing map preserves insertion
 * order only, and keeps restored legacy maps bounded after the first edit.
 */
internal fun MutableMap<String, String>.putBoundedLocalDraft(sessionId: String, value: String) {
    if (value.isBlank()) {
        remove(sessionId)
        return
    }
    remove(sessionId)
    this[sessionId] = value
    while (size > MAX_LOCAL_SESSION_DRAFTS) {
        keys.firstOrNull()?.let(::remove) ?: break
    }
}
