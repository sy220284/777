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

/**
 * A durable acceptance receipt. It explicitly does not promise execution, consumption or
 * a completed turn. Wake *requested* also does not mean that a new turn was admitted.
 */
internal fun renderLocalAgentMessageSubmission(
    messageId: String,
    mode: LocalAgentMessageDeliveryMode,
    accepted: Boolean,
    duplicate: Boolean,
    requiresResume: Boolean,
    rejection: String? = null,
): String {
    require(messageId.isNotBlank()) { "AGENT_MESSAGE_ID_REQUIRED" }
    val wakeRequested = shouldWakeAgentAfterAcceptedMessage(mode, accepted, requiresResume)
    return buildString {
        append("message_id=").append(messageId)
        append("\naccepted=").append(accepted)
        append("\nduplicate=").append(duplicate)
        append("\ndelivery_mode=").append(mode.wireValue)
        append("\nwake_requested=").append(wakeRequested)
        append("\nprocessing_status=").append(if (accepted) "unverified" else "not_admitted")
        append("\ncompletion_status=").append(if (accepted) "unverified" else "not_admitted")
        if (!accepted) rejection?.let { append("\nreason=").append(it) }
    }
}
