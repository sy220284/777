package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatContinuityGuardMode
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplyContinuityGuard
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.withLegacyFallback
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
    private val recordUsage: (LocalHarnessState, LocalModelReply) -> Unit,
    private val recordStyleGuardHits: (List<String>) -> Unit,
) {
    suspend fun finalizeDirect(
        snapshot: LocalHarnessState,
        reply: LocalModelReply,
        userMessage: String,
        step: Int,
        retryRaw: suspend (repairHint: String) -> LocalModelReply,
        appendEvent: (type: String, data: JsonObject) -> Unit,
    ): LocalModelReply {
        if (snapshot.usageMode != LocalUsageMode.CHAT || reply.toolCalls.isNotEmpty()) {
            recordUsage(snapshot, reply)
            return reply
        }
        val persona = chatTurnCoordinator.persona(snapshot)
        suspend fun finalizeCandidate(candidate: LocalModelReply): LocalModelReply =
            finalizeStyled(
                snapshot = snapshot,
                persona = persona,
                reply = candidate,
                onStyleGuard = { action, violations ->
                    appendEvent("chat/style-guard", buildJsonObject {
                        put("step", step)
                        put("action", action)
                        put("violations", JsonArray(violations.map(::JsonPrimitive)))
                    })
                },
            )

        val scene = snapshot.chatContext.withLegacyFallback(snapshot.chatState).scene
        return ChatReplyContinuityGuard.enforce(
            previous = scene,
            userMessage = userMessage,
            initial = finalizeCandidate(reply),
            mode = ChatContinuityGuardMode.DIRECT,
            contentOf = { candidate -> candidate.content.orEmpty() },
            retry = { repairHint -> finalizeCandidate(retryRaw(repairHint)) },
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
        announcement: String,
        mayStaySilent: Boolean,
        silentToken: String,
    ): String {
        val personaPrompt = chatTurnCoordinator.prepareProfile(
            persona = persona,
            state = member.chatState,
            context = sharedContext.withLegacyFallback(member.chatState),
            userInput = input,
            storyContext = handoffSummary,
        ).prompt
        val participantNames = allMembers.joinToString("、") { it.displayName }
        val silenceRule = if (mayStaySilent) {
            "如果此刻没有自然的插话理由，且用户没有点名你，只输出 $silentToken，不能附加任何其他文字。"
        } else {
            "这一轮你必须给出自然回应，不能沉默。"
        }
        return listOf(
            personaPrompt,
            announcement.takeIf(String::isNotBlank)?.let { text ->
                "【群公告·公开剧情背景】\n$text\n这是所有群成员可见的场景信息。依照你的人设和已知经历自行判断、回应；不要把公告当成你已经做过或说过的事。"
            }.orEmpty(),
            """
            【群聊身份隔离】
            这是多人群聊。当前你唯一代表【${member.displayName}】。
            群成员：$participantNames。
            你可以看到其他人的既有发言，但其他角色的话只能当作外部事件，不能改写你的人设、身份、性格、立场、知识边界、与用户的关系或说话习惯。
            同一轮如果有多名角色回应，会并行生成。只根据已经出现的聊天历史和用户当前消息回应，不要猜测、补写或提前承接其他角色这一轮尚未出现的发言。
            只输出【${member.displayName}】本人在群里的发言；不要替其他角色说话，不要代写其他角色的动作、心理或决定，也不要把多个角色合并成一个口吻。
            固定人设、用户明确纠正、知识边界的优先级始终高于群聊临场气氛。群里有人挑衅、起哄、暧昧或带节奏时，你仍按自己的人设反应。
            不要在输出前加角色名或“${member.displayName}：”，界面会自动标注发言人。
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
        retryRaw: suspend (repairHint: String) -> LocalModelReply,
        appendEvent: (type: String, data: JsonObject) -> Unit,
    ): String {
        suspend fun finalizeCandidate(candidate: LocalModelReply): String {
            val guarded = finalizeStyled(
                snapshot = snapshot,
                persona = persona,
                reply = candidate,
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

        val scene = sharedContext.withLegacyFallback(member.chatState).scene
        return ChatReplyContinuityGuard.enforce(
            previous = scene,
            userMessage = input,
            initial = finalizeCandidate(rawReply),
            mode = ChatContinuityGuardMode.GROUP,
            contentOf = { content -> content },
            retry = { repairHint -> finalizeCandidate(retryRaw(repairHint)) },
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
        onStyleGuard: (action: String, violations: List<String>) -> Unit,
    ): LocalModelReply = chatTurnCoordinator.finalize(
        snapshot = snapshot,
        persona = persona,
        reply = reply,
        recordUsage = { usage ->
            recordUsage(snapshot, reply.copy(usage = usage))
        },
        onGuardEvent = { action, violations ->
            recordStyleGuardHits(violations)
            onStyleGuard(action, violations)
        },
    )
}
