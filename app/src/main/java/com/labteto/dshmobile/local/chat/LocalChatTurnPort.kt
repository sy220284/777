package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job

/**
 * Transitional low-level Chat turn bridge.
 *
 * ChatFeature owns send/timeline/regeneration and direct execution. This bridge only starts the
 * remaining first-turn dispatcher while group/direct composition still leaves through Engine.
 */
internal interface LocalChatTurnPort {
    fun start(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
    ): Job
}
