package com.labteto.dshmobile.local.work

/**
 * The delivery mode is an execution command, not just message metadata.
 * QUEUE_ONLY accepts into the existing durable Job Inbox but never initiates recovery.
 * TRIGGER_TURN preserves the previous foreground/continuable wake behavior.
 *
 * Acceptance is not evidence of model consumption. A separately checkpointed model
 * history or an actual completed result is required for stronger statements.
 */
internal enum class LocalAgentMessageDeliveryMode(val wireValue: String) {
    QUEUE_ONLY("queue_only"),
    TRIGGER_TURN("trigger_turn");

    companion object {
        fun fromWire(raw: String?): LocalAgentMessageDeliveryMode = when (raw?.trim()?.lowercase()) {
            null, "", "trigger_turn" -> TRIGGER_TURN
            "queue_only" -> QUEUE_ONLY
            else -> error("AGENT_MESSAGE_DELIVERY_MODE_INVALID")
        }
    }
}

internal fun shouldWakeAgentAfterAcceptedMessage(
    mode: LocalAgentMessageDeliveryMode,
    accepted: Boolean,
    requiresResume: Boolean,
): Boolean = accepted && requiresResume && mode == LocalAgentMessageDeliveryMode.TRIGGER_TURN
