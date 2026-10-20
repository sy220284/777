package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.model.LocalMediaCreationJob
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase

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

    /** One event per lazy-list row; the persisted transcript remains unchanged. */
    /** Sidecar media timeline rows are visible but never replayed as model/persona dialogue. */
    data class MediaResult(
        val result: LocalMediaResult,
        /** Visual attachment identity; null keeps independent historical/media-studio rows. */
        val attachedTo: String? = null,
    ) : LocalTranscriptItem {
        override val key: String = "media-result:${result.id}"
    }

    data class CreationJob(
        val job: LocalMediaCreationJob,
        val attachedTo: String? = null,
    ) : LocalTranscriptItem {
        override val key: String = "media-result:${job.id}"
    }

    data class VideoTask(
        val task: LocalMediaVideoTask,
        val attachedTo: String? = null,
    ) : LocalTranscriptItem {
        override val key: String = "media-video:${task.id}"
    }

    data class WorkArtifact(
        val artifact: LocalArtifactUiItem,
        val attachedTo: String? = null,
    ) : LocalTranscriptItem {
        override val key: String = "work-artifact:${artifact.category}:${artifact.reference}"
    }

    data class WorkStep(
        val message: LocalHarnessMessage,
        val repeatCount: Int = 1,
        val activity: LocalToolActivityUiItem? = null,
        val groupedMessages: List<LocalHarnessMessage> = emptyList(),
    ) : LocalTranscriptItem {
        override val key: String =
            message.toolCallId?.takeIf { message.role == "tool" }?.let { "work-tool:$it" }
                ?: "work-step:${message.id}"
    }

    /** In-flight Tool-owned event. Replaced by the completion row with the same stable key. */
    data class WorkPendingTool(val activity: LocalToolActivityUiItem) : LocalTranscriptItem {
        override val key: String = "work-tool:${activity.callId}"
    }
}

/** Only recurring status polls are compacted; ordinary identical-looking calls stay distinct. */
internal fun isRepeatedWorkPollingTool(name: String?): Boolean {
    val normalized = name?.lowercase().orEmpty()
    return normalized.contains("workflow_run") || normalized.contains("workflow_job") ||
        normalized.contains("check_status") || normalized.startsWith("job_poll") ||
        normalized.endsWith("_poll") || normalized.endsWith("_status")
}

/**
 * Work events are rendered directly as standalone WorkStep rows.
 * Keep the original tool declaration order without combining independent operations.
 */
private fun orderedWorkSteps(messages: List<LocalHarnessMessage>): List<LocalTranscriptItem.WorkStep> {
    val ordered = messages.filter { it.role != "reasoning" }.toMutableList()
    val completed = ordered.filter { it.role == "tool" && it.toolDeclaredAt != null }
        .sortedWith(compareBy<LocalHarnessMessage> { it.toolDeclaredAt ?: Long.MAX_VALUE }
            .thenBy { it.toolDeclaredSequence ?: Long.MAX_VALUE })
    ordered.removeAll { it.role == "tool" && it.toolDeclaredAt != null }
    completed.forEach { tool ->
        val at = tool.toolDeclaredAt ?: Long.MAX_VALUE
        val target = ordered.indexOfFirst { other ->
            val otherAt = if (other.role == "tool") other.toolDeclaredAt ?: other.createdAt
                else other.createdAt
            otherAt > at
        }
        ordered.add(if (target < 0) ordered.size else target, tool)
    }
    return ordered.mapNotNull { message ->
        when (message.role) {
            "tool" -> LocalTranscriptItem.WorkStep(message)
            "progress", "assistant" ->
                if (localWorkTimelineText(message.content) != null) LocalTranscriptItem.WorkStep(message)
                else null
            else -> null
        }
    }
}

