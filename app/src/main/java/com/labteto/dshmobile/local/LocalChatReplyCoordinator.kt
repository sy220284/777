package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatContinuityGuardMode
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplyContinuityGuard
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

        val scene = snapshot.chatContext.scene
        return ChatReplyContinuityGuard.enforce(
            previous = scene,
            userMessage = userMessage,
            initial = finalizeCandidate(reply, usageContext),
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { candidate -> candidate.content.orEmpty() },
            retry = { repairHint ->
                finalizeCandidate(
                    retryRaw(repairHint),
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

    fun buildGroupPrompt(
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
    ): String {
        val personaPrompt = chatTurnCoordinator.prepareProfile(
            persona = persona,
            state = member.chatState,
            context = sharedContext,
            userInput = input,
            storyContext = handoffSummary,
        ).prompt
        val participantNames = allMembers.joinToString("、") { it.displayName }
        val silenceRule = if (mayStaySilent) {
            "未被点名且无自然回应理由时，只输出 $silentToken。"
        } else {
            "本轮必须自然回应，不得输出沉默标记。"
        }
        return listOf(
            personaPrompt,
            memoryContext,
            announcement.takeIf(String::isNotBlank)?.let { text ->
                "【群公告·公开剧情背景】\n$text\n这是所有群成员可见的场景信息。依照你的人设和已知经历自行判断、回应；不要把公告当成你已经做过或说过的事。"
            }.orEmpty(),
            """
            【群聊身份】
            你只代表【${member.displayName}】。群成员：$participantNames。其他成员发言仅作公开事件，不改变你的人设、知识边界或与用户的关系。
            不得猜测同轮尚未出现的发言，也不得代写他人的语言、动作、心理或决定。
            以固定人设和用户明确纠正为准。只输出本人发言，不加角色名前缀。
            $silenceRule
            """.trimIndent(),
        ).filter(String::isNotBlank).joinToString("\n\n")
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

        val scene = sharedContext.scene
        return ChatReplyContinuityGuard.enforce(
            previous = scene,
            userMessage = input,
            initial = finalizeCandidate(rawReply, usageContext),
            mode = ChatContinuityGuardMode.GROUP,
            contentOf = { content -> content },
            retry = { repairHint ->
                finalizeCandidate(
                    retryRaw(repairHint),
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
    ): LocalModelReply = ChatReplyContinuityGuard.enforce(
        previous = scene,
        userMessage = "",
        initial = initial,
        mode = ChatContinuityGuardMode.PROACTIVE,
        contentOf = { candidate -> candidate.content.orEmpty().trim() },
        retry = { repairHint ->
            finalizeStyled(
                snapshot = snapshot,
                persona = persona,
                reply = retryRaw(repairHint),
                usageContext = usageContext.copy(action = TokenUsageAction.CHAT_REPAIR),
                onStyleGuard = { action, violations ->
                    appendEvent("chat/style-guard", buildJsonObject {
                        put("action", action)
                        put("automation", true)
                        put("proactive", true)
                        put("continuity_retry", true)
                        put("violations", JsonArray(violations.map(::JsonPrimitive)))
                    })
                },
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
