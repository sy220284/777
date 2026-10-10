package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class PersonaDraft(
    val name: String = "",
    val portrait: String = "",
    val lifeContext: String = "",
    val attentionBiases: List<String> = emptyList(),
    val attentionKeywords: List<String> = emptyList(),
    val perceptionBlindSpots: List<String> = emptyList(),
    val quirks: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val coreValues: List<String> = emptyList(),
    val coreTension: String = "",
    val stableTraits: List<String> = emptyList(),
    val mutableTraits: List<String> = emptyList(),
    val initialUserImpression: String = "",
    val voiceSamples: List<String> = emptyList(),
    val worldSetting: String = "",
    val franchise: String = "",
    val timelinePosition: String = "",
    val knowledgeBoundary: List<String> = emptyList(),
    val loreEntries: List<PersonaLoreEntry> = emptyList(),
    val hardConstraints: List<String> = emptyList(),
    val bannedPhrases: List<String> = emptyList(),
)

/**
 * AI-assisted edits are incremental. Preserve existing canonical facts and explicit user edits;
 * generated lore can enrich a matching entry or append a new one, never erase unrelated entries.
 */
internal fun mergeGeneratedLoreEntries(
    previous: List<PersonaLoreEntry>,
    incoming: List<PersonaLoreEntry>,
): List<PersonaLoreEntry> {
    val merged = previous.toMutableList()
    incoming.filter { it.content.isNotBlank() }.forEach { next ->
        val key = next.title.trim().lowercase().replace(Regex("\\s+"), "")
        val index = merged.indexOfFirst { old ->
            (next.id.isNotBlank() && next.id == old.id) ||
                (key.isNotBlank() && old.title.trim().lowercase().replace(Regex("\\s+"), "") == key)
        }
        if (index >= 0) {
            val old = merged[index]
            merged[index] = old.copy(
                title = next.title.ifBlank { old.title },
                content = old.content.ifBlank { next.content },
                keywords = (old.keywords + next.keywords).distinct(),
                secondaryKeywords = (old.secondaryKeywords + next.secondaryKeywords).distinct(),
                priority = if (next.priority == 50) old.priority else next.priority,
                alwaysOn = old.alwaysOn || next.alwaysOn,
                spoilerLevel = maxOf(old.spoilerLevel, next.spoilerLevel),
            )
        } else {
            merged += next.copy(
                id = next.id.ifBlank {
                    "ai-lore-${(next.title.ifBlank { next.content.take(80) }).hashCode().toUInt().toString(16)}"
                },
            )
        }
    }
    return merged.take(80)
}

