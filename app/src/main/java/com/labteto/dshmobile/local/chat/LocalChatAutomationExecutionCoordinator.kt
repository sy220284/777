package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.session.LocalSessionCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatPersonaStore
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.chat.appendMaterializedChatBranchMessage
import com.labteto.dshmobile.local.chat.applySceneTurn
import com.labteto.dshmobile.local.chat.characterProactiveDirective
import com.labteto.dshmobile.local.chat.enqueuePending
import com.labteto.dshmobile.local.chat.evaluateChatProactivePolicy
import com.labteto.dshmobile.local.chat.evaluateChatSilenceTrigger
import com.labteto.dshmobile.local.chat.isNearDuplicateProactive
import com.labteto.dshmobile.local.chat.proactiveConversationFocus
import com.labteto.dshmobile.local.chat.recentProactiveAvoidanceContext
import com.labteto.dshmobile.local.chat.withoutLegacyConversationContext
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.chatSystemPrompt
import com.labteto.dshmobile.local.model.withChatTurnContext
import com.labteto.dshmobile.local.model.withEphemeralContext
import com.labteto.dshmobile.local.recordAutomation
import com.labteto.dshmobile.local.runtime.AUTOMATION_CHAT_HISTORY_MESSAGES
import com.labteto.dshmobile.local.runtime.CHAT_POST_TURN_MODEL_STEP
import com.labteto.dshmobile.local.runtime.CHAT_ROLEPLAY_TEMPERATURE
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.appendLocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.session.localTranscriptIndexForSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns proactive scheduled Chat generation and detached-session persistence.
 *
 * Visible-session transaction ownership is delegated through LocalChatAutomationVisibleTurnOwner;
 * detached execution uses Shared Session/Runtime and Chat-owned collaborators.
 */
