package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Singleton
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
            appendLine("【固定人设】")
            appendLine("角色名称：${persona.name}")
            appendField("人物身份", persona.identity)
            appendField("背景经历", persona.background)
            appendField("核心性格", persona.personality)
            appendField("说话方式", persona.speechStyle)
            appendField("与用户关系", persona.relationship)
            appendField("世界设定", persona.worldSetting)
            appendField("作品来源", persona.franchise)
            appendField("当前时间线", persona.timelinePosition)
            appendList("核心动机", persona.coreMotivations)
            appendList("价值排序", persona.valuePriorities)
            appendList("稳定行为模式", persona.behaviorPatterns)
            appendList("内在矛盾", persona.internalContradictions)
            appendList("知识边界", persona.knowledgeBoundary)
            appendList("硬约束", persona.hardConstraints)
            appendList("对白参考", persona.exampleDialogues)
            appendList("禁用表达", persona.bannedPhrases)
            appendList("常用表达", persona.signaturePhrases)
            appendList("既有纠正", persona.corrections)
            appendLine()
            appendLine("【真实对话】")
            append(dialogue)
        }

        return modelGateway.withFrozenRoute(model, baseUrl) {
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
            "identity",
            "background",
            "personality",
            "speechStyle",
            "relationship",
            "worldSetting",
            "franchise",
            "timelinePosition",
            "coreMotivations",
            "valuePriorities",
            "behaviorPatterns",
            "internalContradictions",
            "knowledgeBoundary",
            "hardConstraints",
            "exampleDialogues",
            "bannedPhrases",
            "signaturePhrases",
            "corrections",
        )

        val SYSTEM_PROMPT = """
            审计角色一致性，只整理证据，不修改人设。只输出标准 JSON：
            {"conflicts":[{"field":"","fixedValue":"","observedValue":"","reason":""}],
             "suggestions":[{"field":"","value":"","evidence":""}]}

            规则：
            1. conflicts 只报有明确对话证据的稳定冲突；玩笑、临时情绪、假设、梦境和用户猜测不算。
            2. 同时检查知识越界、关系突变、过度迎合、推测当事实和长期表达漂移。
            3. suggestions 只提取明确、稳定、长期有用且人设未包含的新信息；同义项去重，一条只写一个事实。
            4. 用户明确纠正优先写入 corrections；不自动生成 loreEntries。
            5. field 只能使用 identity, background, personality, speechStyle, relationship, worldSetting,
               franchise, timelinePosition, coreMotivations, valuePriorities, behaviorPatterns,
               internalContradictions, knowledgeBoundary, hardConstraints, exampleDialogues,
               bannedPhrases, signaturePhrases, corrections；无结果返回空数组。
        """.trimIndent()
    }
}
