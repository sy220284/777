package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatTurnCoordinator
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalAuxiliaryModelRequestRuntime
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.recordForeground
import com.labteto.dshmobile.local.runtime.CHAT_POST_TURN_MODEL_STEP
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns reply-suggestion generation and commit inside ChatFeature.
 *
 * Model work runs outside the Session lease on a frozen route. The final state/durable commit takes
 * the shared MAINTENANCE lease and revalidates the exact assistant/character generation target.
 */
@Singleton
internal class LocalReplySuggestionCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val modelGateway: LocalModelGateway,
    private val requestRuntime: LocalAuxiliaryModelRequestRuntime,
    private val usageTracker: DeepSeekUsageTracker,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val branchCoordinator: LocalChatBranchCoordinator,
) {
    suspend fun generate(): Boolean {
        val state = runtimeStateStore.mutableState
        val snapshot = state.value
        if (
            snapshot.loading ||
            !snapshot.modelState.configured ||
            snapshot.kernel.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.chat.groupChat.enabled
        ) return false

        val assistantMessage = snapshot.messages.lastOrNull { message ->
            message.role == "assistant" && message.content.isNotBlank()
        } ?: return false
        val expectedSessionId = snapshot.sessionId
        val expectedAssistantMessageId = assistantMessage.id
        val boundEventLog = sessionStorage.eventLogs.get(expectedSessionId)
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
                state,
                snapshot,
                expectedAssistantMessageId,
                error.message?.takeIf(String::isNotBlank)
                    ?: "回复建议需要可用的模型账户或 API Key",
            )
            return false
        }

        val prompt = chatTurnCoordinator.replySuggestionsPrompt(
            persona = snapshot.chat.chatPersona,
            state = snapshot.chat.chatState,
            messages = snapshot.messages,
            latestAssistantMessageId = expectedAssistantMessageId,
        )
        val reply = try {
            requestRuntime.complete(
                snapshot = snapshot,
                profile = profile,
                messages = chatReplySuggestionModelMessages(prompt),
                eventLog = boundEventLog,
                operation = "chat/reply-suggestions",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
            })
            updateError(
                state,
                snapshot,
                expectedAssistantMessageId,
                error.message?.takeIf(String::isNotBlank) ?: "回复建议生成失败，请重试",
            )
            return false
        }
        usageTracker.recordForeground(
            snapshot = snapshot,
            reply = reply,
            action = TokenUsageAction.REPLY_SUGGESTIONS,
            turnId = snapshot.transcriptIndex.latestUserMessageId,
            step = CHAT_POST_TURN_MODEL_STEP + 1,
        )

        val suggestions = chatTurnCoordinator.parseReplySuggestions(reply.content.orEmpty())
        if (suggestions.isNullOrEmpty()) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "parse-failed")
                put("content", reply.content.orEmpty().take(2_000))
            })
            updateError(state, snapshot, expectedAssistantMessageId, "回复建议返回格式异常，请重试")
            return false
        }

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            expectedSessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        )
        if (lease == null) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "stale-discarded")
                put("assistant_message_id", expectedAssistantMessageId)
                put("reason", "session-busy")
            })
            return false
        }
        try {
            val applied = commitReplySuggestions(
                state,
                snapshot,
                expectedAssistantMessageId,
                suggestions,
            )
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
            if (hasChatBranchAlternatives(state.value.chat.chatBranches)) {
                branchCoordinator.persistCurrentProjection(
                    expectedSessionId,
                    "chat/reply-suggestions-updated",
                )
            }
            sessionStorage.enqueueCurrentSnapshot(expectedSessionId)
            return true
        } finally {
            lease.close()
        }
    }

    private fun updateError(
        state: MutableStateFlow<LocalHarnessState>,
        snapshot: LocalHarnessState,
        expectedAssistantMessageId: String,
        message: String,
    ) = commitReplySuggestionError(state, snapshot, expectedAssistantMessageId, message)
}
