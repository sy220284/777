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
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
    private val usageTracker: DeepSeekUsageTracker,
    private val json: Json,
) {
    suspend fun inspect(
        model: String,
        baseUrl: String,
        persona: PersonaProfile,
        messages: List<LocalHarnessMessage>,
    ): PersonaInspectionResult {
        val apiKey = apiKeys.get()?.trim()?.takeIf(String::isNotEmpty)
            ?: error("请先在模型设置里配置密钥")
        val dialogue = messages
            .asSequence()
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
            appendList("硬约束", persona.hardConstraints)
            appendList("对白参考", persona.exampleDialogues)
            appendList("禁用表达", persona.bannedPhrases)
            appendList("常用表达", persona.signaturePhrases)
            appendList("既有纠正", persona.corrections)
            appendLine()
            appendLine("【真实对话】")
            append(dialogue)
        }

        val reply = modelClient.complete(
            apiKey = apiKey,
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
        usageTracker.record(model, reply.usage)
        val raw = reply.content?.trim().orEmpty()
        if (raw.isBlank()) error("人物检查没有返回结果")

        val decoded = runCatching {
            json.decodeFromString(PersonaInspectionResult.serializer(), extractJsonObject(raw))
        }.getOrElse { cause ->
            throw IllegalStateException("人物检查结果格式无法解析，请再试一次", cause)
        }
        return sanitize(decoded)
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
            .removePrefix("``json")
            .removePrefix("``JSON")
            .removePrefix("``")
            .removeSuffix("``")
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
            "hardConstraints",
            "exampleDialogues",
            "bannedPhrases",
            "signaturePhrases",
            "corrections",
        )

        val SYSTEM_PROMPT = """
            你负责审计角色扮演的人物一致性。只做证据整理，不替用户修改人设。

            只输出一个 JSON 对象，结构必须严格为：
            {
              "conflicts":[
                {"field":"personality","fixedValue":"固定设定","observedValue":"对话中明确表现","reason":"为什么构成冲突"}
              ],
              "suggestions":[
                {"field":"background","value":"可追加到固定人设的新内容","evidence":"对应的对话证据"}
              ]
            }

            规则：
            1. conflicts 只记录真实矛盾：对话中稳定、明确地违背固定人设。一次玩笑、临时情绪、假设、梦境、用户猜测不算冲突。
            2. suggestions 只提取对话中已经明确、以后仍稳定有用、固定人设里尚未包含的内容。禁止为了补全而猜测。
            3. 同义内容不要重复建议；固定人设已有更完整版本时不要给较短版本。
            4. 用户明确纠正角色“不会这么说/不会这么做”等内容优先放 corrections。
            5. 人物身份、经历、能力等事实分别放 identity/background；稳定性格放 personality；可执行说话习惯放 speechStyle。
            6. 与用户长期关系定位、称呼习惯和互动边界放 relationship；世界规则放 worldSetting。
            7. 一条建议只写一个可直接追加的小事实或规则，不要把整份人设重新生成一遍。
            8. field 只能使用 identity, background, personality, speechStyle, relationship, worldSetting,
               hardConstraints, exampleDialogues, bannedPhrases, signaturePhrases, corrections。
            9. 没有冲突或新增内容时返回空数组。
            10. 不要 Markdown，不要解释，只输出标准 JSON。
        """.trimIndent()
    }
}