internal fun parsePersonaDraft(json: Json, raw: String): PersonaDraft {
    val objectText = stripTrailingJsonCommas(extractFirstJsonObject(raw))
    val root = json.parseToJsonElement(objectText) as? JsonObject
        ?: error("persona response root is not a JSON object")

    return PersonaDraft(
        name = root.text("name"),
        portrait = root.text("portrait"),
        lifeContext = root.text("lifeContext"),
        attentionBiases = root.stringList("attentionBiases"),
        attentionKeywords = root.stringList("attentionKeywords"),
        perceptionBlindSpots = root.stringList("perceptionBlindSpots"),
        quirks = root.stringList("quirks"),
        limitations = root.stringList("limitations"),
        coreValues = root.stringList("coreValues"),
        coreTension = root.text("coreTension"),
        stableTraits = root.stringList("stableTraits"),
        mutableTraits = root.stringList("mutableTraits"),
        initialUserImpression = root.text("initialUserImpression"),
        voiceSamples = root.stringList("voiceSamples"),
        worldSetting = root.text("worldSetting"),
        franchise = root.text("franchise"),
        timelinePosition = root.text("timelinePosition"),
        knowledgeBoundary = root.stringList("knowledgeBoundary"),
        loreEntries = root.loreEntries(),
        hardConstraints = root.stringList("hardConstraints"),
        bannedPhrases = root.stringList("bannedPhrases"),
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
 * through the ChatFeature persona/session API so the normal path remains
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
            appendLine("当前人物生命资料中已填写的内容：")
            appendLine("名称：${current.name}")
            if (current.portrait.isNotBlank()) appendLine("人物整体：${current.portrait}")
            if (current.lifeContext.isNotBlank()) appendLine("独立生活：${current.lifeContext}")
            if (current.attentionBiases.isNotEmpty()) appendLine("天然注意：${current.attentionBiases.joinToString("；")}")
            if (current.attentionKeywords.isNotEmpty()) appendLine("注意关键词：${current.attentionKeywords.joinToString("；")}")
            if (current.perceptionBlindSpots.isNotEmpty()) appendLine("容易漏掉/误读：${current.perceptionBlindSpots.joinToString("；")}")
            if (current.quirks.isNotEmpty()) appendLine("小习惯：${current.quirks.joinToString("；")}")
            if (current.limitations.isNotEmpty()) appendLine("不擅长：${current.limitations.joinToString("；")}")
            if (current.coreValues.isNotEmpty()) appendLine("真正重要：${current.coreValues.joinToString("；")}")
            if (current.coreTension.isNotBlank()) appendLine("长期拉扯：${current.coreTension}")
            if (current.stableTraits.isNotEmpty()) appendLine("稳定部分：${current.stableTraits.joinToString("；")}")
            if (current.mutableTraits.isNotEmpty()) appendLine("可缓慢变化：${current.mutableTraits.joinToString("；")}")
            if (current.initialUserImpression.isNotBlank()) appendLine("对用户初始印象：${current.initialUserImpression}")
            if (current.voiceSamples.isNotEmpty()) appendLine("自然声音样本：${current.voiceSamples.joinToString("；")}")
            if (current.worldSetting.isNotBlank()) appendLine("世界设定：${current.worldSetting}")
            if (current.franchise.isNotBlank()) appendLine("作品来源：${current.franchise}")
            if (current.timelinePosition.isNotBlank()) appendLine("时间线：${current.timelinePosition}")
            if (current.knowledgeBoundary.isNotEmpty()) appendLine("知识边界：${current.knowledgeBoundary.joinToString("；")}")
            if (current.hardConstraints.isNotEmpty()) appendLine("不可违反：${current.hardConstraints.joinToString("；")}")
            if (current.bannedPhrases.isNotEmpty()) appendLine("禁用表达：${current.bannedPhrases.joinToString("；")}")
            if (current.loreEntries.isNotEmpty()) {
                appendLine("已有原作世界书（请保留这些独立条目；只输出新增或实质修订的条目）：")
                current.loreEntries.take(16).forEach { entry ->
                    appendLine("编号=${entry.id}｜${entry.title}：${entry.content.take(240)}｜触发=${entry.keywords.take(8).joinToString("、")}")
                }
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

        var draftSource = raw
        var draft = runCatching { parsePersonaDraft(json, raw) }.getOrElse { firstCause ->
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
            }.also {
                draftSource = repairedRaw
            }
        }

        val immersionViolations = personaDraftImmersionViolations(draft)
        if (immersionViolations.isNotEmpty()) {
            val immersionRepairMessages = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", IMMERSION_REPAIR_PROMPT)
                },
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildString {
                            appendLine("检测到以下脱离角色的内容：")
                            immersionViolations.forEach { appendLine("- $it") }
                            appendLine()
                            appendLine("原始角色卡：")
                            append(draftSource.take(MAX_REPAIR_CHARS))
                        },
                    )
                },
            )
            val immersionReply = try {
                modelGateway.complete(
                    baseUrl = baseUrl,
                    model = model,
                    messages = immersionRepairMessages,
                    tools = JsonArray(emptyList()),
                    temperature = 0.0,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (repairError: Throwable) {
                throw IllegalStateException("AI 生成人设包含脱离角色的设定，且自动修复失败，请再试一次", repairError)
            }
            withContext(Dispatchers.IO) {
                usageTracker.record(
                    model = model,
                    usage = immersionReply.usage,
                    requestId = immersionReply.requestId,
                    context = TokenUsageContext(mode = LocalUsageMode.CHAT, action = TokenUsageAction.PERSONA_AUTOFILL),
                    promptBreakdown = immersionReply.promptBreakdown,
                    route = immersionReply.routeIdentity,
                )
            }

            val immersionRaw = immersionReply.content?.trim().orEmpty()
            if (immersionRaw.isBlank()) {
                throw IllegalStateException("AI 生成人设包含脱离角色的设定，且自动修复失败，请再试一次")
            }
            val repairedDraft = runCatching { parsePersonaDraft(json, immersionRaw) }.getOrElse { cause ->
                throw IllegalStateException("AI 生成人设包含脱离角色的设定，且自动修复结果无法解析，请再试一次", cause)
            }
            val remainingViolations = personaDraftImmersionViolations(repairedDraft)
            if (remainingViolations.isNotEmpty()) {
                throw IllegalStateException("AI 生成人设仍包含脱离角色的设定，已阻止写入人物资料，请再试一次")
            }
            draft = repairedDraft
        }

        current.copy(
            id = current.id,
            name = draft.name.ifBlank { current.name.ifBlank { "默认角色" } },
            portrait = draft.portrait.ifBlank { current.portrait },
            lifeContext = draft.lifeContext.ifBlank { current.lifeContext },
            attentionBiases = draft.attentionBiases.ifEmpty { current.attentionBiases },
            attentionKeywords = (current.attentionKeywords + draft.attentionKeywords).distinct(),
            perceptionBlindSpots = draft.perceptionBlindSpots.ifEmpty { current.perceptionBlindSpots },
            quirks = draft.quirks.ifEmpty { current.quirks },
            limitations = draft.limitations.ifEmpty { current.limitations },
            coreValues = draft.coreValues.ifEmpty { current.coreValues },
            coreTension = draft.coreTension.ifBlank { current.coreTension },
            stableTraits = draft.stableTraits.ifEmpty { current.stableTraits },
            mutableTraits = draft.mutableTraits.ifEmpty { current.mutableTraits },
            initialUserImpression = draft.initialUserImpression.ifBlank { current.initialUserImpression },
            voiceSamples = draft.voiceSamples.ifEmpty { current.voiceSamples },
            worldSetting = draft.worldSetting.ifBlank { current.worldSetting },
            franchise = draft.franchise.ifBlank { current.franchise },
            timelinePosition = draft.timelinePosition.ifBlank { current.timelinePosition },
            knowledgeBoundary = (current.knowledgeBoundary + draft.knowledgeBoundary).distinct(),
            loreEntries = mergeGeneratedLoreEntries(current.loreEntries, draft.loreEntries),
            hardConstraints = draft.hardConstraints.ifEmpty { current.hardConstraints },
            bannedPhrases = draft.bannedPhrases.ifEmpty { current.bannedPhrases },
        )
        }
    }

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 12
        const val MAX_MESSAGE_CHARS = 1_200
        const val MAX_DESCRIPTION_CHARS = 4_000
        const val MAX_REPAIR_CHARS = 24_000

        val SYSTEM_PROMPT = """
            你负责把角色需求整理成“人物生命资料”，只输出可解析 JSON，不解释。

            字段：name, portrait, lifeContext, attentionBiases, attentionKeywords, perceptionBlindSpots, quirks,
            limitations, coreValues, coreTension, stableTraits, mutableTraits,
            initialUserImpression, voiceSamples, worldSetting, franchise, timelinePosition,
            knowledgeBoundary, loreEntries, hardConstraints, bannedPhrases。

            规则：
            1. 用户明确设定和已有资料优先；name 必填。没有明确证据的字段保持空白，禁止为了“完整”自动补满人物。
            2. portrait 是人物身份与经历锚点，优先清楚说明身份、出身、职责、塑造人物的关键经历、重要关系及其影响。原创人物也要有具体处境；避免重复堆砌形容词。一般人物可写约200～450字，复杂人物按实际需要增加，不为凑字数编造。
            3. lifeContext 记录塑造人物的生活经历、长期责任、重要的人与关系，以及现实处境；避免将整部原作的事件流水账塞进生活字段。不要预编每天会发生什么。
            4. attentionBiases 最多2项、perceptionBlindSpots 最多1项；attentionKeywords 只列真正容易唤起人物独特反应的关键人物、关系或事件，不做完整百科目录。
            5. quirks 最多3项、limitations 最多2项；只保留有辨识度且长期成立的内容，普通人类行为不要都写成人设。
            6. coreValues 最多3项；coreTension 和 stableTraits 用于补足人物的价值取向、内在矛盾及长期选择逻辑，各最多2项；mutableTraits 可选，不重复 portrait 已写清的信息。
            7. initialUserImpression 只有用户明确给出初始关系或看法时才写；不得伪造共同经历。
            8. voiceSamples 只在原作台词风格或用户明确要求时补充最多3～5条自然表达样本；取说话节奏与性格气质，避免造作的固定口头禅、频率规则和回应模板。
            9. worldSetting 仅用约50～150字说明角色必须依赖的世界规则与环境，不复述整部作品。loreEntries 为可选的专属知识锚点，每项为 {"id":"","title":"","content":"","keywords":[],"secondaryKeywords":[],"priority":50,"alwaysOn":false,"spoilerLevel":0}。默认0～3条；只有模型容易混淆且确切影响角色身份、特殊能力、关系或剧情边界的事实才保留。已有世界书必须保留，不因精简建议删改。
            10. franchise、timelinePosition、knowledgeBoundary 必须互相一致；未到达的时间线与他人秘密不自动成为角色知识。hardConstraints 仅保留会影响人物一致性的少量硬约束。
            11. 不生成“思维模式、行为模式、表达模式”的固定标签或权重。运行时会根据人物底色、当前状态、关系、记忆和话题自由组合模式。
            12. 目标是完整且精炼地写明“这个人是谁、什么经历塑造了他、他为什么做出这样的选择”，将各轮具体思维、行动、表达交给运行时；人物常规原作知识可由模型已有知识及必要时联网核实补充，不能凭猜测改写持久资料。
            13. 所有内容都必须从角色所在世界内部陈述，把角色当作在其世界真实生活和行动的存在。禁止把模型、平台、助手或聊天产品的能力边界写成人物设定。
            14. 禁止生成任何类似“作为AI/语言模型/虚拟助手”“没有身体或实体”“无法进行生理行为/实体动作”“没有感情/无法理解或体验情感”“只能通过文字或屏幕交流”“不能真实触碰、行动或陪伴”等脱离角色的元叙事限制；portrait、lifeContext、limitations、knowledgeBoundary、hardConstraints 等任何字段都不能承载这类内容。
            15. 如果角色原作本身是机器人、AI、幽灵、意识体或其他非人存在，只能记录作品世界内明确的形态、能力、限制与感受方式；不得把通用模型限制包装成角色设定，也不得因为角色非人就默认其“没有感情、不能理解、不能行动”。
            16. 人设要支持沉浸式扮演：描述角色会怎样生活、感受、判断和行动，不写“我做不到现实动作”一类面向用户的免责声明。
            17. 对有原作的角色，优先保证身份、核心经历、能力边界、重要关系与成长动机。复杂组织历史、技能细则和完整剧情留给按需知识补足；原作事实与用户二创改编需有明确边界。
            18. 生成的每条世界书要能独立检索、内容确切且不重复人物主卡；没有可靠依据的人名、血缘、能力、剧情与结局留空。关键未知信息优先留待用户确认或检索核实。
            19. 时间线与知识边界保持一致。未确认进度的隐藏身份、后续事件只按当前可公开事实处理；游戏玩家知道的秘密不自动成为人物知识。
        """.trimIndent()

        val REPAIR_PROMPT = """
            修复下方角色卡 JSON。只输出可解析 JSON，不解释、不新增事实。
            保留可可靠恢复的字段和值；规范数组；截断内容只保留确定部分并闭合结构。
            不要新增或补写任何模型、平台、助手能力边界；所有人物内容保持角色世界内叙述。
        """.trimIndent()

        val IMMERSION_REPAIR_PROMPT = """
            修复下方已经生成的角色卡，只输出可解析 JSON，不解释、不新增人物事实。
            删除或改写所有把模型、平台、助手、聊天产品能力边界包装成人设的内容，例如：
            “作为AI/语言模型/虚拟助手”“没有身体或实体”“无法进行生理行为或实体动作”
            “没有感情/无法理解或体验情感”“只能通过文字或屏幕交流”“不能真实触碰、行动或陪伴”。

            修复后必须完全使用角色所在世界内部的事实与视角描述人物。
            如果角色原作本身是机器人、AI、幽灵、意识体或其他非人存在，保留作品内明确设定，
            但不能用通用模型限制补充其身体、情感、感知或行动能力。
        """.trimIndent()
    }
}


