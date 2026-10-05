package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalHarnessState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * Owns the bounded UI transcript projection.
 *
 * Durable transcript facts still live in Session Event; this runtime only maintains the hot window
 * shown by LocalHarnessState and advances the caller-owned durable projection cursor.
 */
internal interface LocalTranscriptStatePort {
    fun appendMessages(messages: List<LocalHarnessMessage>, runtimeWindowMessages: Int)
}

internal fun localAggregateTranscriptStatePort(
    state: MutableStateFlow<LocalHarnessState>,
): LocalTranscriptStatePort = object : LocalTranscriptStatePort {
    override fun appendMessages(messages: List<LocalHarnessMessage>, runtimeWindowMessages: Int) {
        state.update { current ->
            current.copy(
                messages = (current.messages + messages).takeLast(runtimeWindowMessages),
                transcriptIndex = appendLocalTranscriptRuntimeIndex(current.transcriptIndex, messages),
            )
        }
    }
}

internal class LocalTranscriptRuntime(
    private val state: LocalTranscriptStatePort,
    private val pruneToolResult: (String) -> String,
    private val runtimeWindowMessages: Int,
    private val onProjected: (Long) -> Unit,
) {
    constructor(
        state: MutableStateFlow<LocalHarnessState>,
        pruneToolResult: (String) -> String,
        runtimeWindowMessages: Int,
        onProjected: (Long) -> Unit,
    ) : this(
        state = localAggregateTranscriptStatePort(state),
        pruneToolResult = pruneToolResult,
        runtimeWindowMessages = runtimeWindowMessages,
        onProjected = onProjected,
    )

    fun newMessage(
        role: String,
        content: String,
        toolName: String? = null,
        speakerId: String? = null,
        speakerName: String? = null,
        contentAlreadyBounded: Boolean = false,
        toolIsError: Boolean? = null,
        toolErrorCode: String? = null,
    ): LocalHarnessMessage = LocalHarnessMessage(
        id = UUID.randomUUID().toString(),
        role = role,
        content = if (role == "tool" && !contentAlreadyBounded) pruneToolResult(content) else content,
        toolName = toolName,
        toolIsError = toolIsError,
        toolErrorCode = toolErrorCode,
        speakerId = speakerId,
        speakerName = speakerName,
        createdAt = System.currentTimeMillis(),
    )

    fun withTranscript(
        data: JsonObject,
        messages: List<LocalHarnessMessage>,
    ): JsonObject = if (messages.isEmpty()) data else JsonObject(
        data + ("transcript" to encodeTranscriptMessages(messages)),
    )

    fun applyMessages(
        messages: List<LocalHarnessMessage>,
        eventSequence: Long,
    ) {
        if (messages.isNotEmpty()) {
            state.appendMessages(messages, runtimeWindowMessages)
        }
        onProjected(eventSequence)
    }
}
