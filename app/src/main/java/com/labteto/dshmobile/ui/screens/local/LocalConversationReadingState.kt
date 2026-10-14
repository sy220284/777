package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.ConversationReadingUiPort
import com.labteto.dshmobile.uicontract.UiReadingAnchor

/** Uses the same retained-session budget as composer UI state. No business persistence. */
internal class LocalConversationReadingState(
    saved: List<String> = emptyList(),
    private val onSave: (List<String>) -> Unit = {},
) : ConversationReadingUiPort {
    private val anchors = linkedMapOf<String, UiReadingAnchor>()

    init {
        saved.chunked(4).filter { it.size == 4 }.forEach { row ->
            val offset = row[2].toIntOrNull() ?: return@forEach
            val following = row[3].toBooleanStrictOrNull() ?: return@forEach
            anchors[row[0]] = UiReadingAnchor(row[1].ifEmpty { null }, offset, following)
        }
        trim()
    }

    override fun anchor(sessionId: String) = anchors[sessionId]

    override fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor) {
        if (anchors[sessionId] == anchor) return
        anchors.remove(sessionId)
        anchors[sessionId] = anchor
        trim()
        onSave(anchors.flatMap { (id, value) ->
            listOf(id, value.messageId.orEmpty(), value.offsetPx.toString(), value.followingTail.toString())
        })
    }

    fun forgetSessions(ids: Set<String>) {
        ids.forEach(anchors::remove)
        onSave(anchors.flatMap { (id, value) -> listOf(id, value.messageId.orEmpty(), value.offsetPx.toString(), value.followingTail.toString()) })
    }

    private fun trim() {
        while (anchors.size > MAX_LOCAL_SESSION_PRESENTATION_STATES) anchors.remove(anchors.keys.first())
    }
}
