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
    data class MediaResult(
        val result: LocalMediaResult,
        val slotId: String = result.id,
        val revisionJob: LocalMediaCreationJob? = null,
    ) : LocalTranscriptItem {
        override val key: String = "media-result:${slotId}"
    }

    data class CreationJob(val job: LocalMediaCreationJob, val slotId: String = job.id) : LocalTranscriptItem {
        override val key: String = "media-result:${slotId}"
    }

    data class VideoTask(val task: LocalMediaVideoTask, val slotId: String = task.id) : LocalTranscriptItem {
        override val key: String = "media-video:${slotId}"
    }

    data class WorkArtifact(val artifact: LocalArtifactUiItem) : LocalTranscriptItem {
        override val key: String = "work-artifact:${artifact.category}:${artifact.reference}"
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
            var visibleCount = 0
            item.messages.forEach { message ->
                if (message.role !in setOf("tool", "progress", "assistant")) return@forEach
                if (message.role != "tool" && localWorkTimelineText(message.content) == null) {
                    return@forEach
                }
                val previous = lastOrNull() as? LocalTranscriptItem.WorkStep
                val samePoll = message.role == "tool" && previous?.message?.role == "tool" &&
                    message.toolName == previous.message.toolName &&
                    // Same action name alone is insufficient: parallel jobs may use one API.
                    message.content == previous.message.content &&
                    isRepeatedWorkPollingTool(message.toolName) &&
                    (message.toolIsError ?: localWorkToolFailed(message.content)) ==
                    (previous.message.toolIsError ?: localWorkToolFailed(previous.message.content))
                if (samePoll && previous != null) {
                    set(lastIndex, previous.copy(repeatCount = previous.repeatCount + 1))
                } else {
                    add(LocalTranscriptItem.WorkStep(message))
                    visibleCount++
                }
            }
            if (visibleCount == 0 && item.messages.any { it.role == "reasoning" }) {
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
    val sessionJobs = jobs.filter { it.sessionId == sessionId }
    val sessionVideos = videoTasks.filter { it.sessionId == sessionId }
    val kinds = buildMap<String, String> {
        delivered.forEach { put(it.id, it.kind) }
        sessionJobs.forEach { put(it.id, it.kind) }
        sessionVideos.forEach { put(it.id, "VIDEO") }
    }
    val parents = buildMap<String, String> {
        delivered.forEach { it.revisionOf?.let { parent -> put(it.id, parent) } }
        sessionJobs.forEach { it.revisionOf?.let { parent -> put(it.id, parent) } }
        sessionVideos.forEach { it.revisionOf?.let { parent -> put(it.id, parent) } }
    }
    // Same-kind revisions replace the visible slot, never duplicate it.
    fun root(id: String): String {
        var current = id
        val seen = mutableSetOf(current)
        repeat(64) {
            val parent = parents[current] ?: return current
            if (kinds[parent] != kinds[id] || !seen.add(parent)) return current
            current = parent
        }
        return current
    }
    val earliest = mutableMapOf<String, Long>()
    val operations = sessionJobs.associate { it.id to it.createdAtMillis }
    fun track(id: String, time: Long) {
        val key = root(id)
        earliest[key] = minOf(earliest[key] ?: Long.MAX_VALUE, time)
    }
    delivered.forEach { track(it.id, operations[it.id] ?: it.createdAtMillis) }
    sessionJobs.forEach { track(it.id, it.createdAtMillis) }
    sessionVideos.forEach { track(it.id, it.startedAtMillis) }
    val media = buildList {
        val successful = delivered.groupBy { root(it.id) }
        val unfinished = sessionJobs.filter { job -> delivered.none { it.id == job.id } }
            .groupBy { root(it.id) }
        (successful.keys + unfinished.keys).forEach { slot ->
            val completed = successful[slot]?.maxWithOrNull(compareBy<LocalMediaResult> { it.createdAtMillis }.thenBy { it.id })
            val newest = unfinished[slot]?.maxWithOrNull(compareBy<LocalMediaCreationJob> { it.createdAtMillis }.thenBy { it.id })
            if (completed != null) {
                // Once a newer version succeeds, an older failed attempt cannot reappear
                // as a stale error under the new audio.
                val completedAt = operations[completed.id] ?: completed.createdAtMillis
                add(LocalTranscriptItem.MediaResult(completed, slot,
                    newest?.takeIf { it.createdAtMillis > completedAt }))
            }
            else if (newest != null) add(LocalTranscriptItem.CreationJob(newest, slot))
        }
        sessionVideos.groupBy { root(it.id) }.forEach { (slot, versions) ->
            val latest = versions.maxWithOrNull(compareBy<LocalMediaVideoTask> { it.startedAtMillis }.thenBy { it.id })
            if (latest != null) add(LocalTranscriptItem.VideoTask(latest, slot))
        }
    }
    if (media.isEmpty()) return transcript
    fun timestamp(item: LocalTranscriptItem): Long = when (item) {
        is LocalTranscriptItem.Message -> item.message.createdAt
        is LocalTranscriptItem.Thinking -> item.messages.first().createdAt
        is LocalTranscriptItem.WorkProcess -> item.messages.first().createdAt
        is LocalTranscriptItem.WorkStep -> item.message.createdAt
        is LocalTranscriptItem.MediaResult -> earliest[item.slotId] ?: item.result.createdAtMillis
        is LocalTranscriptItem.CreationJob -> earliest[item.slotId] ?: item.job.createdAtMillis
        is LocalTranscriptItem.VideoTask -> earliest[item.slotId] ?: item.task.startedAtMillis
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

/** Message anchors survive pack layouts and Work row compaction; sidecars keep their own identity. */
internal fun LocalTranscriptItem.readingMessageId(): String? = when (this) {
    is LocalTranscriptItem.Message -> message.id
    is LocalTranscriptItem.Thinking -> messages.first().id
    is LocalTranscriptItem.WorkProcess -> messages.first().id
    is LocalTranscriptItem.WorkStep -> message.id
    else -> null
}

internal fun LocalTranscriptItem.containsReadingMessage(messageId: String?): Boolean =
    messageId != null && when (this) {
        is LocalTranscriptItem.Thinking -> messages.any { it.id == messageId }
        is LocalTranscriptItem.WorkProcess -> messages.any { it.id == messageId }
        else -> readingMessageId() == messageId
    }
