package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.send.prepareLocalSend
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.buildLocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import kotlinx.serialization.json.JsonObject

/** Queue and visible timeline are committed in one EventLog record while queue CAS owns admission. */
internal const val LOCAL_QUEUED_USER_TIMELINE_ACTION = "queued-user-timeline"

internal data class LocalQueuedUserTimelineUpdate(
    val messages: List<LocalHarnessMessage>,
    val transcriptIndex: LocalTranscriptRuntimeIndex,
    val eventSequence: Long,
    val pendingCount: Int,
) {
    fun hotMessages(): List<LocalHarnessMessage> = messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)
}

/**
 * User operations on an unconsumed input.
 * The caller must own the selected Session's run lock and publish the returned projection.
 * History is captured before the queue CAS; a concurrent transcript append invalidates admission.
 */
internal class LocalQueuedUserMessageEditor(
    private val eventLog: LocalSessionEventLog,
    private val queue: AgentInputQueue,
) {
    fun edit(messageId: String, newText: String): LocalQueuedUserTimelineUpdate? {
        require(messageId.isNotBlank()) { "排队消息编号不能为空" }
        val queued = queue.snapshot().firstOrNull {
            it.id == messageId && it.kind == QueuedAgentInputKind.USER
        } ?: return null
        val beforeSequence = eventLog.latestSequence()
        val timeline = LocalSessionTranscriptPager(eventLog).all()
        val index = timeline.indexOfFirst { it.id == messageId && it.role == "user" }
        require(index >= 0) { "排队消息缺少持久对话记录，已停止改写" }
        val original = timeline[index]
        require(original.blocks.none { it is LocalMessageBlock.Unknown }) {
            "排队消息中存在无法安全重建的附件类型"
        }
        val attachments = original.blocks.mapNotNull { block ->
            when (block) {
                is LocalMessageBlock.Image -> LocalImportedAttachment(
                    block.name, block.relativePath, block.mediaType, block.bytes,
                    block.attachmentId, block.width, block.height,
                )
                is LocalMessageBlock.File -> LocalImportedAttachment(
                    block.name, block.relativePath, block.mediaType, block.bytes, block.attachmentId,
                )
                else -> null
            }
        }
        val prepared = requireNotNull(prepareLocalSend(newText, attachments)) {
            "排队消息内容不能为空"
        }
        require(original.content != prepared.visibleContent || queued.content != prepared.content) {
            "排队消息内容未发生变化"
        }
        val revised = queued.copy(
            content = prepared.content,
            memoryInput = prepared.memoryInput,
            modelMessage = prepared.modelMessage,
        )
        val messages = timeline.toMutableList().apply {
            set(index, original.copy(content = prepared.visibleContent, blocks = prepared.blocks))
        }
        var eventSequence: Long? = null
        if (!queue.replaceUserCommitted(queued, revised) { pending ->
            check(eventLog.latestSequence() == beforeSequence) {
                "排队消息编辑期间发生新的对话事件，请刷新后重试"
            }
            val event = eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                JsonObject(encodeLocalAgentInboxEvent(
                    action = LOCAL_QUEUED_USER_TIMELINE_ACTION,
                    pending = pending,
                    affected = listOf(revised),
                ) + ("transcript" to encodeTranscriptMessages(messages))),
            )
            eventSequence = event.sequence
        }) return null
        return LocalQueuedUserTimelineUpdate(
            messages = messages,
            transcriptIndex = buildLocalTranscriptRuntimeIndex(messages),
            eventSequence = requireNotNull(eventSequence),
            pendingCount = queue.size(),
        )
    }

    fun remove(messageId: String): LocalQueuedUserTimelineUpdate? {
        require(messageId.isNotBlank()) { "排队消息编号不能为空" }
        val queued = queue.snapshot().firstOrNull {
            it.id == messageId && it.kind == QueuedAgentInputKind.USER
        } ?: return null
        val beforeSequence = eventLog.latestSequence()
        val timeline = LocalSessionTranscriptPager(eventLog).all()
        require(timeline.any { it.id == messageId && it.role == "user" }) {
            "排队消息没有可核实的原始记录"
        }
        val messages = timeline.filterNot { it.id == messageId }
        var eventSequence: Long? = null
        if (!queue.removeUserCommitted(messageId) { pending ->
            check(eventLog.latestSequence() == beforeSequence) {
                "排队消息移除期间发生新的对话事件，请刷新后重试"
            }
            val event = eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                JsonObject(encodeLocalAgentInboxEvent(
                    action = LOCAL_QUEUED_USER_TIMELINE_ACTION,
                    pending = pending,
                    affected = listOf(queued),
                ) + ("transcript" to encodeTranscriptMessages(messages))),
            )
            eventSequence = event.sequence
        }) return null
        return LocalQueuedUserTimelineUpdate(
            messages = messages,
            transcriptIndex = buildLocalTranscriptRuntimeIndex(messages),
            eventSequence = requireNotNull(eventSequence),
            pendingCount = queue.size(),
        )
    }

    fun reorder(orderedUserIds: List<String>): Boolean =
        queue.reorderUsersCommitted(orderedUserIds) { pending ->
            eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(action = "queued-user-reordered", pending = pending),
            )
        }
}
