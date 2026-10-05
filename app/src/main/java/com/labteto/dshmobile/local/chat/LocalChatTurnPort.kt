package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job

/**
 * Transitional low-level Chat turn bridge.
 *
 * ChatFeature owns send/timeline admission and durable mutations. This bridge only starts an
 * already-prepared foreground turn until the direct Chat turn body leaves the composition root.
 */
internal interface LocalChatTurnPort {
    fun start(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
    ): Job

    fun startRegeneration(
        prompt: String,
        replacingMessageId: String,
    ): Job
}
