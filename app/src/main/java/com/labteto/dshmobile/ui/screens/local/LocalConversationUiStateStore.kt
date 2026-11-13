package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns local-only conversation presentation preferences so the screen ViewModel stays focused on
 * orchestration instead of persistence details.
 */
internal class LocalConversationUiStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _pinnedSessionIds = MutableStateFlow(
        preferences.getStringSet(PINNED_SESSION_IDS, emptySet()).orEmpty().toSet(),
    )
    val pinnedSessionIds = _pinnedSessionIds.asStateFlow()

    private val _sessionTitleOverrides = MutableStateFlow(
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(SESSION_TITLE_PREFIX)) return@mapNotNull null
            val title = value as? String ?: return@mapNotNull null
            key.removePrefix(SESSION_TITLE_PREFIX) to title
        }.toMap(),
    )
    val sessionTitleOverrides = _sessionTitleOverrides.asStateFlow()

    fun toggleSessionPinned(sessionId: String) {
        val updated = _pinnedSessionIds.value.toMutableSet().apply {
            if (!add(sessionId)) remove(sessionId)
        }.toSet()
        _pinnedSessionIds.value = updated
        preferences.edit().putStringSet(PINNED_SESSION_IDS, updated).apply()
    }

    fun renameSession(sessionId: String, title: String): Boolean {
        val normalized = title.trim().take(80)
        if (normalized.isBlank()) return false
        _sessionTitleOverrides.value = _sessionTitleOverrides.value + (sessionId to normalized)
        preferences.edit().putString(SESSION_TITLE_PREFIX + sessionId, normalized).apply()
        return true
    }

    suspend fun deleteSessions(
        ids: Set<String>,
        delete: suspend (Set<String>) -> Int,
    ): Int {
        val deleted = delete(ids)
        if (deleted <= 0) return deleted

        val pinned = _pinnedSessionIds.value - ids
        val titles = _sessionTitleOverrides.value - ids
        _pinnedSessionIds.value = pinned
        _sessionTitleOverrides.value = titles
        preferences.edit().apply {
            putStringSet(PINNED_SESSION_IDS, pinned)
            ids.forEach { remove(SESSION_TITLE_PREFIX + it) }
        }.apply()
        return deleted
    }

    private companion object {
        const val PREFERENCES_NAME = "local_conversation_ui"
        const val PINNED_SESSION_IDS = "pinned_session_ids"
        const val SESSION_TITLE_PREFIX = "session_title:"
    }
}
