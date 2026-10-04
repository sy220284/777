package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatTurnCoordinator
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.hasChatBranchAlternatives
import com.labteto.dshmobile.local.updateChatBranchNodeSnapshot
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns the model-assisted reply-suggestion transaction end to end: frozen route resolution,
 * protocol-safe input construction, parsing, stale-result rejection, diagnostics and state commit.
 */
internal class LocalReplySuggestionCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val modelGateway: LocalModelGateway,
    private val requestModel: suspend (
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        eventLog: LocalSessionEventLog,
        profile: LocalModelProfile,
    ) -> LocalModelReply,
    private val recordUsage: (LocalHarnessState, LocalModelReply) -> Unit,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val persistBranchState: (String) -> Unit,
    private val persist: () -> Unit,
) {
    suspend fun generate(): Boolean {
        val snapshot = state.value
        if (
            snapshot.loading ||
            !snapshot.configured ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled
        ) return false

        val assistantMessage = snapshot.messages.lastOrNull { message ->
            message.role == "assistant" && message.content.isNotBlank()
        } ?: return false
        val expectedSessionId = snapshot.sessionId
        val expectedAssistantMessageId = assistantMessage.id
        val boundEventLog = eventLogFor(expectedSessionId)
        val profile = try {
            modelGateway.profileForRoute(
                profileId = snapshot.modelState.modelSelection.activeProfileId,
                model = snapshot.modelState.model,
                baseUrl = snapshot.modelState.baseUrl,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateError(
                expectedSessionId,
                expectedAssistantMessageId,
                error.message?.takeIf(String::isNotBlank) ?: "回复建议需要可用的模型账户或 API Key",
            )
            return false
        }

        val prompt = chatTurnCoordinator.replySuggestionsPrompt(
            persona = snapshot.chatPersona,
            state = snapshot.chatState,
            messages = snapshot.messages,
            latestAssistantMessageId = expectedAssistantMessageId,
        )
        val reply = try {
            requestModel(
                snapshot,
                chatReplySuggestionModelMessages(prompt),
                boundEventLog,
                profile,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
            })
            updateError(
                expectedSessionId,
                expectedAssistantMessageId,
                error.message?.takeIf(String::isNotBlank) ?: "回复建议生成失败，请重试",
            )
            return false
        }
        recordUsage(snapshot, reply)

        val suggestions = chatTurnCoordinator.parseReplySuggestions(reply.content.orEmpty())
        if (suggestions.isNullOrEmpty()) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "parse-failed")
                put("content", reply.content.orEmpty().take(2_000))
            })
            updateError(expectedSessionId, expectedAssistantMessageId, "回复建议返回格式异常，请重试")
            return false
        }

        var applied = false
        state.update { current ->
            if (
                current.sessionId != expectedSessionId ||
                current.usageMode != LocalUsageMode.CHAT ||
                current.groupChat.enabled ||
                current.transcriptIndex.latestDialogueMessageId != expectedAssistantMessageId
            ) {
                current
            } else {
                applied = true
                current.copy(
                    replySuggestions = suggestions,
                    error = null,
                    chatBranches = if (current.transcriptIndex.branchingEligible) {
                        updateChatBranchNodeSnapshot(
                            state = current.chatBranches,
                            messageId = expectedAssistantMessageId,
                            chatState = current.chatState,
                            chatContext = current.chatContext,
                            replySuggestions = suggestions,
                        )
                    } else {
                        current.chatBranches
                    },
                )
            }
        }
        if (!applied) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "stale-discarded")
                put("assistant_message_id", expectedAssistantMessageId)
            })
            return false
        }

        boundEventLog.append("chat/reply-suggestions", buildJsonObject {
            put("status", "updated")
            put("assistant_message_id", expectedAssistantMessageId)
            put("suggestion_count", suggestions.size)
        })
        if (hasChatBranchAlternatives(state.value.chatBranches)) {
            persistBranchState("chat/reply-suggestions-updated")
        }
        persist()
        return true
    }

    private fun updateError(
        expectedSessionId: String,
        expectedAssistantMessageId: String,
        message: String,
    ) {
        state.update { current ->
            if (
                current.sessionId == expectedSessionId &&
                current.transcriptIndex.latestDialogueMessageId == expectedAssistantMessageId
            ) {
                current.copy(error = message)
            } else {
                current
            }
        }
    }
}
