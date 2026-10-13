package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.local.model.estimateModelTokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps the model-facing tool surface small without hiding capabilities permanently.
 *
 * Exposure and discovery are declared by each tool through the shared HarnessTool contract.
 * No tool is classified by name prefix or a hand-maintained allowlist.
 */
internal object LocalToolRouter {
    fun isOptional(tool: HarnessTool): Boolean = tool.exposure == ToolExposure.OPTIONAL

    /**
     * Finds optional capabilities that are already explicit in the current task intent.
     *
     * This is deliberately conservative: only declared discovery keywords or the concrete tool
     * name can pre-activate a capability. Ambiguous natural-language inference stays with
     * capability_search so an unrelated task does not silently re-inflate the prompt.
     */
    fun relevantOptionalToolNames(
        tools: List<HarnessTool>,
        taskContext: String,
    ): List<String> {
        val normalized = (if (taskContext.length <= MAX_TASK_CONTEXT_CHARS) taskContext
            else taskContext.take(MAX_TASK_CONTEXT_CHARS / 2) + "\n" +
                taskContext.takeLast(MAX_TASK_CONTEXT_CHARS / 2)).lowercase()
        if (normalized.isBlank()) return emptyList()

        // New plugin/MCP tools participate automatically through their registered metadata.
        // Order by task relevance, never by an arbitrary fixed number of optional tools.
        return tools.asSequence()
            .filter(::isOptional)
            .mapNotNull { tool ->
                val exactName = hasExplicitIntentTerm(normalized, tool.name)
                val keywordHits = tool.metadata.discoveryKeywords.count { keyword ->
                    hasExplicitIntentTerm(normalized, keyword)
                }
                // A broad family label ("网络", "智能体", "会话") must not implicitly activate
                // every optional tool in that category. Explicit discovery still searches family.
                val score = (if (exactName) 100 else 0) + keywordHits * 10
                if (score > 0) tool to score else null
            }
            .sortedWith(compareByDescending<Pair<HarnessTool, Int>> { it.second }
                .thenBy { it.first.name })
            .map { it.first.name }
            .distinct()
            .toList()
    }

    /**
     * Native/Latin tool tokens must match at identifier boundaries. Plain substring checks make
     * "api" match "capital", "pr" match "preview", and "web" match "website", silently
     * adding unrelated schemas. Han text may be attached to Latin names (使用GitHub工具), so
     * only ASCII identifier characters count as token neighbors.
     */
    private fun hasExplicitIntentTerm(text: String, rawTerm: String): Boolean {
        val term = rawTerm.trim().lowercase()
        if (term.isEmpty()) return false
        var from = 0
        while (true) {
            val index = text.indexOf(term, startIndex = from)
            if (index < 0) return false
            val after = index + term.length
            val leftOk = !term.first().isAsciiIdentifierChar() ||
                index == 0 || !text[index - 1].isAsciiIdentifierChar()
            val rightOk = !term.last().isAsciiIdentifierChar() ||
                after == text.length || !text[after].isAsciiIdentifierChar()
            if (leftOk && rightOk) return true
            from = index + 1
        }
    }

    private fun Char.isAsciiIdentifierChar(): Boolean =
        this in 'a'..'z' || this in '0'..'9' || this == '_'

    internal data class ToolSchemaSelection(
        val schemas: JsonArray,
        val omittedReasons: Map<String, String>,
        val compactedNames: Set<String>,
    )

    fun visibleSchemas(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
        maxOptionalDefinitionTokens: Int = DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): JsonArray = selectSchemas(tools, enabledOptional, maxOptionalDefinitionTokens).schemas

