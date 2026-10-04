package com.labteto.dshmobile.local

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
internal class LocalTranscriptRuntime(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val pruneToolResult: (String) -> String,
    private val runtimeWindowMessages: Int,
    private val onProjected: (Long) -> Unit,
) {
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
            state.update { current ->
                current.copy(
                    messages = (current.messages + messages).takeLast(runtimeWindowMessages),
                    transcriptIndex = appendLocalTranscriptRuntimeIndex(current.transcriptIndex, messages),
                )
            }
        }
        onProjected(eventSequence)
    }
}
