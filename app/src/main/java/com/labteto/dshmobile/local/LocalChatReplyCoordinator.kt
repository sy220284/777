package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalAgentRunKind

import com.labteto.dshmobile.local.chat.ChatReplyRepairBudget
import com.labteto.dshmobile.local.chat.ChatContinuityGuardMode
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplyContinuityGuard
import com.labteto.dshmobile.local.chat.ChatReplyImmersionGuard
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.PersonaProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Final response boundary for chat-mode model output.
 *
 * The engine owns scheduling and persistence. This coordinator owns style finalization plus the
 * deterministic continuity guard that must pass before a reply becomes durable/visible.
 */
internal class LocalChatReplyCoordinator(
    private val chatTurnCoordinator: LocalChatTurnCoordinator,
    private val recordUsage: (LocalHarnessState, LocalModelReply, TokenUsageContext) -> Unit,
    private val recordStyleGuardHits: (List<String>) -> Unit,
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
        if (snapshot.usageMode != LocalUsageMode.CHAT || reply.toolCalls.isNotEmpty()) {
            recordUsage(snapshot, reply, usageContext)
            return reply
        }
        val persona = chatTurnCoordinator.persona(snapshot)
        val repairBudget = ChatReplyRepairBudget()
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
            persona = persona,
            initial = finalizeCandidate(candidate, candidateUsageContext),
            contentOf = { checked -> checked.content.orEmpty() },
            retry = { immersionHint ->
                finalizeCandidate(
                    repairBudget.repair(immersionHint, retryRaw),
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
            previous = scene,
            userMessage = userMessage,
            initial = finalizeImmersedCandidate(reply, usageContext),
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { candidate -> candidate.content.orEmpty() },
            retry = { repairHint ->
                finalizeImmersedCandidate(
                    repairBudget.repair(repairHint, retryRaw),
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
        )
        val participantNames = allMembers.joinToString("、") { it.displayName }
        val silenceRule = if (mayStaySilent) {
            "未被点名且无自然回应理由时，只输出 $silentToken。"
        } else {
            "本轮必须自然回应，不得输出沉默标记。"
        }
        val groupDynamic = listOf(
            prepared.dynamicPrompt,
            memoryContext,
            announcement.takeIf(String::isNotBlank)?.let { text ->
                "【群公告·公开剧情背景】\n$text\n这是所有群成员可见的场景信息。依照你真实知道的内容和当前状态判断；不要把公告当成你已经做过或说过的事。"
            }.orEmpty(),
            """
            【群聊身份】
            你只代表【${member.displayName}】。群成员：$participantNames。其他成员发言只是公开事件，不改变你的固定人物生命资料、知识边界或你自己的关系经历。
            你可以记得自己在单聊和群聊中真实经历过的事，并按自己的性格、关系、当前情境和表达意愿自然提及；某段旧记忆只有在本轮真正说出口后，才成为其他群成员此刻听到的新信息。
            不得猜测同轮尚未出现的发言，也不得代写他人的语言、动作、心理或决定。
            只输出本人发言，不加角色名前缀。$silenceRule
            """.trimIndent(),
        ).filter(String::isNotBlank).joinToString("\n\n")
        return prepared.copy(dynamicPrompt = groupDynamic)
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
    ): String {
        val repairBudget = ChatReplyRepairBudget()
        suspend fun finalizeCandidate(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): String {
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
            return stripGroupSpeakerPrefix(
                guarded.content.orEmpty(),
                member.displayName,
                persona.name,
            )
        }

        suspend fun finalizeImmersedCandidate(
            candidate: LocalModelReply,
            candidateUsageContext: TokenUsageContext,
        ): String = ChatReplyImmersionGuard.enforce(
            persona = persona,
            initial = finalizeCandidate(candidate, candidateUsageContext),
            contentOf = { content -> content },
            retry = { immersionHint ->
                finalizeCandidate(
                    repairBudget.repair(immersionHint, retryRaw),
                    candidateUsageContext.copy(action = TokenUsageAction.CHAT_REPAIR),
                )
            },
            onEvent = { action, violations ->
                appendEvent("chat/immersion-guard", buildJsonObject {
                    put("step", 100 + index)
                    put("action", action)
                    put("group_gallery_id", member.galleryId)
                    put("violations", JsonArray(violations.map(::JsonPrimitive)))
                })
            },
        )

        val scene = sharedContext.scene
        return ChatReplyContinuityGuard.enforce(
            previous = scene,
            userMessage = input,
            initial = finalizeImmersedCandidate(rawReply, usageContext),
            mode = ChatContinuityGuardMode.GROUP,
            contentOf = { content -> content },
            retry = { repairHint ->
                finalizeImmersedCandidate(
                    repairBudget.repair(repairHint, retryRaw),
                    usageContext.copy(action = TokenUsageAction.CHAT_REPAIR),
                )
            },
            onEvent = { action, check ->
                appendEvent("chat/continuity-guard", buildJsonObject {
                    put("step", 100 + index)
                    put("action", action)
                    put("group_gallery_id", member.galleryId)
                    put("location", scene.location)
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
            recordUsage(snapshot, finalizedReply, usageContext)
        },
        onGuardEvent = { action, violations ->
            recordStyleGuardHits(violations)
            onStyleGuard(action, violations)
        },
    )
}
