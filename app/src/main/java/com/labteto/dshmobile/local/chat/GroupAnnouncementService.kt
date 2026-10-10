package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Drafts a public scene premise; the user reviews the result before it is saved. */
class GroupAnnouncementService @Inject constructor(
    private val modelGateway: LocalModelGateway,
    private val usageTracker: DeepSeekUsageTracker,
    private val personaStore: ChatPersonaStore,
) {
    suspend fun generate(
        model: String,
        baseUrl: String,
        profileId: String? = null,
        members: List<LocalGroupChatMember>,
        direction: String,
        current: String,
    ): String {
        val cast = members.joinToString("\n") { member ->
            val persona = personaStore.get(member.personaId)
            val values = listOf(
                persona.coreIdentity.take(180),
                persona.factText(CharacterFactCategories.PERSONALITY).take(140),
                persona.factText(CharacterFactCategories.VALUES_AND_TRADEOFFS).take(160),
            ).filter(String::isNotBlank)
            "${member.displayName}：${values.joinToString("；")}"
        }
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "你负责生成供全员阅读的群公告：给出明确场景、共同事件、当前矛盾和可承接悬念。尊重既有人设，不替角色决定立场、行动、心理或台词，不虚构冲突设定。只输出正文，120～260字。")
            },
            buildJsonObject {
                put("role", "user")
                put("content", "人物：\n$cast\n\n剧情方向：${direction.trim().ifBlank { "基于现有人物关系生成自然、可继续发展的共同事件" }.take(500)}\n\n现有公告参考：${current.take(1_000)}")
            },
        )
        return modelGateway.withFrozenRoute(profileId, model, baseUrl) {
            val reply = modelGateway.complete(
                model = model,
                baseUrl = baseUrl,
                messages = messages,
                tools = JsonArray(emptyList()),
            )
            withContext(Dispatchers.IO) {
            usageTracker.record(
                model = model,
                usage = reply.usage,
                requestId = reply.requestId,
                context = TokenUsageContext(mode = LocalUsageMode.CHAT, action = TokenUsageAction.GROUP_ANNOUNCEMENT),
                promptBreakdown = reply.promptBreakdown,
                route = reply.routeIdentity,
            )
        }
            reply.content?.trim()?.take(2_000)?.takeIf(String::isNotBlank)
                ?: error("模型没有生成可用的群公告")
        }
    }
}
