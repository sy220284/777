package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaCreationJob
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem

/**
 * User-facing transcript projection.
 *
 * Reasoning, tool results and assistant text emitted alongside tool calls are implementation
 * details of one agent turn. They become separate lightweight UI rows so the
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

    /** One event per lazy-list row; the persisted transcript remains unchanged. */
    /** Sidecar media timeline rows are visible but never replayed as model/persona dialogue. */
    data class MediaResult(val result: LocalMediaResult) : LocalTranscriptItem {
        override val key: String = "media-result:${result.id}"
    }

    data class CreationJob(val job: LocalMediaCreationJob) : LocalTranscriptItem {
        override val key: String = "media-result:${job.id}"
    }

    data class VideoTask(val task: LocalMediaVideoTask) : LocalTranscriptItem {
        override val key: String = "media-video:${task.id}"
    }

    data class WorkArtifact(val artifact: LocalArtifactUiItem) : LocalTranscriptItem {
        override val key: String = "work-artifact:${artifact.category}:${artifact.reference}"
    }

    data class WorkNode(val node: LocalWorkProcessNode) : LocalTranscriptItem {
        override val key: String = "work-node:${node.eventId}"
    }

    data class WorkStep(
        val message: LocalHarnessMessage,
        val repeatCount: Int = 1,
    ) : LocalTranscriptItem {
        override val key: String = "work-step:${message.id}"
    }
}

/** Only recurring status polls are compacted; ordinary identical-looking calls stay distinct. */
internal fun isRepeatedWorkPollingTool(name: String?): Boolean {
    val normalized = name?.lowercase().orEmpty()
    return normalized.contains("workflow_run") || normalized.contains("workflow_job") ||
        normalized.contains("check_status") || normalized.startsWith("job_poll") ||
        normalized.endsWith("_poll") || normalized.endsWith("_status")
}

/** The UI has independent stable slots for long-running Work steps, not one giant column. */
internal fun buildLocalDisplayTranscript(items: List<LocalTranscriptItem>): List<LocalTranscriptItem> =
    buildList {
        items.forEach { item ->
            if (item !is LocalTranscriptItem.WorkProcess) {
                add(item)
                return@forEach
            }
            val nodes = conversationWorkProcessNodes(buildWorkProcessNodes(item.messages))
            nodes.forEach { add(LocalTranscriptItem.WorkNode(it)) }
            if (nodes.isEmpty() && item.messages.any { it.role == "reasoning" }) {
                add(item) // Only a generic thinking indicator; never display reasoning body.
            }
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

/**
 * Read the last dialogue / Work-process item, never trailing sidecars (artifacts, paid media,
 * background creation cards). Those sidecars may be appended after an already-durable final
 * answer, but they cannot reactivate a transient reply preview.
 */
internal fun localReplyStreamingBoundary(items: List<LocalTranscriptItem>): LocalTranscriptItem? =
    items.lastOrNull { item ->
        item is LocalTranscriptItem.Message ||
            item is LocalTranscriptItem.Thinking ||
            item is LocalTranscriptItem.WorkProcess ||
            item is LocalTranscriptItem.WorkNode ||
            item is LocalTranscriptItem.WorkStep
    }

/**
 * A durable final assistant answer already owns the tail. It must not be duplicated by the
 * transient streaming preview while the execution coordinator is closing the turn.
 */
internal fun shouldShowWorkStreamingTail(
    running: Boolean,
    usageMode: com.labteto.dshmobile.local.LocalUsageMode,
    lastItem: LocalTranscriptItem?,
): Boolean = running && !(usageMode == com.labteto.dshmobile.local.LocalUsageMode.WORK &&
    (lastItem as? LocalTranscriptItem.Message)?.message?.role == "assistant")

private val WORK_PROCESS_ROLES = setOf("reasoning", "tool", "progress")

// Insert paid media results into the conversation timeline without polluting model history.
// Legacy unassigned archive items remain in Media Studio, never leak across sessions.
internal fun mergeLocalMediaTimeline(
    transcript: List<LocalTranscriptItem>,
    sessionId: String,
    results: List<LocalMediaResult>,
    videoTasks: List<LocalMediaVideoTask>,
    jobs: List<LocalMediaCreationJob> = emptyList(),
): List<LocalTranscriptItem> {
    if (sessionId.isBlank()) return transcript
    val delivered = results.filter { it.sessionId == sessionId }
    val finishedIds = delivered.mapTo(mutableSetOf()) { it.id }
    val media = delivered.map { LocalTranscriptItem.MediaResult(it) } +
        jobs.filter { it.sessionId == sessionId && it.id !in finishedIds }
            .map { LocalTranscriptItem.CreationJob(it) } +
        videoTasks.filter { it.sessionId == sessionId }.map { LocalTranscriptItem.VideoTask(it) }
    if (media.isEmpty()) return transcript
    // A pending media card must not jump below newer chat messages after completion.
    val operationTimestamps = jobs.associate { it.id to it.createdAtMillis }
    fun timestamp(item: LocalTranscriptItem): Long = when (item) {
        is LocalTranscriptItem.Message -> item.message.createdAt
        is LocalTranscriptItem.Thinking -> item.messages.first().createdAt
        is LocalTranscriptItem.WorkProcess -> item.messages.first().createdAt
        is LocalTranscriptItem.WorkStep -> item.message.createdAt
        is LocalTranscriptItem.WorkNode -> item.node.createdAt
        is LocalTranscriptItem.MediaResult ->
            operationTimestamps[item.result.id] ?: item.result.createdAtMillis
        is LocalTranscriptItem.CreationJob -> item.job.createdAtMillis
        is LocalTranscriptItem.VideoTask -> item.task.startedAtMillis
        is LocalTranscriptItem.WorkArtifact -> Long.MAX_VALUE
    }
    // Stable sort preserves original transcript order when event timestamps tie.
    return (transcript + media).sortedBy(::timestamp)
}

// Work products should be directly reachable from the conversation, not only its Output tab.
// Existing event-derived artifact facts remain the sole source, without copying file bytes.
internal fun appendWorkArtifactsToTranscript(
    transcript: List<LocalTranscriptItem>,
    artifacts: List<LocalArtifactUiItem>,
): List<LocalTranscriptItem> {
    // Prefer the latest state of a file. Keep the lazy timeline complete; Work's
    // dedicated artifact browser remains an index, not the only way to recover results.
    val unique = artifacts.filter { it.reference.isNotBlank() }.asReversed()
        .distinctBy { it.category to it.reference }.asReversed()
    return transcript + unique.map(LocalTranscriptItem::WorkArtifact)
}
