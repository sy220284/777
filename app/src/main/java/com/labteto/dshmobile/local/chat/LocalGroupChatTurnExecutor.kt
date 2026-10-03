package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.local.chat.ChatDiarySourceMode
import com.labteto.dshmobile.local.chat.ChatDiaryStore
import com.labteto.dshmobile.local.chat.ChatDiaryWriteRequest
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.ChatPersonaStore
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.chatPostTurnModelMessages
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.applySceneTurn
import com.labteto.dshmobile.local.chat.enqueuePendingDurably
import com.labteto.dshmobile.local.chat.loadPendingBatch
import com.labteto.dshmobile.local.chat.withContextForPlanner
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns multi-character group-chat turn execution.
 *
 * The engine supplies only its live session boundaries and persistence/model callbacks. Group reply
 * fan-out, per-character state refresh, shared-scene consolidation and transcript projection stay
 * together here instead of expanding LocalHarnessEngine.
 */
internal class LocalGroupChatTurnExecutor(
    private val _state: MutableStateFlow<LocalHarnessState>,
    private val modelGateway: LocalModelGateway,
    private val chatPersonaStore: ChatPersonaStore,
    private val chatPersonaGalleryStore: ChatPersonaGalleryStore,
    private val chatReplyCoordinator: LocalChatReplyCoordinator,
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val diaryStore: ChatDiaryStore,
    private val usageTracker: DeepSeekUsageTracker,
    private val json: Json,
    private val modelHistory: LocalModelHistoryBuffer,
    private val transcriptRuntime: LocalTranscriptRuntime,
    private val imageCapabilities: LocalImageCapabilityRegistry,
    private val imageRequestBudget: LocalImageRequestBudget,
    private val workspacePath: String,
    private val eventLogProvider: () -> LocalSessionEventLog,
    private val completeModel: suspend (
        String,
        LocalHarnessState,
        List<JsonObject>,
        Int,
        JsonArray?,
        Boolean,
        Int?,
        Boolean,
        Double?,
    ) -> LocalModelReply,
    private val ensureSystemMessageAction: () -> Unit,
    private val captureAutoMemoryAction: suspend (String, String?) -> Unit,
    private val chatMemoryContextAction: (String, LocalHarnessState, String, String) -> String,
    private val compactHistoryAction: (Int) -> Unit,
    private val updateContextMetricsAction: () -> Unit,
    private val persistBranchStateAction: (String) -> Unit,
    private val checkpointHistoryAction: (String) -> Unit,
    private val persistAction: () -> Unit,
    private val persistNowAction: suspend () -> Unit,
    private val finishTurn: (Job?) -> Unit,
) {
    private val eventLog: LocalSessionEventLog
        get() = eventLogProvider()

    private fun ensureSystemMessage() = ensureSystemMessageAction()

    private suspend fun captureAutoMemoryDirective(text: String, sourceMessageId: String? = null) =
        captureAutoMemoryAction(text, sourceMessageId)

    private fun compactHistoryIfNeeded(extraTokens: Int = 0) = compactHistoryAction(extraTokens)

    private fun updateContextMetrics() = updateContextMetricsAction()

    private fun persistChatBranchState(reason: String) = persistBranchStateAction(reason)

    private fun checkpointModelHistory(reason: String) = checkpointHistoryAction(reason)

    private fun persist() = persistAction()

    private suspend fun persistNow() = persistNowAction()

    private fun projectGalleryState(groupChat: LocalGroupChatState, phase: String) {
        projectGroupGalleryState(groupChat, chatPersonaGalleryStore).failures.forEach { failure ->
            eventLog.append("group/state-persist", buildJsonObject {
                put("gallery_id", failure.galleryId)
                put("phase", phase)
                put("detail", failure.detail)
            })
        }
    }

    private suspend fun completeWithRetry(
        key: String,
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        toolsOverride: JsonArray? = null,
        publishPreview: Boolean = true,
        maxAttemptsOverride: Int? = null,
        allowContextOverflowRecovery: Boolean = true,
        temperature: Double? = null,
    ): LocalModelReply = completeModel(
        key,
        snapshot,
        messages,
        step,
        toolsOverride,
        publishPreview,
        maxAttemptsOverride,
        allowContextOverflowRecovery,
        temperature,
    )

    private suspend fun generateGroupReply(
        key: String,
        snapshot: LocalHarnessState,
        baseHistory: List<JsonObject>,
        input: String,
        allMembers: List<LocalGroupChatMember>,
        member: LocalGroupChatMember,
        memoryContext: String,
        index: Int,
        turnId: String?,
    ): GroupGeneratedReply {
        val persona = chatPersonaStore.get(member.personaId)
        val startedAtNanos = System.nanoTime()
        val interactiveAttempts = snapshot.modelAttempts.coerceIn(1, 2)

        return try {
            val turnContext = chatReplyCoordinator.buildGroupTurnContext(
                persona = persona,
                member = member,
                input = input,
                allMembers = allMembers,
                handoffSummary = snapshot.handoffSummary,
                sharedContext = snapshot.groupChat.context,
                memoryContext = memoryContext,
                announcement = snapshot.groupChat.announcement,
                mayStaySilent = false,
                silentToken = GROUP_CHAT_SILENT_TOKEN,
            )
            val groupRequestHistory = withChatTurnContext(
                history = baseHistory,
                stableContext = turnContext.stablePrompt,
                dynamicContext = turnContext.dynamicPrompt,
            )
            val groupImageMode = resolveLocalImageInputMode(
                snapshot.imageInputMode,
                imageCapabilities,
                snapshot.baseUrl,
                snapshot.model,
            )
            if (hasLocalImageRefs(groupRequestHistory) && groupImageMode == LocalImageInputMode.TOOL) {
                throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
            }
            val requestMessages = prepareLocalMultimodalMessages(
                messages = groupRequestHistory,
                workspaceRoot = File(workspacePath),
                mode = groupImageMode,
                budget = imageRequestBudget,
                maxImageBytes = LocalModelPresets.maxNativeImageBytesFor(snapshot.model, snapshot.baseUrl),
            )
            val rawReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = requestMessages,
                step = 100 + index,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = interactiveAttempts,
                temperature = CHAT_ROLEPLAY_TEMPERATURE,
            )
            val finalContent = chatReplyCoordinator.finalizeGroup(
                snapshot = snapshot,
                persona = persona,
                member = member,
                input = input,
                index = index,
                rawReply = rawReply,
                sharedContext = snapshot.groupChat.context,
                usageContext = buildTokenUsageContext(
                    snapshot = snapshot,
                    action = TokenUsageAction.GROUP_REPLY,
                    turnId = turnId,
                    runKind = LocalAgentRunKind.FOREGROUND,
                    agentId = member.galleryId,
                    taskLabel = input,
                    step = 100 + index,
                ),
                retryRaw = { repairHint ->
                    completeWithRetry(
                        key = key,
                        snapshot = snapshot,
                        messages = withTailEphemeralContext(requestMessages, repairHint),
                        step = 100 + index,
                        toolsOverride = JsonArray(emptyList()),
                        publishPreview = false,
                        maxAttemptsOverride = 1,
                        allowContextOverflowRecovery = false,
                        temperature = CHAT_ROLEPLAY_TEMPERATURE,
                    )
                },
                appendEvent = { type, data ->
                    eventLog.append(type, data)
                },
            )

            eventLog.append("group/agent-latency", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("index", index)
                put("status", "success")
                put("elapsed_ms", (System.nanoTime() - startedAtNanos) / 1_000_000L)
            })
            GroupGeneratedReply(
                member = member,
                persona = persona,
                content = finalContent,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            eventLog.append("group/agent-latency", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("index", index)
                put("status", "failed")
                put("elapsed_ms", (System.nanoTime() - startedAtNanos) / 1_000_000L)
            })
            val failure = if (hasLocalImageRefs(baseHistory) && imageInputUnsupported(error)) {
                imageCapabilities.markUnsupported(snapshot.baseUrl, snapshot.model)
                IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。", error)
            } else {
                error
            }
            GroupGeneratedReply(
                member = member,
                persona = persona,
                failure = failure,
            )
        }
    }

    private fun recordGroupDiary(
        member: LocalGroupChatMember,
        persona: PersonaProfile,
        plan: com.labteto.dshmobile.local.chat.ChatPostTurnPlan,
        sharedPending: List<ChatPendingTurn>,
        snapshot: LocalHarnessState,
    ) = recordGroupDiaryDelta(
        member = member,
        persona = persona,
        delta = plan.diaryDelta,
        turnSignificance = plan.turnSignificance,
        sharedPending = sharedPending,
        snapshot = snapshot,
    )

    private fun recordGroupDiaryDelta(
        member: LocalGroupChatMember,
        persona: PersonaProfile,
        delta: ChatDiaryDelta?,
        turnSignificance: String,
        sharedPending: List<ChatPendingTurn>,
        snapshot: LocalHarnessState,
    ) {
        if (!snapshot.autoMemory) return
        val subjectKey = com.labteto.dshmobile.local.chat.chatRelationshipSubjectKey(
            member.galleryId,
            member.personaId,
        ) ?: return
        runCatching {
            diaryStore.record(
                ChatDiaryWriteRequest(
                    subjectKey = subjectKey,
                    personaName = persona.name,
                    delta = delta,
                    turnSignificance = turnSignificance,
                    sourceMode = ChatDiarySourceMode.GROUP,
                    sourceSessionId = snapshot.sessionId,
                    sourceUserMessageIds = sharedPending.map(ChatPendingTurn::userMessageId),
                    sourceAssistantMessageIds = sharedPending.map(ChatPendingTurn::assistantMessageId),
                    evidenceText = sharedPending.joinToString("\n") { turn ->
                        listOf(turn.userMessage, turn.assistantMessage)
                            .filter(String::isNotBlank)
                            .joinToString(" ")
                    },
                    generation = snapshot.groupChat.context.generation,
                ),
            )
        }.onSuccess { diary ->
            if (diary != null) {
                eventLog.append("group/diary", buildJsonObject {
                    put("gallery_id", member.galleryId)
                    put("diary_id", diary.id)
                    put("importance", diary.importance)
                    put("status", "recorded")
                })
            }
        }.onFailure { error ->
            eventLog.append("group/diary", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("status", "failed")
                put("detail", error.message.orEmpty().take(800))
            })
        }
    }

    private suspend fun refreshGroupMemberState(
        member: LocalGroupChatMember,
        persona: PersonaProfile,
        userMessage: String,
        assistantMessage: String,
        sharedPending: List<ChatPendingTurn>,
        step: Int,
    ): ChatCharacterState? {
        val snapshot = _state.value
        val key = modelRequestMarkerOrNull(snapshot) ?: return null
        val sharedContext = _state.value.groupChat.context
        val plannerState = member.chatState.withContextForPlanner(sharedContext)
        val prompt = buildString {
            appendLine(
                chatTurnCoordinator.postTurnPrompt(
                    persona = persona,
                    state = plannerState,
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                ),
            )
            appendLine()
            appendLine("【群聊共享待归并回合】")
            appendLine(renderPendingTurnsForPlanner(sharedPending))
            append("continuity 只能根据以上共享待归并回合更新；角色私有情绪、关系与互动状态仍只根据当前角色自己的本轮对话更新。")
        }
        return try {
            val plannerReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = chatPostTurnModelMessages(prompt),
                step = step,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = 1,
            )
            usageTracker.record(
                snapshot = snapshot,
                reply = plannerReply,
                action = TokenUsageAction.GROUP_STATE_REFRESH,
                turnId = snapshot.transcriptIndex.latestUserMessageId,
                runKind = LocalAgentRunKind.FOREGROUND,
                agentId = member.galleryId,
                taskLabel = userMessage,
                step = step,
            )
            val plan = chatTurnCoordinator.parsePostTurn(
                plannerReply.content.orEmpty(),
                previous = plannerState,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
            ) ?: return null
            recordGroupDiary(member, persona, plan, sharedPending, snapshot)
            plan.state
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            eventLog.append("group/post-turn", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
            })
            null
        }
    }

    private suspend fun refreshGroupMemberStates(
        replies: List<GroupReplyForStateUpdate>,
        userMessage: String,
        sharedPending: List<ChatPendingTurn>,
    ): GroupStateRefreshBatch {
        if (replies.isEmpty()) return GroupStateRefreshBatch(emptyMap(), complete = true)
        val initialSnapshot = _state.value
        val responderIds = replies.mapTo(hashSetOf()) { it.member.galleryId }
        val observers = initialSnapshot.groupChat.members
            .filter { it.galleryId !in responderIds }
            .map { member ->
                member to (
                    chatPersonaStore.get(member.personaId)
                )
            }
        if (replies.size == 1 && observers.isEmpty()) {
            val reply = replies.single()
            val refreshed = refreshGroupMemberState(
                member = reply.member,
                persona = reply.persona,
                userMessage = userMessage,
                assistantMessage = reply.content,
                sharedPending = sharedPending,
                step = CHAT_POST_TURN_MODEL_STEP + 100,
            )
            return GroupStateRefreshBatch(
                states = mapOf(reply.member.galleryId to (refreshed ?: reply.member.chatState)),
                complete = refreshed != null,
            )
        }

        val snapshot = _state.value
        val key = modelRequestMarkerOrNull(snapshot) ?: return GroupStateRefreshBatch(
            states = replies.associate { it.member.galleryId to it.member.chatState },
            complete = false,
        )
        val prompt = buildString {
            appendLine("你要一次整理多个群聊角色各自的隐藏状态。每个角色的私有状态完全隔离，禁止把甲角色的判断、关系或经历写进乙角色。")
            appendLine("【群聊共享待归并回合】")
            appendLine(renderPendingTurnsForPlanner(sharedPending))
            appendLine("每个角色 plan 的 continuity 只能根据以上共享待归并回合更新；私有情绪、关系与互动状态只根据该角色自己的本轮对话更新。")
            appendLine("最终只输出一个 JSON 对象，格式为：")
            appendLine("""{"plans":[{"galleryId":"人物ID","plan":{"state":{},"suggestions":[],"turnSignificance":"NONE|MINOR|MAJOR","diaryDelta":null}}],"observerDiaries":[{"galleryId":"人物ID","turnSignificance":"NONE|MINOR|MAJOR","diaryDelta":null}]}""")
            appendLine("每个 plan 必须分别遵循对应角色下面的状态更新规则；suggestions 固定输出空数组，禁止附加解释。")
            appendLine("observerDiaries 只给本轮在场但未发言的角色记录其亲历后的主观记忆，不更新 state；无持续影响时 diaryDelta=null。")
            replies.forEach { reply ->
                val memberPrompt = chatTurnCoordinator.postTurnPrompt(
                    persona = reply.persona,
                    state = reply.member.chatState.withContextForPlanner(
                        snapshot.groupChat.context,
                    ),
                    userMessage = userMessage,
                    assistantMessage = reply.content,
                ).replace(
                    "不要继续扮演角色，不要解释过程，不要使用 Markdown，只输出一个 JSON 对象。",
                    "不要继续扮演角色，不要解释过程。",
                )
                appendLine()
                appendLine("===== 人物 ${reply.member.galleryId} / ${reply.persona.name} =====")
                appendLine(memberPrompt)
            }
            observers.forEach { (member, persona) ->
                appendLine()
                appendLine("===== 在场旁观人物 ${member.galleryId} / ${persona.name} =====")
                appendLine("人物底色：${persona.portrait.take(320)}")
                if (persona.coreValues.isNotEmpty()) appendLine("真正重要：${persona.coreValues.take(3).joinToString("；")}")
                persona.initialUserImpression.takeIf(String::isNotBlank)?.let { appendLine("对用户初始印象：${it.take(180)}") }
                appendLine(
                    "当前心理：情绪=${member.chatState.mood}｜关系=${member.chatState.relationshipState}｜" +
                        "关注=${member.chatState.currentFocus.take(120).ifBlank { "无" }}｜" +
                        "内在拉扯=${member.chatState.internalConflict.take(120).ifBlank { "无" }}",
                )
                appendLine("只根据共享待归并回合判断这个角色是否形成值得长期记住的经历；必须沿用上面的日记质量规则，禁止把其他角色的心理当成自己的。")
            }
        }

        return try {
            val plannerReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = chatPostTurnModelMessages(prompt),
                step = CHAT_POST_TURN_MODEL_STEP + 100,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = 1,
            )
            usageTracker.record(
                snapshot = snapshot,
                reply = plannerReply,
                action = TokenUsageAction.GROUP_STATE_REFRESH,
                turnId = snapshot.transcriptIndex.latestUserMessageId,
                runKind = LocalAgentRunKind.FOREGROUND,
                taskLabel = userMessage,
                step = CHAT_POST_TURN_MODEL_STEP + 100,
            )
            val root = json.parseToJsonElement(plannerReply.content.orEmpty()).jsonObject
            val plans = root["plans"]?.jsonArray.orEmpty()
            val result = linkedMapOf<String, ChatCharacterState>()
            plans.forEach { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                val galleryId = item["galleryId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val source = replies.firstOrNull { it.member.galleryId == galleryId } ?: return@forEach
                val plan = item["plan"] ?: return@forEach
                val parsed = chatTurnCoordinator.parsePostTurn(
                    text = plan.toString(),
                    previous = source.member.chatState.withContextForPlanner(
                        snapshot.groupChat.context,
                    ),
                    userMessage = userMessage,
                    assistantMessage = source.content,
                ) ?: return@forEach
                recordGroupDiary(source.member, source.persona, parsed, sharedPending, snapshot)
                result[galleryId] = parsed.state
            }
            val observerDiaries = root["observerDiaries"]?.jsonArray.orEmpty()
            observerDiaries.forEach { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                val galleryId = item["galleryId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val observer = observers.firstOrNull { (member, _) -> member.galleryId == galleryId }
                    ?: return@forEach
                val significance = item["turnSignificance"]?.jsonPrimitive?.contentOrNull ?: "NONE"
                val diaryObject = item["diaryDelta"] as? JsonObject
                val delta = diaryObject?.let {
                    runCatching {
                        json.decodeFromJsonElement(ChatDiaryDelta.serializer(), it)
                    }.getOrNull()
                }
                recordGroupDiaryDelta(
                    member = observer.first,
                    persona = observer.second,
                    delta = delta,
                    turnSignificance = significance,
                    sharedPending = sharedPending,
                    snapshot = snapshot,
                )
            }
            val complete = replies.all { reply -> reply.member.galleryId in result }
            replies.forEach { reply ->
                if (reply.member.galleryId !in result) {
                    result[reply.member.galleryId] = reply.member.chatState
                }
            }
            GroupStateRefreshBatch(result, complete)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            eventLog.append("group/post-turn-batch", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
                put("count", replies.size)
            })
            GroupStateRefreshBatch(
                states = replies.associate { reply ->
                    reply.member.galleryId to reply.member.chatState
                },
                complete = false,
            )
        }
    }

    suspend fun run(
        input: String,
        sourceMessageId: String? = null,
    ) {
        _state.update {
            it.copy(
                running = true,
                error = null,
                replySuggestions = emptyList(),
                groupActiveSpeakerName = null,
                deviceApprovalLease = false,
                pendingApproval = null,
                pendingQuestion = null,
            )
        }
        try {
            val snapshot = _state.value
            require(snapshot.groupChat.members.size >= MIN_GROUP_CHAT_MEMBERS) {
                "群聊至少需要添加 $MIN_GROUP_CHAT_MEMBERS 个角色"
            }
            ensureSystemMessage()
            captureAutoMemoryDirective(input, sourceMessageId)

            val key = modelRequestMarkerOrNull(snapshot) ?: error("请先配置模型账户或 API Key")
            val members = snapshot.groupChat.members
            val cursor = snapshot.groupChat.turnCursor % members.size
            val rotated = members.drop(cursor) + members.take(cursor)
            val responders = groupChatResponders(input, rotated)
            require(responders.isNotEmpty()) { "群聊里还没有可发言的角色" }
            val groupMemoryContexts = responders.associate { member ->
                val persona = chatPersonaStore.get(member.personaId)
                val subjectKey = com.labteto.dshmobile.local.chat.chatRelationshipSubjectKey(
                    member.galleryId,
                    member.personaId,
                )
                member.galleryId to subjectKey?.let {
                    chatMemoryContextAction(input, snapshot, it, persona.name)
                }.orEmpty()
            }
            val groupPromptTokens = responders.maxOfOrNull { member ->
                val persona = chatPersonaStore.get(member.personaId)
                val turnContext = chatReplyCoordinator.buildGroupTurnContext(
                    persona = persona,
                    member = member,
                    input = input,
                    allMembers = members,
                    handoffSummary = snapshot.handoffSummary,
                    sharedContext = snapshot.groupChat.context,
                    memoryContext = groupMemoryContexts[member.galleryId].orEmpty(),
                    announcement = snapshot.groupChat.announcement,
                    mayStaySilent = false,
                    silentToken = GROUP_CHAT_SILENT_TOKEN,
                )
                estimateModelTokens(turnContext.prompt)
            } ?: 0
            compactHistoryIfNeeded(extraTokens = groupPromptTokens)

            eventLog.append("turn/start", buildJsonObject {
                put("model", snapshot.model)
                put("mode", "group-chat")
                put("member_count", members.size)
                put("responder_count", responders.size)
            })

            val turnId = sourceMessageId ?: snapshot.transcriptIndex.latestUserMessageId
            var currentGroup = snapshot.groupChat
            var deliveredReplies = 0
            val repliesForStateUpdate = mutableListOf<GroupReplyForStateUpdate>()
            val baseHistory = boundedGroupChatRequestHistory(modelHistory.snapshot())

            coroutineScope {
                val generatedReplies = responders.mapIndexed { index, initialMember ->
                    val member = currentGroup.members.firstOrNull {
                        it.galleryId == initialMember.galleryId
                    } ?: initialMember
                    async {
                        generateGroupReply(
                            key = key,
                            snapshot = snapshot,
                            baseHistory = baseHistory,
                            input = input,
                            allMembers = members,
                            member = member,
                            memoryContext = groupMemoryContexts[member.galleryId].orEmpty(),
                            index = index,
                            turnId = turnId,
                        )
                    }
                }

                generatedReplies.forEachIndexed { index, deferred ->
                    val initialMember = responders[index]
                    _state.update { current ->
                        current.copy(groupActiveSpeakerName = initialMember.displayName)
                    }

                    val generated = deferred.await()
                    generated.failure?.let { failure ->
                        eventLog.append("group/agent-failed", buildJsonObject {
                            put("gallery_id", generated.member.galleryId)
                            put("persona_id", generated.member.personaId)
                            put("detail", failure.message.orEmpty().take(1_000))
                        })
                        if (responders.size == 1) {
                            throw (failure as? Exception
                                ?: IllegalStateException(failure.message ?: "群聊角色回复失败", failure))
                        }
                        return@forEachIndexed
                    }

                    val content = generated.content
                    if (content.isBlank() || content == GROUP_CHAT_SILENT_TOKEN) {
                        eventLog.append("group/agent-empty", buildJsonObject {
                            put("gallery_id", generated.member.galleryId)
                            put("persona_id", generated.member.personaId)
                        })
                        return@forEachIndexed
                    }

                    val transcript = transcriptRuntime.newMessage(
                        role = "assistant",
                        content = content,
                        speakerId = generated.member.galleryId,
                        speakerName = generated.persona.name,
                    )
                    val eventData = transcriptRuntime.withTranscript(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", content)
                            put("speaker_id", generated.member.galleryId)
                            put("speaker_name", generated.persona.name)
                        },
                        listOf(transcript),
                    )
                    val assistantEvent = eventLog.append("assistant/message", eventData)
                    modelHistory.append(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", groupTranscriptLine(transcript))
                        },
                    )
                    updateContextMetrics()
                    val beforeAssistant = _state.value
                    transcriptRuntime.applyMessages(
                        listOf(transcript),
                        assistantEvent.sequence,
                    )
                    val pendingContext = currentGroup.context
                        .applySceneTurn(
                            userMessage = input,
                            assistantMessage = content,
                            sequence = assistantEvent.sequence,
                        )
                        .enqueuePendingDurably(
                        ChatPendingTurn(
                            sequence = assistantEvent.sequence,
                            userMessageId = beforeAssistant.transcriptIndex.latestUserMessageId.orEmpty(),
                            assistantMessageId = transcript.id,
                            branchHeadId = transcript.id,
                            userMessage = input,
                            assistantMessage = content,
                            generation = currentGroup.context.generation,
                        ),
                        eventLog,
                        scope = "group",
                    )
                    currentGroup = currentGroup.copy(context = pendingContext)
                    _state.update { current ->
                        if (current.sessionId == snapshot.sessionId) {
                            current.copy(groupChat = currentGroup)
                        } else {
                            current
                        }
                    }
                    if (
                        beforeAssistant.chatBranches.nodes.isNotEmpty() &&
                        beforeAssistant.transcriptIndex.branchingEligible
                    ) {
                        _state.update { current ->
                            current.copy(
                                chatBranches = appendMaterializedChatBranchMessage(
                                    current = current.chatBranches,
                                    activeMessages = beforeAssistant.messages,
                                    message = transcript,
                                    parentId = beforeAssistant.transcriptIndex.latestDialogueMessageId,
                                    chatState = current.chatState,
                                    chatContext = current.groupChat.context,
                                    replySuggestions = emptyList(),
                                ),
                            )
                        }
                    }
                    deliveredReplies += 1
                    repliesForStateUpdate += GroupReplyForStateUpdate(
                        member = generated.member,
                        persona = generated.persona,
                        content = content,
                    )
                }
            }

            require(deliveredReplies > 0) { "群聊角色这一轮都没有给出可用回复" }

            val sharedPendingForRefresh = currentGroup.context.loadPendingBatch(
                eventLog, GROUP_POST_TURN_PENDING_BATCH, scope = "group",
                activeBranchMessageIds = if (hasChatBranchAlternatives(_state.value.chatBranches)) {
                    com.labteto.dshmobile.local.activeChatBranchMessages(_state.value.chatBranches).mapTo(hashSetOf()) { it.id }
                } else null,
            )
            val refreshBatch = refreshGroupMemberStates(
                replies = repliesForStateUpdate,
                userMessage = input,
                sharedPending = sharedPendingForRefresh,
            )
            val refreshedStates = refreshBatch.states
            val refreshedInReplyOrder = repliesForStateUpdate
                .mapNotNull { reply -> refreshedStates[reply.member.galleryId] }
            val nextSharedContext = finalizeGroupContextAfterRefresh(
                context = currentGroup.context,
                statesInReplyOrder = refreshedInReplyOrder,
                processedPending = sharedPendingForRefresh,
                complete = refreshBatch.complete,
            )
            if (!refreshBatch.complete) {
                eventLog.append("group/post-turn-batch", buildJsonObject {
                    put("status", "pending-preserved")
                    put("pending_count", currentGroup.context.pendingTurns.size)
                })
            }
            currentGroup = currentGroup.copy(
                context = nextSharedContext,
                members = currentGroup.members.map { existing ->
                    val nextState = refreshedStates[existing.galleryId] ?: return@map existing
                    val source = repliesForStateUpdate.firstOrNull { it.member.galleryId == existing.galleryId }
                    existing.copy(
                        displayName = source?.persona?.name ?: existing.displayName,
                        chatState = nextState.copy(
                            scene = ChatSceneState(),
                            continuity = ChatContinuityState(),
                        ),
                    )
                },
            )
            val nextCursor = (snapshot.groupChat.turnCursor + 1) % members.size
            currentGroup = currentGroup.copy(turnCursor = nextCursor)
            _state.update { current ->
                if (current.sessionId == snapshot.sessionId) {
                    current.copy(
                        groupChat = currentGroup,
                        groupActiveSpeakerName = null,
                        replySuggestions = emptyList(),
                    )
                } else {
                    current
                }
            }

            eventLog.append("turn/end", buildJsonObject {
                put("reason", "completed")
                put("mode", "group-chat")
                put("replies", deliveredReplies)
            })
            if (hasChatBranchAlternatives(_state.value.chatBranches)) {
                persistChatBranchState("group/branch-completed")
            }
            checkpointModelHistory("group/completed")
            persistNow()
            projectGalleryState(_state.value.groupChat, "turn-complete")
        } catch (cancelled: CancellationException) {
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "aborted")
                put("mode", "group-chat")
            })
            checkpointModelHistory("group/cancelled")
            persist()
            throw cancelled
        } catch (error: Exception) {
            val detail = error.message ?: "群聊请求失败"
            _state.update { it.copy(error = detail, groupActiveSpeakerName = null) }
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "error")
                put("mode", "group-chat")
                put("detail", detail.take(2_000))
            })
            checkpointModelHistory("group/failed")
            persist()
        } finally {
            _state.update {
                it.copy(
                    running = false,
                    groupActiveSpeakerName = null,
                    pendingApproval = null,
                    pendingQuestion = null,
                    deviceApprovalLease = false,
                )
            }
            persist()
            finishTurn(currentCoroutineContext()[Job])
        }
    }
    private suspend fun modelRequestMarkerOrNull(snapshot: LocalHarnessState): String? =
        try {
            modelGateway.profileForRoute(
                profileId = snapshot.modelSelection.activeProfileId,
                model = snapshot.model,
                baseUrl = snapshot.baseUrl,
            ).id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

}
