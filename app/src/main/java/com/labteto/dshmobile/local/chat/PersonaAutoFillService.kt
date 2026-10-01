package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val modelGateway: LocalModelGateway,
    private val usageTracker: DeepSeekUsageTracker,
    private val json: Json,
) {
    suspend fun generate(
        model: String,
        baseUrl: String,
        profileId: String? = null,
        current: PersonaProfile,
        recentMessages: List<LocalHarnessMessage>,
        description: String,
    ): PersonaProfile {
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

        return modelGateway.withFrozenRoute(profileId, model, baseUrl) {
            val reply = modelGateway.complete(
                baseUrl = baseUrl,
                model = model,
                messages = messages,
                tools = JsonArray(emptyList()),
                temperature = 0.2,
            )
            withContext(Dispatchers.IO) {
            usageTracker.record(
                model = model,
                usage = reply.usage,
                requestId = reply.requestId,
                context = TokenUsageContext(mode = LocalUsageMode.CHAT, action = TokenUsageAction.PERSONA_AUTOFILL),
                promptBreakdown = reply.promptBreakdown,
                route = reply.routeIdentity,
            )
        }

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
            val repairedReply = try {
                modelGateway.complete(
                    baseUrl = baseUrl,
                    model = model,
                    messages = repairMessages,
                    tools = JsonArray(emptyList()),
                    temperature = 0.0,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (repairError: Throwable) {
                throw IllegalStateException(
                    "AI 返回的人设格式无法解析，请再试一次",
                    repairError,
                ).also { it.addSuppressed(firstCause) }
            }
            withContext(Dispatchers.IO) {
                usageTracker.record(
                    model = model,
                    usage = repairedReply.usage,
                    requestId = repairedReply.requestId,
                    context = TokenUsageContext(mode = LocalUsageMode.CHAT, action = TokenUsageAction.PERSONA_AUTOFILL),
                    promptBreakdown = repairedReply.promptBreakdown,
                    route = repairedReply.routeIdentity,
                )
            }

            val repairedRaw = repairedReply.content?.trim().orEmpty()
            if (repairedRaw.isBlank()) {
                throw IllegalStateException("AI 返回的人设格式无法解析，请再试一次", firstCause)
            }

            runCatching { parsePersonaDraft(json, repairedRaw) }.getOrElse { repairedCause ->
                repairedCause.addSuppressed(firstCause)
                throw IllegalStateException("AI 返回的人设格式无法解析，请再试一次", repairedCause)
            }
        }

        current.copy(
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
    }

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 12
        const val MAX_MESSAGE_CHARS = 1_200
        const val MAX_DESCRIPTION_CHARS = 4_000
        const val MAX_REPAIR_CHARS = 24_000

        val SYSTEM_PROMPT = """
            你负责把角色需求整理成结构化角色卡，只输出可解析 JSON，不解释。

            字段：name, identity, background, personality, speechStyle, relationship,
            worldSetting, franchise, timelinePosition, coreMotivations, valuePriorities,
            behaviorPatterns, internalContradictions, knowledgeBoundary, loreEntries,
            hardConstraints, exampleDialogues, bannedPhrases, signaturePhrases。

            规则：
            1. 用户明确设定和已有字段优先；name 必填，未知信息可省略，只做低风险补全。
            2. identity/background 写身份与经历；personality/speechStyle 写稳定性格与可执行表达；relationship 写与用户的长期互动边界。
            3. coreMotivations/valuePriorities/behaviorPatterns/internalContradictions 写长期驱动、价值排序、稳定行为和内在拉扯；knowledgeBoundary/timelinePosition 明确认知范围与剧情阶段。
            4. loreEntries 仅保存稳定世界资料，每项为 {"id":"","title":"","content":"","keywords":[],"secondaryKeywords":[],"priority":50,"alwaysOn":false,"spoilerLevel":0}，优先0～6条；hardConstraints 3～8条，exampleDialogues 2～4条；禁用词和惯用语保持少量。
            5. 出现明确角色名时替换占位名；数组使用 JSON 数组。
            6. 优先保证 JSON 完整；单文本尽量≤500字，数组≤8项。
        """.trimIndent()

        val REPAIR_PROMPT = """
            修复下方角色卡 JSON。只输出可解析 JSON，不解释、不新增事实。
            保留可可靠恢复的字段和值；规范数组；截断内容只保留确定部分并闭合结构。
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