    /**
     * Tool capability discovery is never the authority for admission. Selection reports why a
     * requested registered tool was not exposed. Oversized human descriptions are abbreviated
     * without altering argument types, required fields or the runtime's full validator schema.
     */
    internal fun selectSchemas(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
        maxOptionalDefinitionTokens: Int = DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): ToolSchemaSelection {
        val core = tools
            .filter { it.exposure == ToolExposure.CORE }
            .sortedBy(HarnessTool::name)
        val optionalByName = tools
            .asSequence()
            .filter { it.exposure == ToolExposure.OPTIONAL }
            .associateBy(HarnessTool::name)

        var remainingTokens = maxOptionalDefinitionTokens.coerceIn(0, MAX_OPTIONAL_TOOL_PROMPT_TOKENS)
        val optionalSchemas = ArrayList<JsonElement>()
        val omitted = linkedMapOf<String, String>()
        val compacted = linkedSetOf<String>()
        for (name in enabledOptional) {
            val tool = optionalByName[name] ?: continue // Missing tools remain absent on purpose.
            // A valid, large MCP/plug-in schema is not an admission error merely because
            // it has many properties or nested objects. Let the actual model-facing
            // definition budget decide; runtime argument validation still uses the
            // complete registered schema.
            val original = tool.schema
            if (remainingTokens <= 0) {
                omitted[name] = "本轮可选工具定义预算已用尽；按精确名称重新选择可优先装入"
                continue
            }

            val originalCost = estimateModelTokens(original.toString())
            val reduced = if (originalCost > minOf(remainingTokens, MAX_OPTIONAL_SCHEMA_TOKENS)) {
                compactDescriptiveAnnotations(original)
            } else original
            val selectedCost = estimateModelTokens(reduced.toString())
            if (selectedCost > MAX_OPTIONAL_SCHEMA_TOKENS || selectedCost > remainingTokens) {
                omitted[name] = "工具参数定义需要约 $selectedCost Token，当前可用 $remainingTokens；已尝试精简说明文本"
                continue
            }
            optionalSchemas += reduced
            if (reduced != original) compacted += name
            remainingTokens -= selectedCost
        }

        // The core surface remains byte-for-byte stable for cache continuity.
        return ToolSchemaSelection(JsonArray(core.map(HarnessTool::schema) + optionalSchemas),
            omitted, compacted)
    }

