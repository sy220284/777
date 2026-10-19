package com.labteto.dshmobile.uicontract

/** Stable source message identity, never a renderer's list index. */
data class UiReadingAnchor(val messageId: String?, val offsetPx: Int, val followingTail: Boolean)

/** Host owns presentation recovery; reading does not change conversation or task facts. */
interface ConversationReadingUiPort {
    fun anchor(sessionId: String): UiReadingAnchor?
    fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor)
}
