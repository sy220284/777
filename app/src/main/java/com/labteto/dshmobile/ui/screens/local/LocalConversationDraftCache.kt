package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_LOCAL_SESSION_DRAFTS = 12

/** Single UI-only owner for both packs. Updates run on the UI thread, like the composer. */
internal class LocalSessionDraftCache(
    private val onSave: (List<String>) -> Unit = {},
) : ConversationDraftUiPort {
    private val values = linkedMapOf<String, String>()
    private val published = MutableStateFlow<Map<String, String>>(emptyMap())
    override val drafts = published.asStateFlow()

    operator fun get(sessionId: String): String? = values[sessionId]

    override fun updateDraft(sessionId: String, text: String) = putBoundedLocalDraft(sessionId, text)

    fun putBoundedLocalDraft(sessionId: String, value: String) {
        values.remove(sessionId)
        if (value.isNotBlank()) values[sessionId] = value
        while (values.size > MAX_LOCAL_SESSION_DRAFTS) values.remove(values.keys.first())
        published.value = values.toMap()
        onSave(save())
    }

    fun save(): List<String> = values.flatMap { (sessionId, value) -> listOf(sessionId, value) }

    companion object {
        fun restore(saved: List<String>, onSave: (List<String>) -> Unit = {}): LocalSessionDraftCache =
            LocalSessionDraftCache(onSave).apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size == 2) {
                        values.remove(pair[0])
                        if (pair[1].isNotBlank()) values[pair[0]] = pair[1]
                    }
                }
                while (values.size > MAX_LOCAL_SESSION_DRAFTS) values.remove(values.keys.first())
                published.value = values.toMap()
            }
    }
}
