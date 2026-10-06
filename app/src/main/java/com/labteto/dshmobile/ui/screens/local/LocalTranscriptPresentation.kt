package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage

/**
 * User-facing transcript projection.
 *
 * Reasoning, tool results and assistant text emitted alongside tool calls are implementation
 * details of one agent turn. They are grouped into a single collapsed work-process row so the
 * transcript keeps the completed answer visually dominant.
 */
internal sealed interface LocalTranscriptItem {
    val key: String

    data class Message(
        val message: LocalHarnessMessage,
    ) : LocalTranscriptItem {
        override val key: String = "message:${message.id}"
    }

    data class Thinking(
        val messages: List<LocalHarnessMessage>,
    ) : LocalTranscriptItem {
        init {
            require(messages.isNotEmpty()) { "思考过程不能为空" }
            require(messages.all { it.role == "reasoning" }) { "聊天思考只接受 reasoning 消息" }
        }

        override val key: String = "thinking:${messages.first().id}"
    }

    data class WorkProcess(
        val messages: List<LocalHarnessMessage>,
    ) : LocalTranscriptItem {
        init {
            require(messages.isNotEmpty()) { "工作过程不能为空" }
        }

        override val key: String = "work:${messages.first().id}"
    }
}

internal fun userVisibleDialogueMessageCount(
    messages: List<LocalHarnessMessage>,
): Int = buildLocalTranscript(
    messages = messages,
    includeWorkProcess = false,
).count { item ->
    item is LocalTranscriptItem.Message &&
        (item.message.role == "user" || item.message.role == "assistant")
}

internal fun needsMoreUserVisibleDialogue(
    messages: List<LocalHarnessMessage>,
    target: Int,
): Boolean = userVisibleDialogueMessageCount(messages) < target.coerceAtLeast(0)

internal fun mergeLocalTranscriptHistory(
    olderMessages: List<LocalHarnessMessage>,
    liveMessages: List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    if (olderMessages.isEmpty()) return liveMessages
    if (liveMessages.isEmpty()) return olderMessages

    val liveIds = liveMessages.mapTo(hashSetOf(), LocalHarnessMessage::id)
    val seen = hashSetOf<String>()
    return buildList(olderMessages.size + liveMessages.size) {
        olderMessages.forEach { message ->
            if (message.id !in liveIds && seen.add(message.id)) add(message)
        }
        liveMessages.forEach { message ->
            if (seen.add(message.id)) add(message)
        }
    }
}

internal fun buildLocalTranscript(
    messages: List<LocalHarnessMessage>,
    includeWorkProcess: Boolean = true,
): List<LocalTranscriptItem> {
    if (messages.isEmpty()) return emptyList()

    val result = mutableListOf<LocalTranscriptItem>()
    var index = 0
    while (index < messages.size) {
        if (messages[index].role == "system") {
            index += 1
            continue
        }
        if (!includeWorkProcess && messages[index].role == "reasoning") {
            val thinking = mutableListOf<LocalHarnessMessage>()
            while (index < messages.size && messages[index].role == "reasoning") {
                thinking += messages[index]
                index += 1
            }
            result += LocalTranscriptItem.Thinking(thinking)
            continue
        }

        if (!isWorkProcessMessage(messages, index)) {
            result += LocalTranscriptItem.Message(messages[index])
            index += 1
            continue
        }

        val work = mutableListOf<LocalHarnessMessage>()
        while (index < messages.size && isWorkProcessMessage(messages, index)) {
            work += messages[index]
            index += 1
        }
        if (includeWorkProcess) {
            result += LocalTranscriptItem.WorkProcess(work)
        }
    }
    return result
}

private fun isWorkProcessMessage(messages: List<LocalHarnessMessage>, index: Int): Boolean {
    val message = messages[index]
    if (message.role in WORK_PROCESS_ROLES) return true

    // Backward compatibility: older sessions persisted assistant content from every tool-using
    // model step as a normal assistant row. If a tool result follows before the next durable
    // user/system/final-assistant boundary, that assistant row was intermediate work text.
    if (message.role != "assistant") return false
    var cursor = index + 1
    while (cursor < messages.size) {
        when (messages[cursor].role) {
            "reasoning" -> cursor += 1
            "tool", "progress" -> return true
            else -> return false
        }
    }
    return false
}

private val WORK_PROCESS_ROLES = setOf("reasoning", "tool", "progress")
