package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalGroupChatMember
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Drafts a public scene premise; the user reviews the result before it is saved. */
class GroupAnnouncementService @Inject constructor(
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
    private val usageTracker: DeepSeekUsageTracker,
) {
    suspend fun generate(
        model: String,
        baseUrl: String,
        members: List<LocalGroupChatMember>,
        direction: String,
        current: String,
    ): String {
        val key = apiKeys.get()?.trim()?.takeIf(String::isNotBlank)
            ?: error("请先在模型设置里配置密钥")
        val cast = members.joinToString("\n") { member ->
            val persona = member.persona
            "${member.displayName}：${persona.identity.take(100)}；${persona.personality.take(120)}；${persona.relationship.take(120)}"
        }
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "你是群聊剧情策划。只写一段供全员阅读的群公告，包含具体场景、共同事件、迫在眉睫的矛盾和可接话的悬念。用自然中文，约120至260字。尊重人物原设，不替任何角色决定立场、行动、心声或台词，不编造与既有人设相冲突的事实。只输出公告正文。")
            },
            buildJsonObject {
                put("role", "user")
                put("content", "人物：\n$cast\n\n剧情方向：${direction.trim().ifBlank { "有戏剧性与冲突的修罗场，关系张力逐步升级" }.take(500)}\n\n现有公告参考：${current.take(1_000)}")
            },
        )
        val reply = modelClient.complete(key, baseUrl, model, messages, JsonArray(emptyList()))
        usageTracker.record(model, reply.usage)
        return reply.content?.trim()?.take(2_000)?.takeIf(String::isNotBlank)
            ?: error("模型没有生成可用的群公告")
    }
}
