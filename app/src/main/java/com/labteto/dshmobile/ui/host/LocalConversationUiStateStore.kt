package com.labteto.dshmobile.ui.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns local-only conversation presentation preferences so the screen ViewModel stays focused on
 * orchestration instead of persistence details.
 */
internal class LocalConversationUiStateStore(context: Context,
    private val sessions: com.labteto.dshmobile.uicontract.SessionUiPort,
) : com.labteto.dshmobile.uicontract.ConversationShellUiPort {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _pinnedSessionIds = MutableStateFlow(
        preferences.getStringSet(PINNED_SESSION_IDS, emptySet()).orEmpty().toSet(),
    )
    override val pinnedSessionIds = _pinnedSessionIds.asStateFlow()

    private val _sessionTitleOverrides = MutableStateFlow(
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(SESSION_TITLE_PREFIX)) return@mapNotNull null
            val title = value as? String ?: return@mapNotNull null
            key.removePrefix(SESSION_TITLE_PREFIX) to title
        }.toMap(),
    )
    override val sessionTitleOverrides = _sessionTitleOverrides.asStateFlow()

    @Synchronized
    override fun submit(command: com.labteto.dshmobile.uicontract.ConversationShellUiCommand): com.labteto.dshmobile.uicontract.UiCommandReceipt {
        if (sessions.snapshot().sessions.none { it.id == command.sessionId })
            return com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY)
        return when (command) {
            is com.labteto.dshmobile.uicontract.ConversationShellUiCommand.Rename -> if (renameSession(command.sessionId, command.title))
                com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted else com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(com.labteto.dshmobile.uicontract.UiCommandRejection.EMPTY_INPUT)
            is com.labteto.dshmobile.uicontract.ConversationShellUiCommand.SetPinned -> {
                val updated = if (command.pinned) _pinnedSessionIds.value + command.sessionId else _pinnedSessionIds.value - command.sessionId
                preferences.edit().putStringSet(PINNED_SESSION_IDS, updated).apply()
                _pinnedSessionIds.value = updated
                com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
            }
        }
    }

    private fun renameSession(sessionId: String, title: String): Boolean {
        val normalized = title.trim().take(80)
        if (normalized.isBlank()) return false
        _sessionTitleOverrides.value = _sessionTitleOverrides.value + (sessionId to normalized)
        preferences.edit().putString(SESSION_TITLE_PREFIX + sessionId, normalized).apply()
        return true
    }

    @Synchronized
    fun forgetSessions(ids: Set<String>) {
        if (ids.isEmpty()) return

        val pinned = _pinnedSessionIds.value - ids
        val titles = _sessionTitleOverrides.value - ids
        _pinnedSessionIds.value = pinned
        _sessionTitleOverrides.value = titles
        preferences.edit().apply {
            putStringSet(PINNED_SESSION_IDS, pinned)
            ids.forEach { remove(SESSION_TITLE_PREFIX + it) }
        }.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "local_conversation_ui"
        const val PINNED_SESSION_IDS = "pinned_session_ids"
        const val SESSION_TITLE_PREFIX = "session_title:"
    }
}
