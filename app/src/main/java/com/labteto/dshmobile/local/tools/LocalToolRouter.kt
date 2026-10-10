package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.local.model.estimateModelTokens
import kotlinx.serialization.json.JsonArray
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
                val exactName = normalized.contains(tool.name.lowercase())
                val keywordHits = tool.metadata.discoveryKeywords.count { keyword ->
                    normalized.contains(keyword.lowercase())
                }
                val familyHit = normalized.contains(tool.metadata.family.lowercase())
                val score = (if (exactName) 100 else 0) + keywordHits * 10 +
                    (if (familyHit) 3 else 0)
                if (score > 0) tool to score else null
            }
            .sortedWith(compareByDescending<Pair<HarnessTool, Int>> { it.second }
                .thenBy { it.first.name })
            .map { it.first.name }
            .distinct()
            .toList()
    }

    fun visibleSchemas(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
        maxOptionalDefinitionTokens: Int = DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
    ): JsonArray {
        val core = tools
            .filter { tool -> tool.exposure == ToolExposure.CORE }
            .sortedBy(HarnessTool::name)
        val optionalByName = tools
            .asSequence()
            .filter { tool -> tool.exposure == ToolExposure.OPTIONAL }
            .associateBy(HarnessTool::name)

        var remainingTokens = maxOptionalDefinitionTokens
            .coerceIn(0, MAX_OPTIONAL_TOOL_PROMPT_TOKENS)
        val optional = ArrayList<HarnessTool>()
        for (name in enabledOptional) {
            if (remainingTokens <= 0) break
            val tool = optionalByName[name] ?: continue
            val schema = tool.schema
            val complexity = schemaComplexity(schema)
            if (
                complexity.depth > MAX_OPTIONAL_SCHEMA_DEPTH ||
                complexity.properties > MAX_OPTIONAL_SCHEMA_PROPERTIES
            ) continue
            val tokens = estimateModelTokens(schema.toString())
            if (tokens > MAX_OPTIONAL_SCHEMA_TOKENS || tokens > remainingTokens) continue
            optional += tool
            remainingTokens -= tokens
        }

        // Core remains deterministic for prompt-cache stability; optional tools append in activation order.
        // Registry limits protect process resources; this separate budget protects the model prompt.
        return JsonArray((core + optional).map(HarnessTool::schema))
    }

    fun search(
        tools: List<HarnessTool>,
        query: String,
        limit: Int = Int.MAX_VALUE,
    ): List<HarnessTool> {
        val normalized = query.trim().lowercase()
        require(normalized.isNotEmpty()) { "能力搜索内容不能为空" }
        // Bound parsing work while preserving intent at both ends of a long request.
        // A tool name appended after detailed instructions must remain discoverable.
        val termPattern = Regex("[\\p{L}\\p{N}_-]{2,}")
        val front = termPattern.findAll(normalized.take(MAX_CAPABILITY_QUERY_EDGE_CHARS))
            .map { it.value }.take(12).toList()
        val tail = termPattern.findAll(normalized.takeLast(MAX_CAPABILITY_QUERY_EDGE_CHARS))
            .map { it.value }.toList().takeLast(12)
        // Java regex treats a continuous Chinese phrase as one word. Add bigrams so
        // natural requests can match Chinese capability keywords without a hardcoded tool list.
        val chunks = front + tail
        val hanWords = Regex("[\\u4e00-\\u9fff]{2,}")
        val chineseTerms = chunks.flatMap { chunk ->
            hanWords.findAll(chunk).flatMap { it.value.windowed(2).asSequence() }.toList()
        }
        val terms = (chunks + chineseTerms).toSet()
        val scored = mutableListOf<Pair<HarnessTool, Int>>()
        for (tool in tools) {
            if (!isOptional(tool)) continue
            val haystack = buildString {
                append(tool.name.lowercase())
                append(' ').append(description(tool).lowercase())
                append(' ').append(tool.metadata.family.lowercase())
                append(' ').append(tool.metadata.discoveryKeywords.joinToString(" ").lowercase())
                append(' ').append(tool.metadata.requirements.joinToString(" ").lowercase())
                append(' ').append(tool.metadata.usageNotes.joinToString(" ").lowercase())
            }
            var score = 0
            for (term in terms) {
                score += when {
                    tool.name.equals(term, ignoreCase = true) -> 100
                    tool.name.contains(term, ignoreCase = true) -> 25
                    tool.metadata.family.equals(term, ignoreCase = true) -> 20
                    haystack.contains(term) -> 10
                    else -> 0
                }
            }
            if (score > 0) scored += tool to score
        }
        scored.sortWith(
            compareByDescending<Pair<HarnessTool, Int>> { pair -> pair.second }
                .thenBy { pair -> pair.first.name },
        )
        return scored
            .take(limit.coerceAtLeast(1))
            .map { pair -> pair.first }
    }

    fun capabilitySummary(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
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
    fun capabilityDirectory(tools: List<HarnessTool>, enabledOptional: Set<String>): String =
        buildString {
            val optional = tools.filter(::isOptional)
            appendLine("当前注册的可选工具：${optional.size} 个，分 ${optional.map { it.metadata.family }.distinct().size} 类。")
            optional.groupBy { it.metadata.family }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (family, members) ->
                    val selected = members.count { it.name in enabledOptional }
                    appendLine("- $family：已注册 ${members.size}，本轮已选 ${selected}；工具：${members.joinToString(", ") { it.name }}")
                }
            append("目录由实时注册表生成；本轮未选不代表无法使用。选择具体能力时再次调用 capability_search 加载工具定义；实际执行仍需满足连接与授权条件。")
        }.trimEnd()

    fun description(tool: HarnessTool): String =
        tool.schema["function"]?.jsonObject
            ?.get("description")?.jsonPrimitive?.contentOrNull
            .orEmpty()

    fun conciseDescription(tool: HarnessTool): String =
        description(tool).take(MAX_CAPABILITY_DESCRIPTION_CHARS)

    private data class SchemaComplexity(
        val depth: Int,
        val properties: Int,
    )

    private fun schemaComplexity(schema: kotlinx.serialization.json.JsonElement): SchemaComplexity {
        fun visit(element: kotlinx.serialization.json.JsonElement, depth: Int): SchemaComplexity =
            when (element) {
                is kotlinx.serialization.json.JsonObject -> {
                    var maxDepth = depth
                    var properties = (element["properties"] as? kotlinx.serialization.json.JsonObject)?.size ?: 0
                    element.values.forEach { child ->
                        val nested = visit(child, depth + 1)
                        maxDepth = maxOf(maxDepth, nested.depth)
                        properties += nested.properties
                    }
                    SchemaComplexity(maxDepth, properties)
                }
                is kotlinx.serialization.json.JsonArray -> {
                    var maxDepth = depth
                    var properties = 0
                    element.forEach { child ->
                        val nested = visit(child, depth + 1)
                        maxDepth = maxOf(maxDepth, nested.depth)
                        properties += nested.properties
                    }
                    SchemaComplexity(maxDepth, properties)
                }
                else -> SchemaComplexity(depth, 0)
            }
        return visit(schema, 1)
    }

    internal const val DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS = 16_000
    private const val MAX_OPTIONAL_TOOL_PROMPT_TOKENS = 24_000
    private const val MAX_OPTIONAL_SCHEMA_TOKENS = 4_000
    private const val MAX_OPTIONAL_SCHEMA_DEPTH = 12
    private const val MAX_OPTIONAL_SCHEMA_PROPERTIES = 256
    private const val MAX_SUMMARY_REQUIREMENTS_PER_FAMILY = 4
    private const val MAX_CAPABILITY_DESCRIPTION_CHARS = 480
    private const val MAX_TASK_CONTEXT_CHARS = 12_000
    private const val MAX_CAPABILITY_QUERY_EDGE_CHARS = 4_096
}
