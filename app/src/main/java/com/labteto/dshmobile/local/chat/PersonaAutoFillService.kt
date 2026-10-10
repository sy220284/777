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
    val coreIdentity: String = "",
    val facts: List<CharacterFact> = emptyList(),
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
internal fun mergeGeneratedCharacterFacts(
    previous: List<CharacterFact>,
    incoming: List<CharacterFact>,
): List<CharacterFact> {
    val result = previous.toMutableList()
    incoming.filter { it.category.isNotBlank() && it.content.isNotBlank() }.forEach { fact ->
        // User-authored facts win over new AI drafts. No accidental replacement of explicit edits.
        if (result.any { it.category == fact.category && it.provenance == CharacterFactProvenance.USER_CREATED }) {
            return@forEach
        }
        val index = result.indexOfFirst { it.id == fact.id }
        if (index >= 0) {
            if (result[index].provenance != CharacterFactProvenance.USER_CREATED) result[index] = fact
        } else if (result.none {
                it.category == fact.category && it.content.trim() == fact.content.trim()
            }) {
            // Distinct facts in the same category carry independent events and sources.
            result += fact
        }
    }
    return result
}

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
        coreIdentity = root.text("coreIdentity"),
        facts = root.characterFacts(),
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

private fun JsonObject.characterFacts(): List<CharacterFact> {
    val items = this["facts"] as? JsonArray ?: return emptyList()
    return items.mapIndexedNotNull { index, item ->
        val data = item as? JsonObject ?: return@mapIndexedNotNull null
        val category = data.text("category")
        val content = data.text("content")
        if (category.isBlank() || content.isBlank()) return@mapIndexedNotNull null
        val provenance = runCatching {
            CharacterFactProvenance.valueOf(data.text("provenance"))
        }.getOrDefault(CharacterFactProvenance.UNVERIFIED)
        CharacterFact(
            id = data.text("id").ifBlank { "generated-$category-$index" },
            category = category,
            content = content,
            relatedFactIds = data.stringList("relatedFactIds"),
            perspective = data.text("perspective"),
            temporalScope = data.text("temporalScope"),
            provenance = provenance,
            sourceReference = data.text("sourceReference"),
        )
    }
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
            appendLine("已有的人物事实（用户编辑优先，勿重写未被请求修改的内容）：")
            appendLine("name：${current.name}")
            appendLine("franchise：${current.franchise}")
            appendLine("coreIdentity：${current.coreIdentity}")
            current.facts.forEach { fact ->
                appendLine("facts[${fact.category}]：${fact.content}（${fact.provenance}）")
            }
            current.loreEntries.forEach { entry ->
                appendLine("lore[${entry.id}] ${entry.title}：${entry.content.take(240)}")
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
            coreIdentity = draft.coreIdentity.ifBlank { current.coreIdentity },
            facts = mergeGeneratedCharacterFacts(current.facts, draft.facts),
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
            // Mutable-trait tracking belongs to runtime configuration, never to auto-generated
            // character facts. Existing user-authored axes stay intact during regeneration.
            mutableTraits = current.mutableTraits,
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
            你要生成777人物卡V4。仅输出完整可解析JSON对象，字段：
            name, franchise, coreIdentity, facts, loreEntries。
            facts是数组，每项含id、category、content，可选perspective、temporalScope、provenance、sourceReference、relatedFactIds。
            category推荐使用 personality、selfNarrative、identityGap、valuesAndTradeoffs、subjectiveBeliefs、
            biography、definingChoices、emotionalImprints、lifeGravity、unfinishedBusiness、
            relationships、limitsAndCosts、sensorySignature、preferencesAndHabits、voiceStyle、customFacts。
            provenance只允许 CANON、INFERRED、USER_CREATED、UNVERIFIED。
            1. name与核心身份尽可能明确；身份、稳定性格、经历、重要关系、能力边界、核心价值优先。
            2. 其它类别仅当有可靠信息才生成，禁止为了凑满16类编造秘密、创伤、未来成长和关系进度。
            3. 同一事实存一次；历史事件可以通过relatedFactIds关联其选择和影响，不重复复制长段落。
            4. 原作事实与推断、用户二创明确区分；不确定的设定可留空或标记UNVERIFIED。
            5. 人物资料只描写已有的人生事实、固有认知与偏好；当前情绪、成长、主动性、亲密度、
               回复节奏、行为频率和日常行动安排完全交给运行时。
            6. temporalScope填写明确的故事阶段ID时才需要；未来剧情、他人秘密不写成此刻已知事实。
            7. loreEntries为可选数组，每条可包括id,title,content,keywords,secondaryKeywords,priority,alwaysOn,spoilerLevel。
               复杂原作背景保留在可检索资料中；只有确切需要的资料才生成，不设置固定三条上限。
            8. 用户明确编辑过的资料保留；不得擅自覆盖已存在的USER_CREATED事实。
            9. 所有资料只从人物世界内部陈述，禁止添加通用模型限制或聊天平台元叙事。
            10. 语言风格可以记录固有表达特征，不预设固定台词与表演脚本。
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
        add(draft.coreIdentity)
        draft.facts.forEach { add(it.content); add(it.perspective) }
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