/** Only genuinely unfinished calls may appear as transient live rows. */
internal fun appendPendingWorkToolActivities(
    transcript: List<LocalTranscriptItem>,
    activities: List<LocalToolActivityUiItem>,
    running: Boolean,
): List<LocalTranscriptItem> {
    // The declaration has the true command arguments. Attach them to the durable completion
    // by callId so a completed step retains the same natural-language description as in flight.
    val byCallId = activities.associateBy { it.callId }
    val enriched = transcript.map { item ->
        if (item is LocalTranscriptItem.WorkStep && item.message.role == "tool") {
            item.copy(activity = item.message.toolCallId?.let(byCallId::get) ?: item.activity)
        } else item
    }
    if (!running) return compactRepeatedWorkStatusChecks(enriched)
    val completedIds = enriched.filterIsInstance<LocalTranscriptItem.WorkStep>()
        .flatMap { step ->
            (listOf(step.message) + step.groupedMessages).mapNotNull { it.toolCallId }
        }.toSet()
    val lastUserAt = transcript.filterIsInstance<LocalTranscriptItem.Message>()
        .lastOrNull { it.message.role == "user" }?.message?.createdAt ?: Long.MIN_VALUE
    val pending = activities.filter {
        it.phase == LocalToolUiPhase.DECLARED || it.phase == LocalToolUiPhase.RUNNING
    }.filter { it.callId !in completedIds && (it.declaredAt ?: Long.MIN_VALUE) >= lastUserAt }
        .distinctBy { it.callId }.sortedWith(
            compareBy<LocalToolActivityUiItem> { it.declaredAt ?: Long.MAX_VALUE }
                .thenBy { it.declaredSequence ?: Long.MAX_VALUE }
        )
    if (pending.isEmpty()) return compactRepeatedWorkStatusChecks(enriched)
    // Insert at the call's original time instead of appending below newer narration,
    // completed replies or deliverables. Never reorder existing durable messages.
    val result = enriched.toMutableList()
    pending.forEach { activity ->
        val timestamp = activity.declaredAt ?: Long.MAX_VALUE
        val index = result.indexOfFirst { item ->
            when (item) {
                is LocalTranscriptItem.Message -> item.message.createdAt > timestamp
                is LocalTranscriptItem.WorkStep -> {
                    val at = item.message.toolDeclaredAt ?: item.message.createdAt
                    at > timestamp || (at == timestamp && item.message.role == "tool" &&
                        (item.message.toolDeclaredSequence ?: Long.MAX_VALUE) >
                        (activity.declaredSequence ?: Long.MAX_VALUE))
                }
                is LocalTranscriptItem.Thinking -> item.messages.first().createdAt > timestamp
                is LocalTranscriptItem.WorkPendingTool -> {
                    val at = item.activity.declaredAt ?: Long.MAX_VALUE
                    at > timestamp || (at == timestamp &&
                        (item.activity.declaredSequence ?: Long.MAX_VALUE) >
                        (activity.declaredSequence ?: Long.MAX_VALUE))
                }
                is LocalTranscriptItem.MediaResult -> item.result.createdAtMillis > timestamp
                is LocalTranscriptItem.CreationJob -> item.job.createdAtMillis > timestamp
                is LocalTranscriptItem.VideoTask -> item.task.startedAtMillis > timestamp
                is LocalTranscriptItem.WorkArtifact -> true
                else -> false
            }
        }
        result.add(if (index < 0) result.size else index, LocalTranscriptItem.WorkPendingTool(activity))
    }
    return compactRepeatedWorkStatusChecks(result)
}

/**
 * Compact only consecutive, successful status checks of the same tool AND arguments.
 * Every result is retained in groupedMessages and the expandable receipt. Other tool
 * calls (and any uncertain/failed/cancelled result) always keep their own row.
 */