    private fun compactDescriptiveAnnotations(value: JsonElement, propertyNames: Boolean = false): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                when {
                    key == "description" && !propertyNames && child is JsonPrimitive &&
                        child.isString && child.content.length > MAX_COMPACT_DESCRIPTION_CHARS ->
                        JsonPrimitive(child.content.take(MAX_COMPACT_DESCRIPTION_CHARS) + "…")
                    else -> compactDescriptiveAnnotations(child, propertyNames = key == "properties")
                }
            })
            is JsonArray -> JsonArray(value.map { compactDescriptiveAnnotations(it) })
            else -> value
        }

    /**
     * Ranks capabilities by the strongest matching evidence. A flood of weak description/family
     * terms can no longer outrank a concrete tool identifier or an explicit discovery keyword.
     * Search does not change the conservative implicit preactivation contract.
     */
    fun search(
        tools: List<HarnessTool>,
        query: String,
        limit: Int = Int.MAX_VALUE,
    ): List<HarnessTool> {
        val normalized = query.trim().lowercase()
        require(normalized.isNotEmpty()) { "能力搜索内容不能为空" }
        val termPattern = Regex("[\\p{L}\\p{N}_-]{2,}")
        val front = termPattern.findAll(normalized.take(MAX_CAPABILITY_QUERY_EDGE_CHARS))
            .map { it.value }.take(12).toList()
        val tail = termPattern.findAll(normalized.takeLast(MAX_CAPABILITY_QUERY_EDGE_CHARS))
            .map { it.value }.toList().takeLast(12)
        val chunks = front + tail
        val hanWords = Regex("[\u4e00-\u9fff]{2,}")
        val chineseTerms = chunks.flatMap { chunk ->
            hanWords.findAll(chunk).flatMap { it.value.windowed(2).asSequence() }.toList()
        }
        val terms = (chunks + chineseTerms).toSet()
        val scored = mutableListOf<Pair<HarnessTool, Int>>()
        for (tool in tools) {
            if (!isOptional(tool)) continue
            val name = tool.name.lowercase()
            val family = tool.metadata.family.lowercase()
            val keywords = tool.metadata.discoveryKeywords.map(String::lowercase)
            val descriptionText = description(tool).lowercase()
            val explanatoryText = (tool.metadata.requirements + tool.metadata.usageNotes)
                .joinToString(" ").lowercase()
            var strongest = 0
            var supportingHits = 0
            for (term in terms) {
                val match = when {
                    name == term -> 10_000
                    name.split('_', '-').any { it == term } -> 2_000
                    keywords.any { it == term } -> 1_200
                    family == term -> 800
                    hasExplicitIntentTerm(name, term) -> 600
                    keywords.any { hasExplicitIntentTerm(it, term) } -> 400
                    hasExplicitIntentTerm(descriptionText, term) -> 160
                    hasExplicitIntentTerm(explanatoryText, term) -> 80
                    else -> 0
                }
                strongest = maxOf(strongest, match)
                if (match > 0) supportingHits++
            }
            if (strongest > 0) {
                // At most 16 tie-break points: relevance tier, not query verbosity, drives ranking.
                scored += tool to strongest * 32 + supportingHits.coerceAtMost(16)
            }
        }
        scored.sortWith(
            compareByDescending<Pair<HarnessTool, Int>> { it.second }
                .thenBy { it.first.name },
        )
        return scored.take(limit.coerceAtLeast(1)).map { it.first }
    }

    fun capabilitySummary(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
        blocked: Set<String> = emptySet(),
    ): String {
        val optional = tools.filter(::isOptional)
        if (optional.isEmpty()) return "可选扩展能力：无"
        return buildString {
            appendLine("可选扩展能力（已注册；以下为本轮选择状态，并非连接或授权状态）：")
            optional
                .groupBy { it.metadata.family }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (family, familyTools) ->
                    val enabled = familyTools.count { it.name in enabledOptional }
                    append("- ").append(family).append("：")
                    append(if (enabled == familyTools.size) "本轮已选用" else if (enabled == 0) "本轮未选用" else "本轮部分选用")
                    append(" ").append(enabled).append("/").append(familyTools.size)
                    val requirements = familyTools.flatMap { it.metadata.requirements }.distinct()
                    if (requirements.isNotEmpty()) {
                        val shown = requirements.take(MAX_SUMMARY_REQUIREMENTS_PER_FAMILY)
                        append("；前置条件：").append(shown.joinToString("；"))
                        if (requirements.size > shown.size) {
                            append("；另有 ").append(requirements.size - shown.size).append(" 项")
                        }
                    }
                    val blockedTools = familyTools.filter { it.name in blocked }
                    if (blockedTools.isNotEmpty()) {
                        append("；运行策略关闭：").append(blockedTools.joinToString(", ") { it.name })
                    }
                    appendLine()
                }
            append("本轮未选用的能力仍已注册，可由智能体按需发现；是否能执行须以实际连接、授权和模型工具表为准。")
        }.trimEnd()
    }

    /** Generic inventory requests must browse the live registry instead of failing fuzzy search. */
    fun isInventoryRequest(query: String): Boolean {
        val normalized = query.trim().lowercase().replace(Regex("\\s+"), "")
        return normalized in setOf(
            "工具与能力检测", "工具能力检测", "能力检测", "工具检测", "能力列表",
            "工具列表", "有哪些工具", "有哪些能力", "所有工具", "全部工具",
            "所有能力", "全部能力", "可用工具", "可用能力", "扩展能力",
            "能力目录", "工具目录", "列出能力", "列出工具", "能力自检",
        )
    }

    /** The directory is generated from currently registered tools, including dynamic MCP tools. */
    fun capabilityDirectory(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
        blocked: Set<String> = emptySet(),
    ): String = buildString {
            val listed = tools.filter { it.exposure != ToolExposure.INTERNAL }
            val optionalCount = listed.count(::isOptional)
            val coreCount = listed.size - optionalCount
            appendLine("当前可供智能体发现的工具：${listed.size} 个（核心 ${coreCount}，扩展 ${optionalCount}），分 ${listed.map { it.metadata.family }.distinct().size} 类。")
            listed.groupBy { it.metadata.family }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (family, members) ->
                    val selected = members.count { it.exposure == ToolExposure.CORE || it.name in enabledOptional }
                    appendLine("- $family：已注册 ${members.size}，本轮已选 ${selected}；工具：" +
                        members.joinToString(", ") { tool ->
                            tool.name + if (tool.name in blocked) "（当前设置已关闭）" else ""
                        })
                }
            append("目录由实时注册表生成；核心工具默认提供，可选扩展按需通过 capability_search 加载。本轮未选不代表未注册；实际执行仍需满足连接与授权条件。")
        }.trimEnd()

    fun description(tool: HarnessTool): String =
        tool.schema["function"]?.jsonObject
            ?.get("description")?.jsonPrimitive?.contentOrNull
            .orEmpty()

    fun conciseDescription(tool: HarnessTool): String =
        description(tool).take(MAX_CAPABILITY_DESCRIPTION_CHARS)

    internal const val DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS = 16_000
    private const val MAX_OPTIONAL_TOOL_PROMPT_TOKENS = 24_000
    private const val MAX_OPTIONAL_SCHEMA_TOKENS = 24_000
    private const val MAX_COMPACT_DESCRIPTION_CHARS = 240
    private const val MAX_SUMMARY_REQUIREMENTS_PER_FAMILY = 4
    private const val MAX_CAPABILITY_DESCRIPTION_CHARS = 480
    private const val MAX_TASK_CONTEXT_CHARS = 12_000
    private const val MAX_CAPABILITY_QUERY_EDGE_CHARS = 4_096
}
