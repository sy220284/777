package com.labteto.dshmobile.uipack.zhaoxi.scenes

import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot

/**
 * One stable presentation identity per user turn. Stage messages and the final
 * answer grow inside that turn; this projection never mutates the transcript.
 */
internal sealed interface ZxTimelineEntry {
    val key: String
    fun containsMessage(id: String): Boolean

    data class Message(val message: UiMessageSnapshot) : ZxTimelineEntry {
        override val key: String get() = "message:${message.id}"
        override fun containsMessage(id: String) = message.id == id
    }

    data class Reply(
        val anchorId: String?,
        val stages: List<UiMessageSnapshot>,
        override val key: String,
    ) : ZxTimelineEntry {
        override fun containsMessage(id: String): Boolean = stages.any { it.id == id }
    }
}

/** Hidden tool events remain available through the Work owner, never printed as model prose. */
internal fun projectZxReplyTimeline(
    messages: List<UiMessageSnapshot>,
    running: Boolean,
): List<ZxTimelineEntry> {
    val result = mutableListOf<ZxTimelineEntry>()
    var anchor: String? = null
    var stages = mutableListOf<UiMessageSnapshot>()
    var segment = 0
    fun flush() {
        if (stages.isNotEmpty()) {
            result += ZxTimelineEntry.Reply(anchor, stages.toList(),
                "reply:${anchor ?: "orphan"}:$segment")
            stages = mutableListOf()
            segment++
        }
    }
    messages.forEach { message ->
        when (message.role) {
            UiMessageRole.USER -> {
                flush()
                anchor = message.id
                segment = 0
                result += ZxTimelineEntry.Message(message)
            }
            UiMessageRole.ASSISTANT -> stages += message
            UiMessageRole.TOOL -> { /* preserved in evidence ledger; no raw tool output in prose */ }
            UiMessageRole.SYSTEM -> { flush(); result += ZxTimelineEntry.Message(message) }
        }
    }
    flush()
    // The last user turn reserves its reply slot before the first model token.
    // Once persisted, its key remains unchanged and Compose can update in place.
    if (running && result.lastOrNull() !is ZxTimelineEntry.Reply) {
        val lastUser = messages.lastOrNull { it.role == UiMessageRole.USER }?.id
        if (lastUser != null && messages.lastOrNull()?.role == UiMessageRole.USER) {
            result += ZxTimelineEntry.Reply(lastUser, emptyList(), "reply:$lastUser:0")
        }
    }
    return result
}
