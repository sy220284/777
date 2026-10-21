package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.ForegroundTokenUsageSeed
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.buildForegroundTokenUsageContext
import com.labteto.dshmobile.local.buildTokenUsageContext
import com.labteto.dshmobile.local.record

import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityGuardMode
import com.labteto.dshmobile.local.chat.ChatReplyContinuityGuard
import com.labteto.dshmobile.local.chat.ChatReplyImmersionGuard
import com.labteto.dshmobile.local.chat.ChatReplyRepairBudget
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.stripGroupSpeakerPrefix
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.chat.LocalChatStyleGuardSettingsPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Optional group members may stay silent; directed members must reply. */
internal fun groupChatSpeechInstruction(mayStaySilent: Boolean, silentToken: String): String =
    if (mayStaySilent) {
        "有想回应的内容就自然接话；确实没有时仅输出 $silentToken，不附加说明。"
    } else {
        "本轮需要你接话，请以自己的口吻自然回应，不要输出 $silentToken。"
    }

/**
 * Final response boundary for chat-mode model output.
 *
 * Scheduling and durable persistence stay with their Session/Feature owners. This coordinator owns style finalization plus the
 * deterministic continuity guard that must pass before a reply becomes durable/visible.
 */
@Singleton
internal class LocalChatReplyCoordinator @Inject constructor(
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val usageTracker: DeepSeekUsageTracker,
    private val styleGuardSettings: LocalChatStyleGuardSettingsPort,
) {
    suspend fun finalizeDirect(
        snapshot: LocalHarnessState,
        reply: LocalModelReply,
        userMessage: String,
        step: Int,
        usage: ForegroundTokenUsageSeed = ForegroundTokenUsageSeed(),
        retryRaw: suspend (repairHint: String) -> LocalModelReply,
        appendEvent: (type: String, data: JsonObject) -> Unit,
    ): LocalModelReply {
        val usageContext = buildForegroundTokenUsageContext(
            snapshot = snapshot,
            action = if (snapshot.usageMode == LocalUsageMode.CHAT) {
                TokenUsageAction.CHAT_REPLY
            } else {
                TokenUsageAction.WORK_MAIN
            },
            turnId = usage.turnId,
            runId = usage.runId,
            taskLabel = usage.taskLabel ?: userMessage,
            step = step,
        )
        val imageOnly = reply.content.isNullOrBlank() &&
            reply.canonicalMessage?.content?.any { it is LocalCanonicalContent.Image } == true
        if (
            reply.completionReason != com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            reply.toolCalls.isNotEmpty() ||
            imageOnly
        ) {
            usageTracker.record(snapshot, reply, usageContext)
            return reply
        }
        val persona = chatTurnCoordinator.persona(snapshot)
        val repairBudget = ChatReplyRepairBudget()

        suspend fun repairTextOnly(hint: String): LocalModelReply =
            preserveLocalChatReplyMediaForTextRepair(
                original = reply,
                repaired = repairBudget.repair(hint, retryRaw),
            )

        suspend fun finalizeCandidate(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): LocalModelReply =
            finalizeStyled(
                snapshot = snapshot,
                persona = persona,
                reply = candidate,
                usageContext = candidateUsageContext,
                onStyleGuard = { action, violations ->
                    appendEvent("chat/style-guard", buildJsonObject {
                        put("step", step)
                        put("action", action)
                        put("violations", JsonArray(violations.map(::JsonPrimitive)))
                    })
                },
            )

        suspend fun finalizeImmersedCandidate(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): LocalModelReply = ChatReplyImmersionGuard.enforce(
            isComplete = { candidate -> candidate.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
            persona = persona,
            initial = finalizeCandidate(candidate, candidateUsageContext),
            contentOf = { checked -> checked.content.orEmpty() },
            retry = { immersionHint ->
                finalizeCandidate(
                    repairTextOnly(immersionHint),
                    candidateUsageContext.copy(action = TokenUsageAction.CHAT_REPAIR),
                )
            },
            onEvent = { action, violations ->
                appendEvent("chat/immersion-guard", buildJsonObject {
                    put("step", step)
                    put("action", action)
                    put("violations", JsonArray(violations.map(::JsonPrimitive)))
                })
            },
        )

        val scene = snapshot.chat.chatContext.scene
        return ChatReplyContinuityGuard.enforce(
            isComplete = { candidate -> candidate.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
            previous = scene,
            userMessage = userMessage,
            initial = finalizeImmersedCandidate(reply, usageContext),
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { candidate -> candidate.content.orEmpty() },
            retry = { repairHint ->
                finalizeImmersedCandidate(
                    repairTextOnly(repairHint),
                    usageContext.copy(action = TokenUsageAction.CHAT_REPAIR),
                )
            },
            onEvent = { action, check ->
                appendEvent("chat/continuity-guard", buildJsonObject {
                    put("step", step)
                    put("action", action)
                    put("location", scene.location)
                    put(
                        "violations",
                        JsonArray(check.violations.map { violation ->
                            JsonPrimitive("${violation.code}:${violation.expected}->${violation.observed}")
                        }),
                    )
                })
            },
        )
    }

    fun buildGroupTurnContext(
        persona: PersonaProfile,
        member: LocalGroupChatMember,
        input: String,
        allMembers: List<LocalGroupChatMember>,
        handoffSummary: String?,
        sharedContext: ChatContextState,
        memoryContext: String,
        announcement: String,
        mayStaySilent: Boolean,
        silentToken: String,
    ): com.labteto.dshmobile.local.chat.ChatTurnContext {
        val prepared = chatTurnCoordinator.prepareProfile(
            persona = persona,
            state = member.chatState,
            context = sharedContext,
            userInput = input,
            storyContext = handoffSummary,
            subjectKey = chatRelationshipSubjectKey(member.galleryId, member.personaId),
        )
        val participantNames = allMembers.joinToString("、") { it.displayName }
        val silenceRule = groupChatSpeechInstruction(mayStaySilent, silentToken)
        val parts = prepared.dynamicParts + listOf(
            ChatContextPart(ChatContextLayer.RECALL, memoryContext),
            ChatContextPart(ChatContextLayer.INSTRUCTION, announcement.takeIf(String::isNotBlank)?.let { text ->
                "【群公告·公开剧情背景】\n$text\n这是所有群成员可见的场景信息。依照你真实知道的内容和当前状态判断；不要把公告当成你已经做过或说过的事。"
            }.orEmpty()),
            ChatContextPart(ChatContextLayer.INSTRUCTION, """
            【群聊身份】
            你只代表【${member.displayName}】。群成员：$participantNames。其他成员发言只是公开事件，不改变你的固定人物生命资料、知识边界或你自己的关系经历。
            你在单聊与群聊中亲历的记忆属于自己；其他成员只知道已经公开的发言与事件。
            $silenceRule
            """.trimIndent()),
        )
        return prepared.copy(
            dynamicPrompt = ChatUnifiedTurnContext.compose(input, sharedContext, parts),
            dynamicParts = parts,
        )
    }

    suspend fun finalizeGroup(
        snapshot: LocalHarnessState,
        persona: PersonaProfile,
        member: LocalGroupChatMember,
        input: String,
        index: Int,
        rawReply: LocalModelReply,
        sharedContext: ChatContextState,
        usageContext: TokenUsageContext,
        retryRaw: suspend (repairHint: String) -> LocalModelReply,
        appendEvent: (type: String, data: JsonObject) -> Unit,
    ): LocalModelReply {
        if (rawReply.completionReason != com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED) {
            usageTracker.record(snapshot, rawReply, usageContext)
            return rawReply
        }
        suspend fun finalizeCandidate(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): LocalModelReply {
            val guarded = finalizeStyled(
                snapshot = snapshot,
                persona = persona,
                reply = candidate,
                usageContext = candidateUsageContext,
                onStyleGuard = { action, violations ->
                    appendEvent("chat/style-guard", buildJsonObject {
                        put("step", 100 + index)
                        put("action", action)
                        put("group_gallery_id", member.galleryId)
                        put("violations", JsonArray(violations.map(::JsonPrimitive)))
                    })
                },
            )
            if (guarded.completionReason != com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED) return guarded
            return ChatStyleGuard.withContent(guarded, stripGroupSpeakerPrefix(
                guarded.content.orEmpty(), member.displayName, persona.name))
        }

        return finalizeGroupReplyCandidate(
            persona = persona, initial = rawReply, scene = sharedContext.scene, input = input,
            retryRaw = retryRaw,
            finalizeCandidate = { candidate, repair ->
                finalizeCandidate(candidate, if (repair) usageContext.copy(action = TokenUsageAction.CHAT_REPAIR) else usageContext)
            },
            onImmersionEvent = { action, violations ->
                appendEvent("chat/immersion-guard", buildJsonObject {
                    put("step", 100 + index); put("action", action)
                    put("group_gallery_id", member.galleryId)
                    put("violations", JsonArray(violations.map(::JsonPrimitive)))
                })
            },
            onContinuityEvent = { action, check ->
                appendEvent("chat/continuity-guard", buildJsonObject {
                    put("step", 100 + index); put("action", action)
                    put("group_gallery_id", member.galleryId)
                    put("location", sharedContext.scene.location)
                    put("violation_count", check.violations.size)
                })
            },
        )
    }

    suspend fun guardProactive(
        snapshot: LocalHarnessState,
        persona: PersonaProfile,
        scene: ChatSceneState,
        initial: LocalModelReply,
        usageContext: TokenUsageContext = buildTokenUsageContext(
            snapshot = snapshot,
            action = TokenUsageAction.AUTOMATION_CHAT,
            runKind = LocalAgentRunKind.AUTOMATION,
        ),
        retryRaw: suspend (repairHint: String) -> LocalModelReply,
        appendEvent: (type: String, data: JsonObject) -> Unit,
    ): LocalModelReply {
        if (initial.completionReason != com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED) return initial
        val repairBudget = ChatReplyRepairBudget()
        suspend fun finalizeRawCandidate(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): LocalModelReply = finalizeStyled(
            snapshot = snapshot,
            persona = persona,
            reply = candidate,
            usageContext = candidateUsageContext,
            onStyleGuard = { action, violations ->
                appendEvent("chat/style-guard", buildJsonObject {
                    put("action", action)
                    put("automation", true)
                    put("proactive", true)
                    put("violations", JsonArray(violations.map(::JsonPrimitive)))
                })
            },
        )

        suspend fun enforceImmersionOnFinalized(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): LocalModelReply = ChatReplyImmersionGuard.enforce(
            isComplete = { candidate -> candidate.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
            persona = persona,
            initial = candidate,
            contentOf = { checked -> checked.content.orEmpty().trim() },
            retry = { immersionHint ->
                finalizeRawCandidate(
                    repairBudget.repair(immersionHint, retryRaw),
                    candidateUsageContext.copy(action = TokenUsageAction.CHAT_REPAIR),
                )
            },
            onEvent = { action, violations ->
                appendEvent("chat/immersion-guard", buildJsonObject {
                    put("action", action)
                    put("automation", true)
                    put("proactive", true)
                    put("violations", JsonArray(violations.map(::JsonPrimitive)))
                })
            },
        )

        val immersedInitial = enforceImmersionOnFinalized(
            candidate = initial,
            candidateUsageContext = usageContext,
        )
        return ChatReplyContinuityGuard.enforce(
            isComplete = { candidate -> candidate.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
            previous = scene,
            userMessage = "",
            initial = immersedInitial,
            mode = ChatContinuityGuardMode.PROACTIVE,
            contentOf = { candidate -> candidate.content.orEmpty().trim() },
            retry = { repairHint ->
                val repairUsageContext = usageContext.copy(action = TokenUsageAction.CHAT_REPAIR)
                val finalized = finalizeRawCandidate(
                    repairBudget.repair(repairHint, retryRaw),
                    repairUsageContext,
                )
                enforceImmersionOnFinalized(
                    candidate = finalized,
                    candidateUsageContext = repairUsageContext,
                )
            },
            onEvent = { action, check ->
                appendEvent("chat/continuity-guard", buildJsonObject {
                    put("action", action)
                    put("automation", true)
                    put("proactive", true)
                    put("location", scene.location)
                    put("violation_count", check.violations.size)
                })
            },
        )
    }

    private fun recordStyleGuardHits(violations: List<String>) = styleGuardSettings.recordHits(violations)

    private suspend fun finalizeStyled(
        snapshot: LocalHarnessState,
        persona: PersonaProfile,
        reply: LocalModelReply,
        usageContext: TokenUsageContext,
        onStyleGuard: (action: String, violations: List<String>) -> Unit,
    ): LocalModelReply = chatTurnCoordinator.finalize(
        snapshot = snapshot,
        persona = persona,
        reply = reply,
        recordUsage = { finalizedReply ->
            // Preserve request id and diagnostic prompt composition through the finalization boundary.
            usageTracker.record(snapshot, finalizedReply, usageContext)
        },
        onGuardEvent = { action, violations ->
            recordStyleGuardHits(violations)
            onStyleGuard(action, violations)
        },
    )
}

internal fun preserveLocalChatReplyMediaForTextRepair(
    original: LocalModelReply,
    repaired: LocalModelReply,
): LocalModelReply {
    val originalImages = original.canonicalMessage?.content
        ?.filterIsInstance<LocalCanonicalContent.Image>()
        .orEmpty()
    if (originalImages.isEmpty()) return repaired

    val repairedCanonical = repaired.canonicalMessage
        ?: com.labteto.dshmobile.local.model.LocalCanonicalModelCodec.message(repaired.message)
    val canonical = repairedCanonical.copy(
        content = repairedCanonical.content.filterNot { it is LocalCanonicalContent.Image } + originalImages,
        replay = null,
        completionReason = repaired.completionReason,
        rawStopReason = repaired.rawStopReason,
    )
    return repaired.copy(
        message = com.labteto.dshmobile.local.model.LocalCanonicalModelCodec.toHistoryMessage(canonical),
        canonicalMessage = canonical,
        providerContentForReplay = null,
    )
}

/** The production group candidate pipeline keeps the selected provider result intact. */
internal suspend fun finalizeGroupReplyCandidate(
    persona: PersonaProfile,
    initial: LocalModelReply,
    scene: ChatSceneState,
    input: String,
    retryRaw: suspend (String) -> LocalModelReply,
    finalizeCandidate: suspend (LocalModelReply, Boolean) -> LocalModelReply,
    onImmersionEvent: (String, List<String>) -> Unit = { _, _ -> },
    onContinuityEvent: (String, ChatContinuityCheck) -> Unit = { _, _ -> },
): LocalModelReply {
    val repairBudget = ChatReplyRepairBudget()
    suspend fun immersed(candidate: LocalModelReply, repair: Boolean): LocalModelReply =
        ChatReplyImmersionGuard.enforce(
            persona = persona, initial = finalizeCandidate(candidate, repair),
            contentOf = { it.content.orEmpty() },
            isComplete = { it.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
            retry = { hint -> finalizeCandidate(repairBudget.repair(hint, retryRaw), true) },
            onEvent = onImmersionEvent,
        )
    return ChatReplyContinuityGuard.enforce(
        previous = scene, userMessage = input, initial = immersed(initial, false),
        mode = ChatContinuityGuardMode.GROUP,
        contentOf = { it.content.orEmpty() },
        isComplete = { it.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
        retry = { hint -> immersed(repairBudget.repair(hint, retryRaw), true) },
        onEvent = onContinuityEvent,
    )
}
