package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.toLocalChatProjectionState

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
internal class LocalReplySuggestionCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chatState: LocalChatStatePort,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val modelGateway: LocalModelGateway,
    private val requestRuntime: LocalAuxiliaryModelRequestRuntime,
    private val usageTracker: DeepSeekUsageTracker,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    suspend fun generate(): Boolean {
        val aggregateSnapshot = runtimeStateStore.state.value
        val snapshot = aggregateSnapshot.toLocalChatProjectionState()
        if (snapshot.usageMode != LocalUsageMode.CHAT || snapshot.chat.groupChat.enabled) return false
        if (snapshot.loading || snapshot.kernel.running) {
            runtimeStateStore.projection.publishError("当前聊天正在加载或生成回复，请稍后使用回复建议")
            return false
        }
        if (!snapshot.modelState.configured) {
            runtimeStateStore.projection.publishError("请先配置可用模型，再生成回复建议")
            return false
        }

        val assistantMessage = snapshot.messages.lastOrNull { message ->
            message.role == "assistant" && message.content.isNotBlank()
        } ?: run {
            runtimeStateStore.projection.publishError("当前会话尚无可供生成建议的助手回复")
            return false
        }
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
                modelAttempts = aggregateSnapshot.modelState.modelAttempts,
                profile = profile,
                messages = chatReplySuggestionModelMessages(prompt),
                eventLog = boundEventLog,
                operation = "chat/reply-suggestions",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching {
                boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                    put("status", "failed")
                    put("detail", error.message.orEmpty().take(1_000))
                })
            }
            updateError(
                snapshot,
                expectedAssistantMessageId,
                error.message?.takeIf(String::isNotBlank) ?: "回复建议生成失败，请重试",
            )
            return false
        }
        usageTracker.recordForeground(
            snapshot = aggregateSnapshot,
            reply = reply,
            action = TokenUsageAction.REPLY_SUGGESTIONS,
            turnId = snapshot.transcriptIndex.latestUserMessageId,
            step = CHAT_POST_TURN_MODEL_STEP + 1,
        )

        val suggestions = chatTurnCoordinator.parseReplySuggestions(reply.content.orEmpty())
        if (suggestions.isNullOrEmpty()) {
            runCatching {
                boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                    put("status", "parse-failed")
                    put("content", reply.content.orEmpty().take(2_000))
                })
            }
            updateError(snapshot, expectedAssistantMessageId, "回复建议返回格式异常，请重试")
            return false
        }

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            expectedSessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        )
        if (lease == null) {
            runCatching {
                boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                    put("status", "stale-discarded")
                    put("assistant_message_id", expectedAssistantMessageId)
                    put("reason", "session-busy")
                })
            }
            updateError(snapshot, expectedAssistantMessageId, "回复建议已生成，但会话正在处理其他操作；请稍后重试")
            return false
        }
        try {
            val prepared = prepareReplySuggestionCommit(
                chatState.value,
                snapshot,
                expectedAssistantMessageId,
                suggestions,
            )
            if (prepared == null) {
                runCatching {
                    boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                        put("status", "stale-discarded")
                        put("assistant_message_id", expectedAssistantMessageId)
                    })
                }
                return false
            }

            appendChatDomainStateCommit(boundEventLog, prepared, "reply-suggestions-updated")
            chatState.update { current ->
                if (current.sessionId != expectedSessionId) current else current.copy(
                    chat = prepared.chat,
                    error = null,
                )
            }
            sessionStorage.enqueueCurrentSnapshot(expectedSessionId)
            runCatching {
                boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                    put("status", "updated")
                    put("assistant_message_id", expectedAssistantMessageId)
                    put("suggestion_count", suggestions.size)
                })
            }.onFailure { error ->
                chatState.update { current ->
                    if (current.sessionId == expectedSessionId) {
                        current.copy(
                            error = "回复建议已保存，诊断日志写入失败：${error.message ?: error::class.java.simpleName}",
                        )
                    } else current
                }
            }
            return true
        } finally {
            lease.close()
        }
    }

    private fun updateError(
        snapshot: LocalChatProjectionState,
        expectedAssistantMessageId: String,
        message: String,
    ) = commitReplySuggestionError(chatState, snapshot, expectedAssistantMessageId, message)
}
