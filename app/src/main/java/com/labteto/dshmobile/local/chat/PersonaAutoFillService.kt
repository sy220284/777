package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.DeepSeekClient
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class PersonaDraft(
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

internal fun parsePersonaDraft(json: Json, raw: String): PersonaDraft {
    val objectText = stripTrailingJsonCommas(extractFirstJsonObject(raw))
    val root = json.parseToJsonElement(objectText) as? JsonObject
        ?: error("persona response root is not a JSON object")

    return PersonaDraft(
        name = root.text("name"),
        identity = root.text("identity"),
        background = root.text("background"),
        personality = root.text("personality"),
        speechStyle = root.text("speechStyle"),
        relationship = root.text("relationship"),
        worldSetting = root.text("worldSetting"),
        franchise = root.text("franchise"),
        timelinePosition = root.text("timelinePosition"),
        coreMotivations = root.stringList("coreMotivations"),
        valuePriorities = root.stringList("valuePriorities"),
        behaviorPatterns = root.stringList("behaviorPatterns"),
        internalContradictions = root.stringList("internalContradictions"),
        knowledgeBoundary = root.stringList("knowledgeBoundary"),
        loreEntries = root.loreEntries(),
        hardConstraints = root.stringList("hardConstraints"),
        exampleDialogues = root.stringList("exampleDialogues"),
        bannedPhrases = root.stringList("bannedPhrases"),
        signaturePhrases = root.stringList("signaturePhrases"),
    )
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        .orEmpty()
        .take(MAX_SCALAR_CHARS)

private fun JsonObject.stringList(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    val values = when (value) {
        is JsonArray -> value.mapNotNull { item ->
            (item as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        is JsonPrimitive -> splitLooseList(value.contentOrNull.orEmpty())
        else -> emptyList()
    }
    return values
        .map { it.take(MAX_LIST_ITEM_CHARS) }
        .distinct()
        .take(MAX_LIST_ITEMS)
}

private fun splitLooseList(value: String): List<String> {
    val clean = value.trim()
    if (clean.isBlank() || clean == "[]") return emptyList()
    return clean
        .split(Regex("""[\r\n；;]+"""))
        .map(String::trim)
        .filter(String::isNotBlank)
}

private fun JsonObject.loreEntries(): List<PersonaLoreEntry> {
    val source = this["loreEntries"] ?: return emptyList()
    val objects: List<JsonObject> = when (source) {
        is JsonArray -> source.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(source)
        else -> emptyList()
    }

    return objects.mapNotNull { entry ->
        val title = entry.text("title")
        val content = entry.text("content")
        if (title.isBlank() && content.isBlank()) return@mapNotNull null

        PersonaLoreEntry(
            id = entry.text("id"),
            title = title.take(MAX_LORE_TITLE_CHARS),
            content = content.take(MAX_LORE_CONTENT_CHARS),
            keywords = entry.stringList("keywords").take(MAX_LORE_KEYWORDS),
            secondaryKeywords = entry.stringList("secondaryKeywords").take(MAX_LORE_KEYWORDS),
            priority = entry.intValue("priority", 50).coerceIn(0, 100),
            alwaysOn = entry.booleanValue("alwaysOn", false),
            spoilerLevel = entry.intValue("spoilerLevel", 0).coerceAtLeast(0),
        )
    }.take(MAX_LORE_ENTRIES)
}

private fun JsonObject.intValue(name: String, default: Int): Int =
    (this[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.toIntOrNull()
        ?: default

private fun JsonObject.booleanValue(name: String, default: Boolean): Boolean {
    val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return default
    return when (value) {
        "true", "1", "yes", "y", "是" -> true
        "false", "0", "no", "n", "否" -> false
        else -> default
    }
}

private fun extractFirstJsonObject(raw: String): String {
    var start = -1
    var depth = 0
    var inString = false
    var escaped = false

    raw.forEachIndexed { index, char ->
        if (inString) {
            if (escaped) {
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            }
            return@forEachIndexed
        }

        when (char) {
            '"' -> if (start >= 0) inString = true
            '{' -> {
                if (depth == 0) start = index
                depth += 1
            }
            '}' -> if (depth > 0) {
                depth -= 1
                if (depth == 0 && start >= 0) {
                    return raw.substring(start, index + 1)
                }
            }
        }
    }

    if (start < 0) error("missing JSON object")
    error("unterminated JSON object")
}

private fun stripTrailingJsonCommas(raw: String): String {
    val output = StringBuilder(raw.length)
    var inString = false
    var escaped = false
    var index = 0

    while (index < raw.length) {
        val char = raw[index]
        if (inString) {
            output.append(char)
            if (escaped) {
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            }
            index += 1
            continue
        }

        if (char == '"') {
            inString = true
            output.append(char)
            index += 1
            continue
        }

        if (char == ',') {
            var next = index + 1
            while (next < raw.length && raw[next].isWhitespace()) next += 1
            if (next < raw.length && (raw[next] == '}' || raw[next] == ']')) {
                index += 1
                continue
            }
        }

        output.append(char)
        index += 1
    }

    return output.toString()
}

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
            temperature = 0.2,
        )
        usageTracker.record(model, reply.usage)

        val raw = reply.content?.trim().orEmpty()
        if (raw.isBlank()) error("模型没有返回可用的人设")

        val draft = runCatching { parsePersonaDraft(json, raw) }.getOrElse { firstCause ->
            val repairMessages = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", REPAIR_PROMPT)
                },
                buildJsonObject {
                    put("role", "user")
                    put("content", raw.take(MAX_REPAIR_CHARS))
                },
            )
            val repairedReply = runCatching {
                modelClient.complete(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    messages = repairMessages,
                    tools = JsonArray(emptyList()),
                    temperature = 0.0,
                )
            }.getOrElse {
                throw IllegalStateException("AI 返回的人设格式无法解析，请再试一次", firstCause)
            }
            usageTracker.record(model, repairedReply.usage)

            val repairedRaw = repairedReply.content?.trim().orEmpty()
            if (repairedRaw.isBlank()) {
                throw IllegalStateException("AI 返回的人设格式无法解析，请再试一次", firstCause)
            }

            runCatching { parsePersonaDraft(json, repairedRaw) }.getOrElse { repairedCause ->
                repairedCause.addSuppressed(firstCause)
                throw IllegalStateException("AI 返回的人设格式无法解析，请再试一次", repairedCause)
            }
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

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 12
        const val MAX_MESSAGE_CHARS = 1_200
        const val MAX_DESCRIPTION_CHARS = 4_000
        const val MAX_REPAIR_CHARS = 24_000

        val SYSTEM_PROMPT = """
            你负责把角色扮演需求整理成结构化角色卡，供聊天模式每轮固定注入。

            只输出一个可直接解析的标准 JSON 对象，不要 Markdown，不要解释。
            推荐字段如下：name, identity, background, personality, speechStyle, relationship,
            worldSetting, franchise, timelinePosition, coreMotivations, valuePriorities,
            behaviorPatterns, internalContradictions, knowledgeBoundary, loreEntries,
            hardConstraints, exampleDialogues, bannedPhrases, signaturePhrases。

            规则：
            1. 根据用户补充描述、最近聊天和当前已填写内容综合整理；已经明确的设定优先保留。
            2. name 必须给出；其他字段确实无法可靠判断时可以省略，禁止为了凑字段胡乱扩写。
            3. 缺少的信息可以做低风险、符合角色气质的补全，但不要篡改用户明确设定。
            4. identity 抓住身份定位、外在辨识度和关键能力；background 主要写经历与处境。
            5. personality 写性格驱动力、克制点、软肋和情绪边界；speechStyle 写成可执行的说话规则。
            6. relationship 明确角色如何看待用户、亲疏边界、称呼习惯和互动倾向。
            7. coreMotivations 写长期驱动力；valuePriorities 写发生冲突时的价值排序；behaviorPatterns 写稳定行动方式；internalContradictions 写真实存在的内在拉扯。
            8. knowledgeBoundary 明确角色知道或不知道什么，避免观众上帝视角；timelinePosition 说明采用哪个剧情阶段。
            9. loreEntries 只拆稳定世界资料，每项结构为 {"id":"","title":"","content":"","keywords":[],"secondaryKeywords":[],"priority":50,"alwaysOn":false,"spoilerLevel":0}。优先 0-6 条，只写真正有长期价值的资料。
            10. hardConstraints 写 3-8 条真正影响扮演稳定性的硬规则；exampleDialogues 写 2-4 条短对白。
            11. bannedPhrases 只放会明显破坏角色感或产生机械感的表达；signaturePhrases 只放少量自然常用表达。
            12. “默认角色”只是占位名；只要描述或聊天里出现明确角色名，就应替换成真实角色名。
            13. 数组字段优先输出 JSON 字符串数组；没有合适内容时可省略或返回空数组。
            14. 控制篇幅，优先保证 JSON 完整闭合。单个文本字段尽量不超过 500 字，单个数组尽量不超过 8 项。
        """.trimIndent()

        val REPAIR_PROMPT = """
            你只负责修复下面这段角色卡输出的 JSON 格式。
            只返回一个完整、可解析的 JSON 对象，不要 Markdown，不要解释，不要新增人物事实。
            保留原有字段和值；若某个数组字段被写成单个字符串，可以保留字符串或改成字符串数组。
            若内容在末尾被截断，只保留已经完整出现、能可靠恢复的字段，确保最终 JSON 正常闭合。
        """.trimIndent()
    }
}

private const val MAX_SCALAR_CHARS = 4_000
private const val MAX_LIST_ITEM_CHARS = 1_200
private const val MAX_LIST_ITEMS = 32
private const val MAX_LORE_ENTRIES = 12
private const val MAX_LORE_TITLE_CHARS = 160
private const val MAX_LORE_CONTENT_CHARS = 2_000
private const val MAX_LORE_KEYWORDS = 16
