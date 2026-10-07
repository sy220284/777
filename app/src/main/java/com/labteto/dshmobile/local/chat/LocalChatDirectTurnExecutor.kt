package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.ForegroundTokenUsageSeed
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalImageCapability
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.estimateModelTokens
import com.labteto.dshmobile.local.model.hasLocalImageRefs
import com.labteto.dshmobile.local.model.hasMaterializedImageUrls
import com.labteto.dshmobile.local.model.imageInputUnsupported
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import com.labteto.dshmobile.local.model.resolveLocalImageInputMode
import com.labteto.dshmobile.local.model.withChatTurnContext
import com.labteto.dshmobile.local.model.withEphemeralContext
import com.labteto.dshmobile.local.model.withoutLastCompletedAssistantReply
import com.labteto.dshmobile.local.model.chatSystemPrompt
import com.labteto.dshmobile.local.runtime.CHAT_RECENT_HISTORY_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ChatFeature owner of one direct foreground turn.
 *
 * Shared Runtime owns Session/run identity, foreground history and scarce resources. Shared Model
 * owns transport/retry/cache/overflow. This executor owns direct-chat preparation, quality guards,
 * durable assistant delivery, branch projection, post-turn scheduling and Chat queue continuation.
 */
@Singleton
internal class LocalChatDirectTurnExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chatState: LocalChatStatePort,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelHistory: LocalForegroundModelHistoryRuntime,
    private val modelRequests: LocalModelRequestCoordinator,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val chatMemory: LocalChatMemoryRuntime,
    private val replyCoordinator: LocalChatReplyCoordinator,
    private val branchCoordinator: LocalChatBranchCoordinator,
    private val postTurn: LocalChatContextRefreshCoordinator,
    private val queue: LocalChatQueueRuntime,
    private val webContext: LocalChatWebContextProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageCapabilities
        get() = runtimeStateStore.imageCapabilities
    private val imageRequestBudget by lazy {
        localImageRequestBudgetForModelConcurrency(
            runtimeStateStore.resourceBudget.maxModelRequests,
        )
    }

    internal fun start(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
        replacingMessageId: String? = null,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        run(
            input = input,
            memoryInput = memoryInput,
            sourceMessageId = sourceMessageId,
            replacingMessageId = replacingMessageId,
        )
    }

    internal suspend fun run(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
        replacingMessageId: String? = null,
    ) {
        val admittedSessionId = runtimeStateStore.currentSessionId
        try {
            LocalSessionRuntimeRegistry.withOwner(
                admittedSessionId, LocalSessionRuntimeKind.FOREGROUND,
            ) {
                runUnderSessionOwner(admittedSessionId, input, memoryInput, sourceMessageId, replacingMessageId)
            }
        } finally {
            queue.finishTurnAndStartNext(currentCoroutineContext()[Job])
        }
    }

    /** Called only by a foreground owner; preparation and execution share the same Session lease. */
    internal suspend fun runUnderSessionOwner(
        sessionId: String,
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
        replacingMessageId: String? = null,
    ) {
        if (runtimeStateStore.currentSessionId != sessionId) throw CancellationException("会话已切换")
        LocalExecutionService.withTurn(
            context = context,
            sessionId = sessionId,
            error = { runtimeStateStore.state.value.error },
        ) { runOwned(sessionId, input, memoryInput, sourceMessageId, replacingMessageId) }
    }

    private suspend fun runOwned(
        sessionId: String,
        input: String,
        memoryInput: String,
        sourceMessageId: String?,
        replacingMessageId: String?,
    ) {
        LocalChatPostTurnJobOwner.cancel()
        runtimeStateStore.foregroundInteractions.cancelAll()
        runtimeStateStore.projection.setForegroundRunning(
            sessionId = sessionId,
            running = true,
            error = null,
        )

        val eventLog = sessionStorage.eventLogs.get(sessionId)
        try {
            check(modelHistory.ensureSystemPrompt(sessionId, chatSystemPrompt())) {
                "Chat system prompt 写入时前台会话已切换"
            }
            val snapshot = runtimeStateStore.state.value
            check(snapshot.sessionId == sessionId && snapshot.usageMode == LocalUsageMode.CHAT) {
                "Direct Chat 回合只能处理当前 Chat 会话"
            }
            check(!snapshot.chat.groupChat.enabled) {
                "Direct Chat executor 不处理群聊"
            }

            val branchEligible = snapshot.transcriptIndex.branchingEligible
            val branchParentId = snapshot.transcriptIndex.latestUserMessageId
            val branchBase = snapshot.chat.chatBranches

            chatMemory.captureAutoMemoryDirective(
                memoryInput,
                sourceMessageId ?: snapshot.transcriptIndex.latestUserMessageId,
            )
            val relationshipMemory = chatMemory.relationshipContext(input)
            val preparedChat = chatTurnCoordinator.prepare(
                snapshot = snapshot,
                input = input,
                relationshipMemory = relationshipMemory,
            )
            val turnContext = preparedChat.context
            val networkContext = webContext.forInput(input)
            val dynamicContext = networkContext + preparedChat.dynamicContext
            check(
                modelHistory.compactChatIfNeeded(
                    expectedSessionId = sessionId,
                    extraTokens = estimateModelTokens(turnContext.stablePrompt) +
                        estimateModelTokens(dynamicContext),
                ),
            ) { "Chat 历史压缩时前台会话已切换" }

            val durableHistory = if (replacingMessageId == null) {
                modelHistory.history.snapshot()
            } else {
                modelHistory.history.snapshot().withoutLastCompletedAssistantReply()
            }
            val durableRequestMessages = withChatTurnContext(
                history = boundedChatRequestHistory(
                    durableHistory,
                    recentMessages = CHAT_RECENT_HISTORY_MESSAGES,
                    currentFacts = snapshot.chat.chatContext.canonicalFactLines(),
                ),
                stableContext = turnContext.stablePrompt,
                dynamicContext = dynamicContext,
            )
            val selectedMode = resolveLocalImageInputMode(
                snapshot.modelState.imageInputMode,
                imageCapabilities,
                snapshot.modelState.baseUrl,
                snapshot.modelState.model,
            )
            if (
                hasLocalImageRefs(durableRequestMessages) &&
                imageCapabilities.state(
                    snapshot.modelState.baseUrl,
                    snapshot.modelState.model,
                ) == LocalImageCapability.UNSUPPORTED
            ) {
                error("当前模型不支持图片理解，请切换支持图片的模型后重试。")
            }
            val requestMessages = prepareLocalMultimodalMessages(
                messages = durableRequestMessages,
                workspaceRoot = File(sessionStorage.files.workspace.path),
                mode = selectedMode,
                budget = imageRequestBudget,
                maxImageBytes = LocalModelPresets.maxNativeImageBytesFor(
                    snapshot.modelState.model,
                    snapshot.modelState.baseUrl,
                ),
            )
            val nativeImagesSent = hasMaterializedImageUrls(requestMessages)

            eventLog.append("turn/start", buildJsonObject {
                put("model", snapshot.modelState.model)
                put("mode", "chat")
                put("persona_id", turnContext.persona.id)
            })

            val rawReply = try {
                modelRequests.complete(
                    snapshot = snapshot,
                    messages = requestMessages,
                    step = 1,
                    toolsOverride = JsonArray(emptyList()),
                    allowImageGeneration = true,
                    publishPreviewEnabled = false,
                    streamFilterPhrases = ChatStyleGuard.activePhrases(
                        customPhrases = snapshot.chat.chatStyleGuardCustomPhrases,
                        personaPhrases = turnContext.persona.bannedPhrases,
                        enabled = snapshot.chat.chatStyleGuardEnabled,
                    ),
                    persistOverflowHistory = true,
                    requestLog = eventLog,
                    temperature = snapshot.chat.chatState.behaviorTuning.roleplayTemperature(),
                    previewGuard = {
                        runtimeStateStore.currentSessionId == sessionId &&
                            runtimeStateStore.state.value.sessionId == sessionId
                    },
                ).also {
                    if (nativeImagesSent) {
                        imageCapabilities.markSupported(
                            snapshot.modelState.baseUrl,
                            snapshot.modelState.model,
                        )
                    }
                }
            } catch (error: Throwable) {
                if (nativeImagesSent && imageInputUnsupported(error)) {
                    imageCapabilities.markUnsupported(
                        snapshot.modelState.baseUrl,
                        snapshot.modelState.model,
                    )
                    throw IllegalStateException(
                        "当前模型不支持图片理解，请切换支持图片的模型后重试。",
                        error,
                    )
                }
                throw error
            }

            val reply = replyCoordinator.finalizeDirect(
                snapshot = snapshot,
                reply = rawReply,
                userMessage = input,
                step = 1,
                usage = ForegroundTokenUsageSeed(
                    turnId = sourceMessageId ?: snapshot.transcriptIndex.latestUserMessageId,
                ),
                retryRaw = { repairHint ->
                    modelRequests.complete(
                        snapshot = snapshot,
                        messages = withEphemeralContext(requestMessages, repairHint),
                        step = 1,
                        toolsOverride = JsonArray(emptyList()),
                        allowImageGeneration = false,
                        publishPreviewEnabled = false,
                        maxAttemptsOverride = 1,
                        allowContextOverflowRecovery = false,
                        requestLog = eventLog,
                        temperature = snapshot.chat.chatState.behaviorTuning.roleplayTemperature(),
                        previewGuard = {
                            runtimeStateStore.currentSessionId == sessionId &&
                                runtimeStateStore.state.value.sessionId == sessionId
                        },
                    )
                },
                appendEvent = { type, data -> eventLog.append(type, data) },
            )

            val materializedReply = materializeLocalChatReplyMedia(
                reply = reply,
                workspaceRoot = File(sessionStorage.files.workspace.path),
            )
            val durableReply = materializedReply.reply
            if (replacingMessageId != null && materializedReply.blocks.isEmpty()) {
                error("模型没有返回可用回复")
            }

            val assistantTranscript = materializedReply.blocks
                .takeIf { it.isNotEmpty() }
                ?.let { blocks ->
                    LocalHarnessMessage(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        content = durableReply.content.orEmpty(),
                        createdAt = System.currentTimeMillis(),
                        blocks = blocks,
                    )
                }
            val transcriptMessages = listOfNotNull(assistantTranscript)
            val assistantData = if (transcriptMessages.isEmpty()) {
                durableReply.message
            } else {
                JsonObject(
                    durableReply.message +
                        ("transcript" to encodeTranscriptMessages(transcriptMessages)),
                )
            }
            val assistantEvent = eventLog.append(
                "assistant/message",
                if (replacingMessageId == null) {
                    assistantData
                } else {
                    JsonObject(assistantData + ("replaces" to JsonPrimitive(replacingMessageId)))
                },
            )

            if (replacingMessageId != null) {
                check(
                    modelHistory.reset(
                        sessionId,
                        modelHistory.history.snapshot().withoutLastCompletedAssistantReply(),
                    ),
                ) { "Chat 重生成替换模型历史时前台会话已切换" }
                runtimeStateStore.projection.removeForegroundTranscriptMessage(
                    sessionId,
                    replacingMessageId,
                )
            }
            modelHistory.history.append(durableReply.message)
            check(modelHistory.refreshMetrics(sessionId)) {
                "Chat 回复提交后前台会话已切换"
            }
            runtimeStateStore.projection.appendForegroundTranscript(
                sessionId = sessionId,
                messages = transcriptMessages,
            )
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor = maxOf(
                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                assistantEvent.sequence,
            )

            if (
                branchEligible &&
                branchBase.nodes.isNotEmpty() &&
                assistantTranscript != null &&
                branchParentId != null
            ) {
                val branches = upsertChatBranchNode(
                    branchBase,
                    LocalChatBranchNode(
                        message = assistantTranscript,
                        parentId = branchParentId,
                        chatStateAfter = snapshot.chat.chatState,
                    ),
                    select = true,
                )
                chatState.update { current ->
                    if (current.sessionId != sessionId) current else current.copy(
                        chat = current.chat.copy(chatBranches = branches),
                    )
                }
                if (hasChatBranchAlternatives(branches)) {
                    branchCoordinator.persistCurrentProjection(
                        expectedSessionId = sessionId,
                        reason = if (replacingMessageId != null) {
                            "assistant-regenerated"
                        } else {
                            "assistant-branch-completed"
                        },
                    )
                }
            }

            eventLog.append("turn/end", buildJsonObject {
                put("reason", "completed")
                put("steps", 1)
                put(
                    "messages",
                    runtimeStateStore.state.value.transcriptIndex.totalMessageCount,
                )
                put("mode", "chat")
            })
            if (replacingMessageId != null) {
                modelHistory.checkpoint(sessionId, "chat/regenerated")
            } else {
                modelHistory.checkpointAtTurnBoundary(sessionId, "chat/completed")
            }

            runtimeStateStore.projection.setForegroundRunning(
                sessionId = sessionId,
                running = false,
                error = null,
            )
            sessionStorage.enqueueCurrentSnapshot(sessionId)

            val assistantMessage = durableReply.content?.takeIf(String::isNotBlank)
            if (assistantTranscript != null && assistantMessage != null) {
                snapshot.modelState.modelSelection.activeProfile?.let { profile ->
                    postTurn.schedule(
                        userMessage = input,
                        assistantMessage = assistantMessage,
                        persona = turnContext.persona,
                        expectedSessionId = sessionId,
                        expectedAssistantMessageId = assistantTranscript.id,
                        expectedBaseState = runtimeStateStore.state.value.chat.chatState,
                        boundEventLog = eventLog,
                        profile = profile,
                        sourceUserMessageId =
                            sourceMessageId ?: snapshot.transcriptIndex.latestUserMessageId,
                        assistantEventSequence = assistantEvent.sequence,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "aborted")
                put("mode", "chat")
            })
            modelHistory.checkpointAtTurnBoundary(sessionId, "chat/cancelled")
            sessionStorage.enqueueCurrentSnapshot(sessionId)
            throw cancelled
        } catch (error: Exception) {
            if (replacingMessageId != null) {
                val oldNode = runtimeStateStore.state.value.chat.chatBranches.nodes
                    .firstOrNull { it.message.id == replacingMessageId }
                oldNode?.chatStateAfter?.let { restoredState ->
                    chatState.update { current ->
                        if (current.sessionId != sessionId) current else current.copy(
                            chat = current.chat.copy(
                                chatState = restoredState,
                                chatContext = restoreBranchContext(
                                    snapshot = oldNode.chatContextAfter,
                                    legacyState = oldNode.chatStateAfter,
                                    previousGeneration = current.chat.chatContext.generation,
                                ).boundDurablePending(eventLog),
                                replySuggestions = oldNode.replySuggestionsAfter,
                            ),
                        )
                    }
                }
            }
            val detail = error.message ?: "聊天请求失败"
            chatState.update { current ->
                if (current.sessionId != sessionId) current else current.copy(error = detail)
            }
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "error")
                put("detail", detail.take(2_000))
                put("mode", "chat")
            })
            modelHistory.checkpointAtTurnBoundary(sessionId, "chat/failed")
            sessionStorage.enqueueCurrentSnapshot(sessionId)
        } finally {
            runtimeStateStore.foregroundInteractions.cancelAll()
            runtimeStateStore.projection.setForegroundRunning(
                sessionId = sessionId,
                running = false,
                error = runtimeStateStore.state.value.error,
            )
            sessionStorage.enqueueCurrentSnapshot(sessionId)

        }
    }
}
