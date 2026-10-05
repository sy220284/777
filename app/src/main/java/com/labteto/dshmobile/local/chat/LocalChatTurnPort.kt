package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job

/**
 * Transitional low-level Chat turn bridge.
 *
 * ChatFeature owns send admission/durable input. Only turn execution plus timeline mutation remain
 * behind this bridge until runChatTurn/edit/regenerate finish moving out of the composition root.
 */
internal interface LocalChatTurnPort {
    fun start(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
    ): Job

    fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): LocalChatUserEditResult

    fun regenerateReply(messageId: String): Boolean
}
