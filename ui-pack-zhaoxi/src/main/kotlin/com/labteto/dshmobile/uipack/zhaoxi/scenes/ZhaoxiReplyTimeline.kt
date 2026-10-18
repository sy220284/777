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
        // Tool-only events after the user turn must not consume the initial reply slot.
        if (lastUser != null &&
            messages.lastOrNull { it.role != UiMessageRole.TOOL }?.role == UiMessageRole.USER) {
            result += ZxTimelineEntry.Reply(lastUser, emptyList(), "reply:$lastUser:0")
        }
    }
    return result
}


/**
 * Only append the part of a request-scoped streaming snapshot that is not already durable.
 * Preview providers may return cumulative text or just the latest delta; only strip
 * verified leading committed text. Never search-and-remove arbitrary repeated prose.
 */
internal fun uncommittedReplyPreview(
    preview: String?,
    persistedStages: List<UiMessageSnapshot>,
): String? {
    if (preview.isNullOrBlank()) return null
    val committed = persistedStages.map { it.text }.filter { it.isNotBlank() }
    if (committed.isEmpty()) return preview
    // A delayed preview can arrive after its matching durable message.
    if (committed.any { it == preview || it.startsWith(preview) }) return null
    val prefixes = listOf("\n\n", "\n", "").map { committed.joinToString(it) }
        .filter { it.isNotBlank() }.sortedByDescending { it.length }
    val prefix = prefixes.firstOrNull { preview.startsWith(it) }
        ?: committed.lastOrNull { preview.startsWith(it) }
    if (prefix != null) return preview.removePrefix(prefix).trimStart('\n', '\r')
        .takeIf { it.isNotBlank() }
    // Some providers emit a fresh-only delta. Preserve it verbatim if no prefix matches.
    return preview
}