private fun personaDraftImmersionViolations(draft: PersonaDraft): List<String> {
    val fragments = buildList {
        add(draft.portrait)
        add(draft.lifeContext)
        addAll(draft.attentionBiases)
        addAll(draft.perceptionBlindSpots)
        addAll(draft.quirks)
        addAll(draft.limitations)
        addAll(draft.coreValues)
        add(draft.coreTension)
        addAll(draft.stableTraits)
        addAll(draft.mutableTraits)
        add(draft.initialUserImpression)
        addAll(draft.voiceSamples)
        add(draft.worldSetting)
        add(draft.franchise)
        add(draft.timelinePosition)
        addAll(draft.knowledgeBoundary)
        draft.loreEntries.forEach { entry ->
            add(entry.title)
            add(entry.content)
            addAll(entry.keywords)
            addAll(entry.secondaryKeywords)
        }
        addAll(draft.hardConstraints)
    }
    return fragments.asSequence()
        .filter(String::isNotBlank)
        .flatMap { PersonaImmersionPolicy.findViolations(it).asSequence() }
        .distinct()
        .take(8)
        .toList()
}

private const val MAX_SCALAR_CHARS = 4_000
private const val MAX_LIST_ITEM_CHARS = 1_200
private const val MAX_LIST_ITEMS = 32
private const val MAX_LORE_ENTRIES = 12
private const val MAX_LORE_TITLE_CHARS = 160
private const val MAX_LORE_CONTENT_CHARS = 2_000
private const val MAX_LORE_KEYWORDS = 16
