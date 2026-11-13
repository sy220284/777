package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * Chat-owned foreground transcript projection.
 *
 * Chat 不承接工具结果限界；这里仅负责 Chat 消息创建、durable event transcript 附加与前台 cursor 投影。
 */
@Singleton
internal class LocalChatTranscriptRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    internal fun newMessage(
        role: String,
        content: String,
        speakerId: String? = null,
        speakerName: String? = null,
    ): LocalHarnessMessage {
        require(role != "tool") { "Chat transcript runtime 不接管工具结果投影" }
        return LocalHarnessMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            speakerId = speakerId,
            speakerName = speakerName,
            createdAt = System.currentTimeMillis(),
        )
    }

    internal fun withTranscript(
        data: JsonObject,
        messages: List<LocalHarnessMessage>,
    ): JsonObject = if (messages.isEmpty()) data else JsonObject(
        data + ("transcript" to encodeTranscriptMessages(messages)),
    )

    internal fun applyMessages(
        sessionId: String,
        messages: List<LocalHarnessMessage>,
        eventSequence: Long,
    ) {
        runtimeStateStore.projection.appendForegroundTranscript(
            sessionId = sessionId,
            messages = messages,
        )
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
            maxOf(
                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                eventSequence,
            )
    }
}
