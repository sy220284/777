package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.ATTACHMENT_INVALID
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.QueueAction
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SessionUpdateQueueRequest
import com.labteto.dshmobile.core.wire.dto.imageRejectionOf
import com.labteto.dshmobile.core.wire.newPromptRequestId
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Owns prompt submission, turn cancellation and queued-input mutation for remote sessions. */
internal class SessionTurnCommandRuntime(
    private val apiForHost: (String?) -> DshApiClient?,
    private val apiProvider: () -> DshApiClient?,
    private val currentSessionId: () -> String?,
    private val activeHostKey: () -> String?,
    private val onConnectionError: (String?) -> Unit,
) {
    suspend fun prompt(
        text: String,
        mode: String,
        targetSessionId: String? = currentSessionId(),
        targetHost: String? = activeHostKey(),
    ): PromptOutcome =
        promptContent(mode, listOf(PromptContentPart.Text(text)), targetSessionId, targetHost)

    suspend fun promptWithAttachments(
        text: String,
        mode: String,
        images: List<EncodedImageAttachment>,
        fileReceipts: List<String> = emptyList(),
        targetSessionId: String? = currentSessionId(),
        targetHost: String? = activeHostKey(),
    ): PromptOutcome {
        val parts = mutableListOf<PromptContentPart>()
        if (text.isNotBlank()) parts.add(PromptContentPart.Text(text))
        images.mapTo(parts) { PromptContentPart.Image(it.mediaType, it.data, it.name) }
        fileReceipts.mapTo(parts) { PromptContentPart.File(it) }
        return promptContent(mode, parts, targetSessionId, targetHost)
    }

    suspend fun cancelTurn() {
        val sessionId = currentSessionId() ?: return
        val api = apiProvider() ?: return
        when (val result = api.sessionCancel(SessionCancelRequest(sessionId))) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> onConnectionError(result.error.message)
        }
    }

    suspend fun updateQueue(
        itemId: String,
        action: String,
        contentText: String? = null,
        sessionId: String? = currentSessionId(),
    ): Boolean {
        if (action == "edit" && contentText.isNullOrBlank()) return false
        val targetSessionId = sessionId ?: return false
        val api = apiProvider() ?: return false
        val queueAction: QueueAction = when (action) {
            "remove" -> QueueAction.Remove()
            "steer" -> QueueAction.Steer()
            else -> QueueAction.Edit(listOf(ContentBlock.Text(contentText.orEmpty())))
        }
        return when (
            val result = api.sessionUpdateQueue(
                SessionUpdateQueueRequest(targetSessionId, itemId, queueAction),
            )
        ) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> {
                onConnectionError(result.error.message)
                false
            }
        }
    }

    private suspend fun promptContent(
        mode: String,
        content: List<PromptContentPart>,
        targetSessionId: String?,
        targetHost: String?,
    ): PromptOutcome {
        val sessionId = targetSessionId ?: return PromptOutcome.Failed("no open session")
        val api = apiForHost(targetHost) ?: return PromptOutcome.Failed("not connected")
        val request = SessionPromptRequest(
            requestId = newPromptRequestId(),
            sessionId = sessionId,
            mode = if (mode == "steer") "steer" else "queue",
            content = content,
            clientTimeZone = TimeZone.getDefault().id,
        )
        return when (val result = api.sessionPrompt(request)) {
            is RpcResult.Ok -> PromptOutcome.Ok
            is RpcResult.Err -> if (result.error.code == ATTACHMENT_INVALID) {
                val reason = (result.error.details as? JsonObject)
                    ?.get("reason")
                    ?.jsonPrimitive
                    ?.contentOrNull
                PromptOutcome.Rejected(imageRejectionOf(reason.orEmpty()), reason)
            } else {
                onConnectionError(result.error.message)
                PromptOutcome.Failed(result.error.message)
            }
        }
    }
}