@javax.inject.Singleton
internal class LocalChatAutomationExecutionCoordinator @javax.inject.Inject constructor(
    private val runtimeStateStore: com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore,
    private val styleGuardSettings: LocalChatStyleGuardSettingsPort,
    private val sessionStorage: com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime,
    private val persistence: LocalChatPersistence,
    private val chatComposition: LocalChatComposition,
    private val usageTracker: DeepSeekUsageTracker,
    private val modelGateway: LocalModelGateway,
    private val modelRequestCoordinator: LocalModelRequestCoordinator,
    private val visibleTurnOwner: LocalChatAutomationVisibleTurnOwner,
) : LocalChatAutomationExecutionPort {
    private val state = runtimeStateStore.state
    private val sessionCoordinator get() = sessionStorage.coordinator
    private fun eventLogFor(sessionId: String): LocalSessionEventLog = sessionStorage.eventLogs.get(sessionId)
    private val chatPersonaStore get() = persistence.personaStore
    private val chatTurnCoordinator get() = chatComposition.turnCoordinator
    private val chatReplyCoordinator get() = chatComposition.replyCoordinator


    override suspend fun run(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long,
        recoverInterrupted: Boolean,
        recoveryStartedAt: Long?,
        policy: LocalChatAutomationPolicy,
    ): LocalChatAutomationResult = try {
        runInternal(
            instruction = instruction,
            targetSessionId = targetSessionId,
            timeoutMillis = timeoutMillis,
            recoverInterrupted = recoverInterrupted,
            recoveryStartedAt = recoveryStartedAt,
            quietHoursEnabled = policy.quietHoursEnabled,
            quietStartHour = policy.quietStartHour,
            quietStartMinute = policy.quietStartMinute,
            quietEndHour = policy.quietEndHour,
            quietEndMinute = policy.quietEndMinute,
            proactiveMinGapMinutes = policy.proactiveMinGapMinutes,
            proactiveMaxUnanswered = policy.proactiveMaxUnanswered,
            minimumSilenceMinutes = policy.minimumSilenceMinutes,
            silenceReferenceAt = policy.silenceReferenceAt,
            bypassProactivePolicy = policy.bypassProactivePolicy,
        )
    } catch (timeout: TimeoutCancellationException) {
        val detail = "定时互动执行超时，已停止本轮任务"
        LocalChatAutomationResult(
            sessionId = targetSessionId,
            output = detail,
            status = LocalChatAutomationStatus.FAILED,
            detail = detail,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (blocked: LocalHarnessBlockedException) {
        val detail = blocked.message ?: "定时互动需要人工处理"
        LocalChatAutomationResult(
            sessionId = blocked.sessionId ?: targetSessionId,
            output = detail,
            status = LocalChatAutomationStatus.BLOCKED,
            detail = detail,
        )
    } catch (error: Exception) {
        val detail = "定时互动失败：" + (error.message ?: error::class.java.simpleName)
        LocalChatAutomationResult(
            sessionId = targetSessionId,
            output = detail,
            status = LocalChatAutomationStatus.FAILED,
            detail = detail,
        )
    }

    private suspend fun runInternal(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long,
        recoverInterrupted: Boolean,
        recoveryStartedAt: Long?,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
        minimumSilenceMinutes: Long?,
        silenceReferenceAt: Long?,
        bypassProactivePolicy: Boolean,
    ): LocalChatAutomationResult {
        val trigger = instruction.trim()
        require(trigger.isNotEmpty()) { "定时互动意图不能为空" }
        withTimeout(15_000L) {
            while (state.value.loading) delay(50)
        }
        require(state.value.modelState.configured) { "本机 Harness 尚未配置模型" }

        var initialSession = requireAutomationChatSession(sessionCoordinator.read(targetSessionId))
        val budget = LocalChatAutomationTimeoutBudget(timeoutMillis, 10 * 60_000L)
        val ownership = acquireAutomationChatOwnership(
            targetSessionId, currentCoroutineContext()[Job], budget, visibleTurnOwner::acquire, visibleTurnOwner::release,
        )
        try {
        initialSession = requireAutomationChatSession(sessionCoordinator.read(targetSessionId))
        if (recoverInterrupted) {
            recoverAutomationChatOutput(
                eventLog = eventLogFor(targetSessionId),
                startedAt = recoveryStartedAt,
            )?.let { recovered ->
                return LocalChatAutomationResult(sessionId = targetSessionId, output = recovered)
            }
        }

        val earlyQuietDecision = if (bypassProactivePolicy) {
            null
        } else {
            evaluateChatProactivePolicy(
                messages = emptyList(),
                nowMillis = System.currentTimeMillis(),
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietStartMinute = quietStartMinute,
                quietEndHour = quietEndHour,
                quietEndMinute = quietEndMinute,
                minimumGapMinutes = proactiveMinGapMinutes,
                maxUnanswered = proactiveMaxUnanswered,
            )
        }
        if (earlyQuietDecision?.shouldSend == false) {
            val reason = earlyQuietDecision.reason ?: "当前处于免打扰时段"
            eventLogFor(targetSessionId).append("chat/proactive-skipped", buildJsonObject {
                put("reason", reason)
                put("automation", true)
                put("proactive", true)
                put("persona_id", initialSession.personaId)
            })
            return LocalChatAutomationResult(
                sessionId = targetSessionId,
                output = reason,
                status = LocalChatAutomationStatus.SKIPPED,
                detail = reason,
                nextRunAtHint = earlyQuietDecision.retryAt,
            )
        }

        if (!bypassProactivePolicy && minimumSilenceMinutes != null) {
            val earlyEventLog = eventLogFor(targetSessionId)
            val earlyTranscript = LocalSessionTranscriptPager(earlyEventLog)
                .page(limit = AUTOMATION_CHAT_HISTORY_MESSAGES)
                .messages
                .ifEmpty {
                    initialSession.transcriptWindow
                        .ifEmpty {
                            initialSession.messages.takeLast(
                                LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
                            )
                        }
                        .takeLast(AUTOMATION_CHAT_HISTORY_MESSAGES)
                }
            val silenceDecision = evaluateChatSilenceTrigger(
                messages = earlyTranscript,
                nowMillis = System.currentTimeMillis(),
                silenceMinutes = minimumSilenceMinutes,
                fallbackReferenceAt = silenceReferenceAt ?: initialSession.updatedAt,
            )
            if (!silenceDecision.ready) {
                val reason = "用户最近仍有互动，尚未达到沉默触发时长"
                earlyEventLog.append("chat/proactive-skipped", buildJsonObject {
                    put("reason", reason)
                    put("automation", true)
                    put("proactive", true)
                    put("silence_trigger", true)
                    put("persona_id", initialSession.personaId)
                })
                return LocalChatAutomationResult(
                    sessionId = targetSessionId,
                    output = reason,
                    status = LocalChatAutomationStatus.SKIPPED,
                    detail = reason,
                    nextRunAtHint = silenceDecision.retryAt,
                )
            }
        }

        return withTimeout(budget.remainingMillis()) {
                val session = sessionCoordinator.read(targetSessionId)
                    ?: error("定时互动绑定的聊天已不存在")
                require(session.usageMode == LocalUsageMode.CHAT) { "目标会话已不在聊天模式" }
                require(!session.groupChat.enabled) { "群聊暂不支持定时角色互动" }
                val sessionContext = session.chatContext
                val sessionCharacterState = session.chatState
                val runtime = state.value
                val runProfile = modelGateway.profileForRun()
                val persona = chatPersonaStore.get(session.personaId)
                val boundEventLog = eventLogFor(session.id)
                val recentTranscript = LocalSessionTranscriptPager(boundEventLog)
                    .page(limit = AUTOMATION_CHAT_HISTORY_MESSAGES)
                    .messages
                    .ifEmpty {
                        session.transcriptWindow
                            .ifEmpty { session.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES) }
                            .takeLast(AUTOMATION_CHAT_HISTORY_MESSAGES)
                    }
                if (!bypassProactivePolicy && minimumSilenceMinutes != null) {
                    val silenceDecision = evaluateChatSilenceTrigger(
                        messages = recentTranscript,
                        nowMillis = System.currentTimeMillis(),
                        silenceMinutes = minimumSilenceMinutes,
                        fallbackReferenceAt = silenceReferenceAt ?: session.updatedAt,
                    )
                    if (!silenceDecision.ready) {
                        val reason = "用户刚有新的互动，重新等待沉默触发时长"
                        boundEventLog.append("chat/proactive-skipped", buildJsonObject {
                            put("reason", reason)
                            put("automation", true)
                            put("proactive", true)
                            put("silence_trigger", true)
                            put("rechecked", true)
                            put("persona_id", persona.id)
                        })
                        return@withTimeout LocalChatAutomationResult(
                            sessionId = session.id,
                            output = reason,
                            status = LocalChatAutomationStatus.SKIPPED,
                            detail = reason,
                            nextRunAtHint = silenceDecision.retryAt,
                        )
                    }
                }

                val proactiveDecision = if (bypassProactivePolicy) {
                    null
                } else {
                    evaluateChatProactivePolicy(
                        messages = recentTranscript,
                        nowMillis = System.currentTimeMillis(),
                        quietHoursEnabled = quietHoursEnabled,
                        quietStartHour = quietStartHour,
                        quietStartMinute = quietStartMinute,
                        quietEndHour = quietEndHour,
                        quietEndMinute = quietEndMinute,
                        minimumGapMinutes = proactiveMinGapMinutes,
                        maxUnanswered = proactiveMaxUnanswered,
                    )
                }
                if (proactiveDecision?.shouldSend == false) {
                    val reason = proactiveDecision.reason ?: "当前不适合继续主动互动"
                    boundEventLog.append("chat/proactive-skipped", buildJsonObject {
                        put("reason", reason)
                        put("automation", true)
                        put("proactive", true)
                        put("persona_id", persona.id)
                    })
                    return@withTimeout LocalChatAutomationResult(
                        sessionId = session.id,
                        output = reason,
                        status = LocalChatAutomationStatus.SKIPPED,
                        detail = reason,
                        nextRunAtHint = proactiveDecision.retryAt,
                        waitingForUserReply = proactiveDecision.waitingForUserReply,
                    )
                }
                val conversationFocus = proactiveConversationFocus(
                    messages = recentTranscript,
                    fallback = session.handoffSummary?.takeIf(String::isNotBlank) ?: trigger,
                )
                val sessionTranscriptIndex = localTranscriptIndexForSession(session)
                val boundState = runtime.copy(
                    sessionId = session.id,
                    modelState = runtime.modelState.copy(model = runProfile.model, baseUrl = runProfile.baseUrl),
                    usageMode = LocalUsageMode.CHAT,
                    chat = LocalChatState(
                        personaId = session.personaId,
                        galleryId = session.galleryId,
                        galleryStoryId = session.galleryStoryId,
                        gallerySaveSuppressedThrough = session.gallerySaveSuppressedThrough,
                        chatPersona = persona,
                        chatState = sessionCharacterState,
                        chatContext = sessionContext,
                        replySuggestions = session.replySuggestions,
                        chatBranches = session.chatBranches,
                        groupChat = session.groupChat,
                    ),
                    conversationMode = session.conversationMode,
                    parentSessionId = session.parentSessionId,
                    lineageId = session.lineageId.ifBlank { session.id },
                    projectId = session.projectId,
                    handoffSummary = session.handoffSummary,
                    messages = recentTranscript,
                    transcriptIndex = sessionTranscriptIndex,
                    work = runtime.work,
                    kernel = runtime.kernel.copy(running = false, queuedInputCount = 0),
                    error = null,
                )
                val chatContext = chatTurnCoordinator.prepareProfile(
                    persona = persona,
                    state = sessionCharacterState,
                    context = sessionContext,
                    userInput = conversationFocus,
                    storyContext = session.handoffSummary,
                )
                val relationshipMemory = chatComposition.memory.relationshipContext(conversationFocus, boundState)
                val proactiveAvoidance = recentProactiveAvoidanceContext(recentTranscript)
                val proactiveDirective = characterProactiveDirective(
                    trigger = trigger,
                    persona = persona,
                    state = sessionCharacterState,
                )
                val localHistory = buildList {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", chatSystemPrompt())
                    })
                    recentTranscript
                        .filter { it.role == "user" || it.role == "assistant" }
                        .forEach { message ->
                            add(buildJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            })
                        }
                }
                val dynamicContext = listOf(
                    chatContext.dynamicPrompt,
                    relationshipMemory,
                    proactiveAvoidance,
                    proactiveDirective,
                )
                    .filter(String::isNotBlank)
                    .joinToString("\n\n")
                val requestMessages = withChatTurnContext(
                    history = localHistory,
                    stableContext = chatContext.stablePrompt,
                    dynamicContext = dynamicContext,
                )
                boundEventLog.append("turn/start", buildJsonObject {
                    put("model", boundState.modelState.model)
                    put("mode", "chat")
                    put("automation", true)
                    put("proactive", true)
                    put("persona_id", persona.id)
                })
                val userActivitySequenceAtGenerationStart =
                    boundEventLog.latestAutomationUserActivitySequence()
                val rawReply = completeAutomationChat(
                    snapshot = boundState,
                    messages = requestMessages,
                    profile = runProfile,
                )
                var reply = chatTurnCoordinator.finalize(
                    snapshot = boundState,
                    persona = persona,
                    reply = rawReply,
                    recordUsage = { candidate -> usageTracker.recordAutomation(boundState, candidate, TokenUsageAction.AUTOMATION_CHAT, proactiveDirective) },
                    onGuardEvent = { action, violations ->
                        styleGuardSettings.recordHits(violations)
                        boundEventLog.append("chat/style-guard", buildJsonObject {
                            put("action", action)
                            put("automation", true)
                            put("proactive", true)
                            put("violations", JsonArray(violations.map(::JsonPrimitive)))
                        })
                    },
                )
                var content = reply.content.orEmpty().trim()
                require(content.isNotEmpty()) { "角色没有生成可用的主动消息" }

                if (isNearDuplicateProactive(content, recentTranscript)) {
                    val retryRawReply = completeAutomationChat(
                        snapshot = boundState,
                        profile = runProfile,
                        messages = withEphemeralContext(
                            requestMessages,
                            """
                            【主动互动去重】
                            上一版与近期内容过近。保持人物与剧情连续，换切入点和表达重新生成。
                            只输出新消息。
                            """.trimIndent(),
                        ),
                    )
                    reply = chatTurnCoordinator.finalize(
                        snapshot = boundState,
                        persona = persona,
                        reply = retryRawReply,
                        recordUsage = { candidate -> usageTracker.recordAutomation(boundState, candidate, TokenUsageAction.CHAT_REPAIR, proactiveDirective) },
                        onGuardEvent = { action, violations ->
                            styleGuardSettings.recordHits(violations)
                            boundEventLog.append("chat/style-guard", buildJsonObject {
                                put("action", action)
                                put("automation", true)
                                put("proactive", true)
                                put("dedupe_retry", true)
                                put("violations", JsonArray(violations.map(::JsonPrimitive)))
                            })
                        },
                    )
                    content = reply.content.orEmpty().trim()
                    require(content.isNotEmpty()) { "角色主动消息去重重写后为空" }
                }

                val proactiveScene = sessionContext.scene
                reply = chatReplyCoordinator.guardProactive(
                    snapshot = boundState,
                    persona = persona,
                    scene = proactiveScene,
                    initial = reply,
                    retryRaw = { repairHint ->
                        completeAutomationChat(
                            snapshot = boundState,
                            messages = withEphemeralContext(requestMessages, repairHint),
                            profile = runProfile,
                        )
                    },
                    appendEvent = { type, data ->
                        boundEventLog.append(type, data)
                    },
                )
                content = reply.content.orEmpty().trim()
                require(content.isNotEmpty()) { "角色主动消息连续性重写后为空" }

                rejectStaleAutomationProactiveReply(
                    boundEventLog, userActivitySequenceAtGenerationStart, session.id, persona.id,
                )?.let { return@withTimeout it }

                val proactiveMessage = LocalHarnessMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = "assistant",
                    content = content,
                    createdAt = System.currentTimeMillis(),
                    proactive = true,
                )
                val assistantEvent = boundEventLog.append("assistant/message", buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    // Proactive/automation metadata lives inside the transcript message. Keep the
                    // model-replay envelope schema clean so only role/content return to the model.
                    put("transcript", encodeTranscriptMessages(listOf(proactiveMessage)))
                })

                if (ownership.visibleTurnOwned && state.value.sessionId == session.id) {
                    visibleTurnOwner.commit(
                        session,
                        reply,
                        content,
                        proactiveMessage,
                        assistantEvent.sequence,
                    )
                } else {
                    // Re-read immediately before commit so a detached automation never overwrites a
                    // foreground turn that completed while the model was generating.
                    val latest = sessionCoordinator.read(session.id) ?: session
                    val latestContext = latest.chatContext
                    val nextChatState = chatTurnCoordinator.applyDeterministicInteractionState(
                        previous = latest.chatState,
                        userMessage = "",
                        assistantMessage = content,
                    ).withoutLegacyConversationContext()
                    val latestIndex = localTranscriptIndexForSession(latest)
                    val nextTranscriptIndex = appendLocalTranscriptRuntimeIndex(
                        latestIndex,
                        listOf(proactiveMessage),
                    )
                    val latestWindow = latest.transcriptWindow.ifEmpty {
                        latest.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)
                    }
                    val baseContext = latestContext
                        .applySceneTurn(
                            userMessage = "",
                            assistantMessage = content,
                            sequence = assistantEvent.sequence,
                        )
                    val pending = ChatPendingTurn(
                        sequence = assistantEvent.sequence,
                        assistantMessageId = proactiveMessage.id,
                        branchHeadId = proactiveMessage.id,
                        userMessage = "",
                        assistantMessage = content,
                        generation = baseContext.generation,
                    )
                    val nextContext = baseContext.enqueuePending(pending)
                    val nextBranches = if (
                        nextTranscriptIndex.branchingEligible &&
                        latest.chatBranches.nodes.isNotEmpty()
                    ) {
                        appendMaterializedChatBranchMessage(
                            current = latest.chatBranches,
                            activeMessages = emptyList(),
                            message = proactiveMessage,
                            parentId = latestIndex.latestDialogueMessageId,
                            chatState = nextChatState,
                            chatContext = nextContext,
                            replySuggestions = latest.replySuggestions,
                        )
                    } else {
                        latest.chatBranches
                    }
                    sessionCoordinator.enqueue(
                        latest.copy(
                            updatedAt = System.currentTimeMillis(),
                            messages = emptyList(),
                            transcriptWindow = (latestWindow + proactiveMessage)
                                .takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                            transcriptIndex = nextTranscriptIndex,
                            transcriptProjectedThroughSequence = assistantEvent.sequence,
                        ).withChatSessionDomain(
                            chatState = nextChatState,
                            chatContext = nextContext,
                            chatBranches = nextBranches,
                        ),
                    )
                }
                boundEventLog.append("turn/end", buildJsonObject {
                    put("reason", "completed")
                    put("steps", 1)
                    put("mode", "chat")
                    put("automation", true)
                    put("proactive", true)
                })
                LocalChatAutomationResult(sessionId = session.id, output = content)
            }
        } finally {
            ownership.close()
        }
    }

    private suspend fun completeAutomationChat(
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        profile: LocalModelProfile,
        allowContextOverflowRecovery: Boolean = true,
    ): LocalModelReply = modelRequestCoordinator.complete(
        snapshot = snapshot,
        messages = messages,
        step = CHAT_POST_TURN_MODEL_STEP + 200,
        toolsOverride = JsonArray(emptyList()),
        publishPreviewEnabled = false,
        maxAttemptsOverride = snapshot.modelState.modelAttempts.coerceIn(1, 3),
        allowContextOverflowRecovery = allowContextOverflowRecovery,
        persistOverflowHistory = false,
        requestLog = eventLogFor(snapshot.sessionId),
        temperature = CHAT_ROLEPLAY_TEMPERATURE,
        profile = profile,
    )
}