internal fun compactRepeatedWorkStatusChecks(
    items: List<LocalTranscriptItem>,
): List<LocalTranscriptItem> {
    val compact = mutableListOf<LocalTranscriptItem>()
    items.forEach { item ->
        val current = item as? LocalTranscriptItem.WorkStep
        val previous = compact.lastOrNull() as? LocalTranscriptItem.WorkStep
        val canGroup = current != null && previous != null &&
            current.message.role == "tool" && previous.message.role == "tool" &&
            isRepeatedWorkPollingTool(current.message.toolName) &&
            current.message.toolName == previous.message.toolName &&
            current.message.toolIsError == false && previous.message.toolIsError == false &&
            current.message.toolErrorCode == null && previous.message.toolErrorCode == null &&
            current.activity?.argumentsPreview != null &&
            current.activity.argumentsPreview == previous.activity?.argumentsPreview
        if (canGroup) {
            compact[compact.lastIndex] = previous!!.copy(
                repeatCount = previous.repeatCount + 1,
                groupedMessages = previous.groupedMessages + current!!.message,
            )
        } else {
            compact += item
        }
    }
    return compact
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
            if (includeWorkProcess && (messages[index].content.startsWith("执行失败：") ||
                    messages[index].content == "本轮已停止。" ||
                    messages[index].content.startsWith("当前任务已无法继续扩展执行预算，"))) {
                result += LocalTranscriptItem.Message(messages[index])
            }
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
            val steps = orderedWorkSteps(work)
            if (steps.isNotEmpty()) result += steps
            else if (work.any { it.role == "reasoning" }) {
                result += LocalTranscriptItem.Thinking(work.filter { it.role == "reasoning" })
            }
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
 * Deduplicate streaming preview against the authoritative durable text from the active user
 * turn, and never reuse a
 * previous user turn's final answer as a prefix of this turn's stream.
 */
internal fun latestDurableWorkNarrativeInCurrentTurn(
    messages: List<LocalHarnessMessage>,
): String? {
    val lastUser = messages.indexOfLast { it.role == "user" }
    for (index in messages.lastIndex downTo (lastUser + 1)) {
        val item = messages[index]
        if (item.role == "progress" || item.role == "assistant") return item.content
    }
    return null
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
            item is LocalTranscriptItem.WorkPendingTool ||
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
        is LocalTranscriptItem.WorkStep ->
            if (item.message.role == "tool") item.message.toolDeclaredAt ?: item.message.createdAt
            else item.message.createdAt
        is LocalTranscriptItem.MediaResult ->
            operationTimestamps[item.result.id] ?: item.result.createdAtMillis
        is LocalTranscriptItem.CreationJob -> item.job.createdAtMillis
        is LocalTranscriptItem.VideoTask -> item.task.startedAtMillis
        is LocalTranscriptItem.WorkPendingTool -> Long.MAX_VALUE
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

/**
 * A single reply can own text, generated media and verified work artifacts.
 *
 * Keep every attachment as its own LazyColumn item for virtualization and stable save/player
 * state; attachedTo is its presentation owner, not a second persisted message or media copy.
 * Do not infer owners from timestamps: legacy/unassigned media and artifacts remain visible.
 */
internal fun composeLocalReplyAttachments(
    timeline: List<LocalTranscriptItem>,
): List<LocalTranscriptItem> {
    val messageOwners = timeline.filterIsInstance<LocalTranscriptItem.Message>()
        .filter { it.message.role == "assistant" || it.message.role == "user" }
        .associate { it.message.id to it.key }
    val toolOwners = timeline.filterIsInstance<LocalTranscriptItem.WorkStep>()
        .filter { it.message.role == "tool" && it.message.toolCallId != null }
        .associate { it.message.toolCallId!! to it.key }
    if (messageOwners.isEmpty() && toolOwners.isEmpty()) return timeline

    val attachments = linkedMapOf<String, MutableList<LocalTranscriptItem>>()
    val unowned = ArrayList<LocalTranscriptItem>(timeline.size)
    timeline.forEach { item ->
        val ownerKey = when (item) {
            is LocalTranscriptItem.MediaResult -> item.result.sourceMessageId?.let(messageOwners::get)
            is LocalTranscriptItem.CreationJob -> item.job.sourceMessageId?.let(messageOwners::get)
            is LocalTranscriptItem.VideoTask -> item.task.sourceMessageId?.let(messageOwners::get)
            is LocalTranscriptItem.WorkArtifact -> item.artifact.sourceCallId?.let(toolOwners::get)
            else -> null
        }
        if (ownerKey == null) {
            unowned += item
        } else {
            val attached = when (item) {
                is LocalTranscriptItem.MediaResult -> item.copy(attachedTo = ownerKey)
                is LocalTranscriptItem.CreationJob -> item.copy(attachedTo = ownerKey)
                is LocalTranscriptItem.VideoTask -> item.copy(attachedTo = ownerKey)
                is LocalTranscriptItem.WorkArtifact -> item.copy(attachedTo = ownerKey)
                else -> item
            }
            attachments.getOrPut(ownerKey) { mutableListOf() } += attached
        }
    }
    if (attachments.isEmpty()) return timeline
    return buildList(timeline.size) {
        unowned.forEach { item ->
            add(item)
            attachments[item.key]?.let { addAll(it) }
        }
    }
}
