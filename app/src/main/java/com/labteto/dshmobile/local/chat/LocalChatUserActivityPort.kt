package com.labteto.dshmobile.local.chat

/**
 * Chat-owned contract for publishing durable user activity to interested external features.
 *
 * Chat emits the fact; composition decides which external feature observes it.
 */
internal fun interface LocalChatUserActivityPort {
    fun record(sessionId: String, userMessageAt: Long)
}
