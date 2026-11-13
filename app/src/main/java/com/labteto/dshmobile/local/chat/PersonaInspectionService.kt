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
/**
 * All already visited stages are available to inspection, just as they are to live replies.
 * Story stage labels are opaque; only the saved visit path grants additional knowledge.
 */
internal fun personaInspectionFactLines(
    persona: PersonaProfile,
    storyStage: String,
    unlockedStages: Collection<String>,
): List<String> = persona.visibleFacts(storyStage, unlockedStages)
    .filter { it.content.isNotBlank() }
    .map { fact -> "${fact.category}（${fact.provenance}；${fact.perspective}）：${fact.content.trim()}" }

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
        storyStage: String = "",
        unlockedStages: Collection<String> = emptyList(),
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
            appendLine("【权威人物档案 V4】")
            appendLine("name：${persona.name}")
            appendField("coreIdentity", persona.coreIdentity)
            appendField("franchise", persona.franchise)
            appendLine("当前故事阶段：${storyStage.ifBlank { "未指定" }}")
            personaInspectionFactLines(persona, storyStage, unlockedStages).forEach(::appendLine)
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
        com.labteto.dshmobile.local.model.requireCompletedModelReply(reply)
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
            .filter { it.field in SUGGESTION_FIELDS && it.value.isNotBlank() }
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

        val ALLOWED_FIELDS = CharacterFactCategories.canonical.toSet() + "coreIdentity"
        val SUGGESTION_FIELDS = CharacterFactCategories.canonical.toSet() + "coreIdentity"

        val SYSTEM_PROMPT = """
            核查V4人物事实与最近的对话是否存在长期一致性问题。只输出JSON：
            {"conflicts":[{"field":"","fixedValue":"","observedValue":"","reason":""}],
             "suggestions":[{"field":"","value":"","evidence":""}]}
            field仅允许coreIdentity及以下资料类别：
            personality,selfNarrative,identityGap,valuesAndTradeoffs,subjectiveBeliefs,biography,
            relationships,preferencesAndHabits,voiceStyle,customFacts。
            只能根据稳定且明确的证据提出事实补充。原作身份和能力要区分可靠事实与推断。
            当前情绪、互动频率、关系进度、临时关注和未来成长属于运行时；
            不可写入人物卡，也不应将模型限制描述成人物设定。
            conflicts 仅记录人物档案中的具体事实与已明确确认的新证据正面冲突的情形，fixedValue 为原档案值，observedValue 为实际表达，不凭临时情绪改写稳定性格。
            suggestions 仅补充尚未记载、长期有效且有原文 evidence 的人物事实；区分角色的主观看法与外界客观事实。
            玩笑、猜测、梦境、否定语境与故事阶段不同均不足以认定冲突；没有可靠证据返回空数组。
            只给用户审核建议，不自动改人物资料。
        """.trimIndent()
    }
}
