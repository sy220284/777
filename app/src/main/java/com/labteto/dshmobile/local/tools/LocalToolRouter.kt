package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
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

    fun visibleSchemas(tools: List<HarnessTool>, enabledOptional: Set<String>): JsonArray =
        JsonArray(
            tools
                .filter { tool ->
                    when (tool.exposure) {
                        ToolExposure.CORE -> true
                        ToolExposure.OPTIONAL -> tool.name in enabledOptional
                        ToolExposure.INTERNAL -> false
                    }
                }
                .map(HarnessTool::schema),
        )

    fun search(
        tools: List<HarnessTool>,
        query: String,
        limit: Int = 32,
    ): List<HarnessTool> {
        val normalized = query.trim().lowercase()
        require(normalized.isNotEmpty()) { "能力搜索内容不能为空" }
        val terms: Set<String> = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { match -> match.value }
            .take(24)
            .toSet()
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
            .take(limit.coerceIn(1, 48))
            .map { pair -> pair.first }
    }

    fun capabilitySummary(
        tools: List<HarnessTool>,
        enabledOptional: Set<String>,
    ): String {
        val optional = tools.filter(::isOptional)
        if (optional.isEmpty()) return "可选扩展能力：无"
        return buildString {
            appendLine("可选扩展能力（注册表真实状态）：")
            optional
                .groupBy { it.metadata.family }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (family, familyTools) ->
                    val enabled = familyTools.count { it.name in enabledOptional }
                    append("- ").append(family).append("：")
                    append(if (enabled == familyTools.size) "已启用" else if (enabled == 0) "未启用" else "部分启用")
                    append(" ").append(enabled).append("/").append(familyTools.size)
                    val requirements = familyTools.flatMap { it.metadata.requirements }.distinct()
                    if (requirements.isNotEmpty()) {
                        append("；前置条件：").append(requirements.joinToString("；"))
                    }
                    appendLine()
                }
            append("未启用能力可通过 capability_search 按能力名称、用途或关键词发现并启用。")
        }.trimEnd()
    }

    fun description(tool: HarnessTool): String =
        tool.schema["function"]?.jsonObject
            ?.get("description")?.jsonPrimitive?.contentOrNull
            .orEmpty()
}
