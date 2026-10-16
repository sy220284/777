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
            val sessionId = row[0].takeIf { it.isNotBlank() && it.length <= 256 } ?: return@forEach
            val messageId = row[1].takeIf { it.isNotEmpty() }
            if (messageId != null && messageId.length > 256) return@forEach
            if (offset !in 0..1_000_000) return@forEach
            anchors[sessionId] = UiReadingAnchor(messageId, offset, following)
        }
        trim()
    }

    override fun anchor(sessionId: String) = anchors[sessionId]

    override fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor) {
        // Presentation state is untrusted after process reconstruction; do not keep
        // unlimited scroll offsets or an invalid cross-session message target.
        if (sessionId.isBlank() || sessionId.length > 256 ||
            (anchor.messageId?.length ?: 0) > 256) return
        val safeAnchor = anchor.copy(offsetPx = anchor.offsetPx.coerceIn(0, 1_000_000))
        if (anchors[sessionId] == safeAnchor) return
        anchors.remove(sessionId)
        anchors[sessionId] = safeAnchor
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
