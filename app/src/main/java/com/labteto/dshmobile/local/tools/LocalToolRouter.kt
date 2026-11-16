package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provides live capability search and task-driven device-permission hints.
 *
 * Exposure and discovery are declared by each tool through the shared HarnessTool contract.
 * Model-facing schemas are fixed gateways; this router never selects model tool definitions.
 */
internal object LocalToolRouter {
    fun isOptional(tool: HarnessTool): Boolean = tool.exposure == ToolExposure.OPTIONAL

    /**
     * Finds optional capabilities needed for device-permission hints.
     *
     * This is deliberately conservative: only declared discovery keywords or the concrete tool
     * name can pre-activate a capability. Ambiguous natural-language inference stays with
     * the live registry so an unrelated task does not request unrelated permissions.
     */
    fun relevantOptionalToolNames(
        tools: List<HarnessTool>,
        taskContext: String,
    ): List<String> {
        // Search the entire actual task. Previous head/tail slicing silently ignored
        // an explicit tool name placed in the middle of a long specification.
        val normalized = taskContext.lowercase()
        if (normalized.isBlank()) return emptyList()

        // Large registries repeat common intent terms across providers. Match each unique
        // term against this task once, without truncating the user's actual requirement.
        val matchedTerms = HashMap<String, Boolean>()
        fun matches(term: String): Boolean = matchedTerms.getOrPut(term.lowercase()) {
            hasExplicitIntentTerm(normalized, term)
        }

        // New plugin/MCP tools participate automatically through their registered metadata.
        // Order by task relevance, never by an arbitrary fixed number of optional tools.
        return tools.asSequence()
            .filter(::isOptional)
            .mapNotNull { tool ->
                val exactName = matches(tool.name)
                val keywordHits = tool.metadata.discoveryKeywords.count { keyword ->
                    matches(keyword)
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

    /**
     * Exact multi-name lookup in the capability-directory UI, not model tool activation.
     * Real execution enforces its own registry, permissions and approval.
     */
    fun exactSelection(query: String): List<String>? {
        val command = query.trim()
        if (!command.startsWith("select:", ignoreCase = true)) return null
        val names = command.substringAfter(':').split(',', '，').map(String::trim)
        require(names.isNotEmpty() && names.all {
            it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,127}"))
        }) { "精确工具选择格式：select:工具名1,工具名2" }
        return names.distinct()
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
        includeCore: Boolean = false,
    ): List<HarnessTool> {
        val normalized = query.trim().lowercase()
        require(normalized.isNotEmpty()) { "能力搜索内容不能为空" }
        // Index vocabulary from the live registry once, then examine the full query.
        // Irrelevant long-form text does not multiply scoring work by the catalog size.
        // Unlike head/tail sampling, explicit capabilities in the middle are preserved.
        val searchableTools = tools.filter {
            isOptional(it) || (includeCore && it.exposure == ToolExposure.CORE)
        }
        val vocabulary = HashSet<String>()
        fun index(value: String) {
            capabilitySearchTerms(value.lowercase(), splitIdentifierParts = true).forEach(vocabulary::add)
        }
        searchableTools.forEach { tool ->
            index(tool.name)
            index(tool.metadata.family)
            tool.metadata.discoveryKeywords.forEach(::index)
            index(description(tool))
            tool.metadata.requirements.forEach(::index)
            tool.metadata.usageNotes.forEach(::index)
        }
        val terms = capabilitySearchTerms(normalized)
            .filterTo(linkedSetOf()) { it in vocabulary }
        val scored = mutableListOf<Pair<HarnessTool, Int>>()
        for (tool in searchableTools) {
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

    /**
     * Tokenize the complete query and registered tool metadata with the same rules.
     * ASCII names embedded in Chinese instructions, underscore-separated identifiers
     * and unspaced Chinese phrases all remain discoverable.
     */
    private fun capabilitySearchTerms(
        text: String,
        splitIdentifierParts: Boolean = false,
    ): Sequence<String> = sequence {
        for (token in CAPABILITY_WORDS.findAll(text)) {
            val value = token.value
            yield(value)
            // Splitting is useful when indexing registered names, but splitting an
            // explicitly requested name would load unrelated tools sharing a suffix.
            if (splitIdentifierParts) {
                for (part in value.split('_', '-')) {
                    if (part.length >= 2) yield(part)
                }
            }
            for (han in CAPABILITY_HAN.findAll(value)) {
                val word = han.value
                for (index in 0 until word.length - 1) {
                    yield(word.substring(index, index + 2))
                }
            }
            for (identifier in CAPABILITY_ASCII.findAll(value)) {
                if (identifier.value.length >= 2) yield(identifier.value)
            }
        }
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

    fun description(tool: HarnessTool): String =
        tool.schema["function"]?.jsonObject
            ?.get("description")?.jsonPrimitive?.contentOrNull
            .orEmpty()

    private val CAPABILITY_WORDS = Regex("[\\p{L}\\p{N}_-]{2,}")
    private val CAPABILITY_HAN = Regex("[\u4e00-\u9fff]{2,}")
    private val CAPABILITY_ASCII = Regex("[a-z][a-z0-9_-]*")
}
