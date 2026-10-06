package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PersonaConflictFinding(
    val field: String = "",
    val fixedValue: String = "",
    val observedValue: String = "",
    val reason: String = "",
)

@Serializable
data class PersonaAppendSuggestion(
    val field: String = "",
    val value: String = "",
    val evidence: String = "",
)

@Serializable
data class PersonaInspectionResult(
    val conflicts: List<PersonaConflictFinding> = emptyList(),
    val suggestions: List<PersonaAppendSuggestion> = emptyList(),
)

/**
 * Reviews a saved character against real dialogue. It never mutates a persona by itself:
 * conflicts are surfaced for the user, while additions must be explicitly selected in the UI.
 */
@Singleton
class PersonaInspectionService @Inject constructor(
    private val modelGateway: LocalModelGateway,
    private val usageTracker: DeepSeekUsageTracker,
    private val json: Json,
) {
    suspend fun inspect(
        model: String,
        baseUrl: String,
        profileId: String? = null,
        persona: PersonaProfile,
        messages: List<LocalHarnessMessage>,
    ): PersonaInspectionResult {
        val dialogue = messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(MAX_CONTEXT_MESSAGES)
            .joinToString("\n") { message ->
                "${if (message.role == "user") "用户" else persona.name}：${message.content.trim().take(MAX_MESSAGE_CHARS)}"
            }
            .trim()
        if (dialogue.isBlank()) {
            return PersonaInspectionResult()
        }

        val prompt = buildString {
            appendLine("【固定人物生命资料】")
            appendLine("人物名称：${persona.name}")
            appendField("人物整体", persona.portrait)
            appendField("独立生活", persona.lifeContext)
            appendList("天然注意", persona.attentionBiases)
            appendList("容易漏掉/误读", persona.perceptionBlindSpots)
            appendList("小习惯/小坚持", persona.quirks)
            appendList("不擅长", persona.limitations)
            appendList("真正重要", persona.coreValues)
            appendField("长期内在拉扯", persona.coreTension)
            appendList("稳定部分", persona.stableTraits)
            appendList("可缓慢变化", persona.mutableTraits)
            appendField("对用户初始印象", persona.initialUserImpression)
            appendList("声音样本", persona.voiceSamples)
            appendField("世界设定", persona.worldSetting)
            appendField("作品来源", persona.franchise)
            appendField("当前时间线", persona.timelinePosition)
            appendList("知识边界", persona.knowledgeBoundary)
            appendList("硬约束", persona.hardConstraints)
            appendList("禁用表达", persona.bannedPhrases)
            appendList("既有纠正", persona.corrections)
            appendLine()
            appendLine("【真实对话】")
            append(dialogue)
        }

        return modelGateway.withFrozenRoute(profileId, model, baseUrl) {
            val reply = modelGateway.complete(
                baseUrl = baseUrl,
                model = model,
            messages = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                },
                buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                },
            ),
                tools = JsonArray(emptyList()),
            )
            withContext(Dispatchers.IO) {
            usageTracker.record(
                model = model,
                usage = reply.usage,
                requestId = reply.requestId,
                context = TokenUsageContext(mode = LocalUsageMode.CHAT, action = TokenUsageAction.PERSONA_INSPECTION),
                promptBreakdown = reply.promptBreakdown,
                route = reply.routeIdentity,
            )
        }
        val raw = reply.content?.trim().orEmpty()
        if (raw.isBlank()) error("人物检查没有返回结果")

        val decoded = runCatching {
            json.decodeFromString(PersonaInspectionResult.serializer(), extractJsonObject(raw))
        }.getOrElse { cause ->
            throw IllegalStateException("人物检查结果格式无法解析，请再试一次", cause)
        }
            sanitize(decoded)
        }
    }

    private fun sanitize(result: PersonaInspectionResult): PersonaInspectionResult {
        val conflicts = result.conflicts.asSequence()
            .map {
                it.copy(
                    field = it.field.trim(),
                    fixedValue = it.fixedValue.trim().take(800),
                    observedValue = it.observedValue.trim().take(800),
                    reason = it.reason.trim().take(500),
                )
            }
            .filter { it.field in ALLOWED_FIELDS && it.observedValue.isNotBlank() }
            .distinctBy { "${it.field}|${normalize(it.observedValue)}" }
            .take(MAX_CONFLICTS)
            .toList()
        val suggestions = result.suggestions.asSequence()
            .map {
                it.copy(
                    field = it.field.trim(),
                    value = it.value.trim().take(1_000),
                    evidence = it.evidence.trim().take(500),
                )
            }
            .filter { it.field in ALLOWED_FIELDS && it.value.isNotBlank() }
            .filterNot { PersonaImmersionPolicy.breaksImmersion(it.value) }
            .distinctBy { "${it.field}|${normalize(it.value)}" }
            .take(MAX_SUGGESTIONS)
            .toList()
        return PersonaInspectionResult(conflicts = conflicts, suggestions = suggestions)
    }

    private fun StringBuilder.appendField(label: String, value: String) {
        if (value.isNotBlank()) appendLine("$label：${value.trim()}")
    }

    private fun StringBuilder.appendList(label: String, values: List<String>) {
        if (values.isNotEmpty()) appendLine("$label：${values.joinToString("；")}")
    }

    private fun extractJsonObject(raw: String): String {
        val unfenced = raw
            .removePrefix("~~~json")
            .removePrefix("~~~JSON")
            .removePrefix("~~~")
            .removeSuffix("~~~")
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

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】—_-]+"""), "")

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 28
        const val MAX_MESSAGE_CHARS = 1_200
        const val MAX_CONFLICTS = 12
        const val MAX_SUGGESTIONS = 20

        val ALLOWED_FIELDS = setOf(
            "portrait",
            "lifeContext",
            "attentionBiases",
            "perceptionBlindSpots",
            "quirks",
            "limitations",
            "coreValues",
            "coreTension",
            "stableTraits",
            "mutableTraits",
            "initialUserImpression",
            "voiceSamples",
            "worldSetting",
            "franchise",
            "timelinePosition",
            "knowledgeBoundary",
            "hardConstraints",
            "bannedPhrases",
            "corrections",
        )

        val SYSTEM_PROMPT = """
            审计人物长期一致性和“活人感”，只整理证据，不修改人物。只输出标准 JSON：
            {"conflicts":[{"field":"","fixedValue":"","observedValue":"","reason":""}],
             "suggestions":[{"field":"","value":"","evidence":""}]}

            规则：
            1. conflicts 只报有明确多轮证据的稳定冲突；单次玩笑、临时情绪、梦境、假设和用户猜测不算。
            2. 同时检查：知识越界、关系突变、过度迎合、把推测当事实、人物成长过快，以及长期选择与稳定部分冲突。
            3. 特别识别“设定表演”：频繁主动展示自身特点、重复固定句式/口头禅、所有回复都过度完整有用、人物生活完全围着用户、对用户理解得异常准确、连续多轮使用同一种互动节拍。此类问题有明确证据时写 conflicts，reason 说明具体表现。
            4. suggestions 只提取明确、稳定、长期有用且人物资料未包含的新信息；同义项去重，一条只写一个事实。不要从模型自己的表演反推一个新的固定人设。
            5. 用户明确纠正优先写入 corrections；不自动生成 loreEntries。
            6. field 只能使用 portrait, lifeContext, attentionBiases, perceptionBlindSpots, quirks,
               limitations, coreValues, coreTension, stableTraits, mutableTraits,
               initialUserImpression, voiceSamples, worldSetting, franchise, timelinePosition,
               knowledgeBoundary, hardConstraints, bannedPhrases, corrections；无结果返回空数组。
            7. 对话里如果出现“作为AI/语言模型/虚拟助手”“没有身体或实体”“无法进行生理行为或实体动作”
               “没有感情/无法理解或体验情感”“只能通过文字交流”等模型或平台自我限制，只能视为脱离角色的表现；
               禁止把它们提炼成 suggestions、hardConstraints、limitations 或任何新的固定人设。
        """.trimIndent()
    }
}
