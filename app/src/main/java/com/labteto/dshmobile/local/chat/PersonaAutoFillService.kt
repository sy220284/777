package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class PersonaDraft(
    val name: String = "",
    val identity: String = "",
    val background: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val relationship: String = "",
    val worldSetting: String = "",
    val franchise: String = "",
    val timelinePosition: String = "",
    val coreMotivations: List<String> = emptyList(),
    val valuePriorities: List<String> = emptyList(),
    val behaviorPatterns: List<String> = emptyList(),
    val internalContradictions: List<String> = emptyList(),
    val knowledgeBoundary: List<String> = emptyList(),
    val loreEntries: List<PersonaLoreEntry> = emptyList(),
    val hardConstraints: List<String> = emptyList(),
    val exampleDialogues: List<String> = emptyList(),
    val bannedPhrases: List<String> = emptyList(),
    val signaturePhrases: List<String> = emptyList(),
)

/**
 * Turns a short natural-language description (plus recent chat context) into the structured
 * default persona used by chat mode. The generated profile is returned to the UI and persisted
 * through LocalHarnessEngine.configureChatPersona so the normal persona/session path remains
 * the single source of truth.
 */
@Singleton
class PersonaAutoFillService @Inject constructor(
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
    private val usageTracker: DeepSeekUsageTracker,
    private val json: Json,
) {
    suspend fun generate(
        model: String,
        baseUrl: String,
        current: PersonaProfile,
        recentMessages: List<LocalHarnessMessage>,
        description: String,
    ): PersonaProfile {
        val apiKey = apiKeys.get()?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("请先在模型设置里配置密钥")

        val recentContext = recentMessages
            .filter { message -> message.role == "user" || message.role == "assistant" }
            .takeLast(MAX_CONTEXT_MESSAGES)
            .joinToString("\n") { message ->
                val role = when (message.role.lowercase()) {
                    "user" -> "用户"
                    "assistant" -> "助手"
                    else -> message.role
                }
                "${role}：${message.content.take(MAX_MESSAGE_CHARS)}"
            }
            .trim()

        val request = description.trim()
        if (request.isBlank() && recentContext.isBlank()) {
            error("写一句角色描述，或者先在聊天里聊出角色设定")
        }

        val currentContext = buildString {
            appendLine("当前默认角色中已填写的内容：")
            appendLine("名称：${current.name}")
            if (current.identity.isNotBlank()) appendLine("身份：${current.identity}")
            if (current.background.isNotBlank()) appendLine("背景：${current.background}")
            if (current.personality.isNotBlank()) appendLine("性格：${current.personality}")
            if (current.speechStyle.isNotBlank()) appendLine("说话方式：${current.speechStyle}")
            if (current.relationship.isNotBlank()) appendLine("与用户关系：${current.relationship}")
            if (current.worldSetting.isNotBlank()) appendLine("世界设定：${current.worldSetting}")
            if (current.franchise.isNotBlank()) appendLine("作品来源：${current.franchise}")
            if (current.timelinePosition.isNotBlank()) appendLine("时间线：${current.timelinePosition}")
            if (current.coreMotivations.isNotEmpty()) appendLine("核心动机：${current.coreMotivations.joinToString("；")}")
            if (current.valuePriorities.isNotEmpty()) appendLine("价值排序：${current.valuePriorities.joinToString("；")}")
            if (current.behaviorPatterns.isNotEmpty()) appendLine("行为模式：${current.behaviorPatterns.joinToString("；")}")
            if (current.internalContradictions.isNotEmpty()) appendLine("内在矛盾：${current.internalContradictions.joinToString("；")}")
            if (current.knowledgeBoundary.isNotEmpty()) appendLine("知识边界：${current.knowledgeBoundary.joinToString("；")}")
            if (current.hardConstraints.isNotEmpty()) {
                appendLine("不可违反：${current.hardConstraints.joinToString("；")}")
            }
            if (current.exampleDialogues.isNotEmpty()) {
                appendLine("对白参考：${current.exampleDialogues.joinToString("；")}")
            }
            if (current.bannedPhrases.isNotEmpty()) {
                appendLine("禁用表达：${current.bannedPhrases.joinToString("；")}")
            }
            if (current.signaturePhrases.isNotEmpty()) {
                appendLine("常用表达：${current.signaturePhrases.joinToString("；")}")
            }
        }.trim()

        val userPrompt = buildString {
            if (request.isNotBlank()) {
                appendLine("用户补充描述：")
                appendLine(request.take(MAX_DESCRIPTION_CHARS))
                appendLine()
            }
            if (recentContext.isNotBlank()) {
                appendLine("最近聊天，可用于提取已经明确的人设：")
                appendLine(recentContext)
                appendLine()
            }
            append(currentContext)
        }

        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            },
            buildJsonObject {
                put("role", "user")
                put("content", userPrompt)
            },
        )

        val reply = modelClient.complete(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = messages,
            tools = JsonArray(emptyList()),
        )
        usageTracker.record(model, reply.usage)

        val raw = reply.content?.trim().orEmpty()
        if (raw.isBlank()) error("模型没有返回可用的人设")

        val draft = runCatching {
            json.decodeFromString(PersonaDraft.serializer(), extractJsonObject(raw))
        }.getOrElse { cause ->
            throw IllegalStateException("AI 返回的人设格式无法解析，请再试一次", cause)
        }

        return current.copy(
            id = PersonaProfile.DEFAULT_PERSONA_ID,
            name = draft.name.ifBlank { current.name.ifBlank { "默认角色" } },
            identity = draft.identity.ifBlank { current.identity },
            background = draft.background.ifBlank { current.background },
            personality = draft.personality.ifBlank { current.personality },
            speechStyle = draft.speechStyle.ifBlank { current.speechStyle },
            relationship = draft.relationship.ifBlank { current.relationship },
            worldSetting = draft.worldSetting.ifBlank { current.worldSetting },
            franchise = draft.franchise.ifBlank { current.franchise },
            timelinePosition = draft.timelinePosition.ifBlank { current.timelinePosition },
            coreMotivations = draft.coreMotivations.ifEmpty { current.coreMotivations },
            valuePriorities = draft.valuePriorities.ifEmpty { current.valuePriorities },
            behaviorPatterns = draft.behaviorPatterns.ifEmpty { current.behaviorPatterns },
            internalContradictions = draft.internalContradictions.ifEmpty { current.internalContradictions },
            knowledgeBoundary = draft.knowledgeBoundary.ifEmpty { current.knowledgeBoundary },
            loreEntries = draft.loreEntries.ifEmpty { current.loreEntries },
            hardConstraints = draft.hardConstraints.ifEmpty { current.hardConstraints },
            exampleDialogues = draft.exampleDialogues.ifEmpty { current.exampleDialogues },
            bannedPhrases = draft.bannedPhrases.ifEmpty { current.bannedPhrases },
            signaturePhrases = draft.signaturePhrases.ifEmpty { current.signaturePhrases },
        )
    }

    private fun extractJsonObject(raw: String): String {
        val unfenced = raw
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        require(start >= 0 && end > start) { "missing JSON object" }
        return unfenced.substring(start, end + 1)
    }

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 12
        const val MAX_MESSAGE_CHARS = 1_200
        const val MAX_DESCRIPTION_CHARS = 4_000

        val SYSTEM_PROMPT = """
            你负责把角色扮演需求整理成结构化角色卡，供聊天模式每轮固定注入。

            只输出一个 JSON 对象，不要 Markdown，不要解释。必须包含这些字段：
            name, identity, background, personality, speechStyle, relationship, worldSetting,
            franchise, timelinePosition, coreMotivations, valuePriorities, behaviorPatterns,
            internalContradictions, knowledgeBoundary, loreEntries,
            hardConstraints, exampleDialogues, bannedPhrases, signaturePhrases。

            规则：
            1. 根据用户补充描述、最近聊天和当前已填写内容综合整理；已经明确的设定优先保留。
            2. 缺少的信息可以做低风险、符合角色气质的补全，但不要篡改用户明确设定。
            3. identity 要同时抓住身份定位、外在辨识度和关键能力；background 主要写经历与处境。
            4. personality 要包含性格驱动力、克制点、软肋和情绪边界；speechStyle 要写成可执行的说话规则。
            5. relationship 要明确角色如何看待用户、亲疏边界、称呼习惯和互动倾向。
            6. coreMotivations 写角色长期驱动力；valuePriorities 写发生冲突时的价值排序；behaviorPatterns 写可重复观察到的行动方式；internalContradictions 写真实存在的内在拉扯。
            7. knowledgeBoundary 明确角色知道/不知道什么，尤其避免观众上帝视角；timelinePosition 说明采用哪个剧情阶段。
            8. loreEntries 只拆稳定世界资料，每项结构为 {"id":"","title":"","content":"","keywords":[],"secondaryKeywords":[],"priority":50,"alwaysOn":false,"spoilerLevel":0}。普通人物建议 3-12 条，不要把整份人设重复塞进去；默认只生成 spoilerLevel=0 的无剧透条目。
            9. hardConstraints 写 4-10 条真正会影响扮演稳定性的硬规则，重要禁忌也放在这里。
            10. exampleDialogues 写 3-6 条短对白，体现语气、节奏和潜台词，不写长段剧情。
            11. bannedPhrases 只放会明显破坏角色感或产生机械 AI 味的表达。
            12. signaturePhrases 放少量自然常用表达，避免每句话都重复口头禅。
            13. “默认角色”只是占位名；只要描述或聊天里出现明确角色名，就应替换成真实角色名。
            14. 除 loreEntries 外的数组字段必须是 JSON 字符串数组；没有合适内容时返回空数组。
            15. 输出必须是可直接解析的标准 JSON。
        """.trimIndent()
    }
}
