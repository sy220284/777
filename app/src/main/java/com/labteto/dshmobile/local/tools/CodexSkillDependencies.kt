package com.labteto.dshmobile.local.tools

/**
 * Codex-compatible dependency records from agents/openai.yaml.
 *
 * The source is optional. An unsupported declaration is retained as an unresolved requirement,
 * never silently treated as ready. Reading metadata cannot execute commands or grant consent.
 */
internal data class CodexSkillDependency(
    val type: String,
    val value: String,
    val description: String = "",
    val transport: String? = null,
    val url: String? = null,
    val command: String? = null,
)

/**
 * A deliberately limited metadata reader: recognizes the Codex dependencies.tools list, including
 * quoted YAML scalars. Other YAML constructs inside that list are surfaced as unresolved instead
 * of being interpreted as permissions. No third-party YAML parser executes constructors.
 */
internal fun parseCodexSkillDependencies(source: String): List<CodexSkillDependency> {
    val lines = source.lineSequence().toList()
    var inDependencies = false
    var inTools = false
    var toolsIndent = -1
    var dependencyIndent = -1
    var record = linkedMapOf<String, String>()
    val result = mutableListOf<CodexSkillDependency>()
    var unrecognized = false

    fun flush() {
        if (record.isEmpty()) return
        val type = record["type"].orEmpty().trim()
        val value = record["value"].orEmpty().trim()
        if (type.isNotBlank() && value.isNotBlank()) {
            result += CodexSkillDependency(
                type.take(64), value.take(1024),
                record["description"].orEmpty().take(1024),
                transport = record["transport"]?.take(64),
                url = record["url"]?.take(1024),
                command = record["command"]?.take(1024),
            )
        } else {
            unrecognized = true
        }
        record = linkedMapOf()
    }
    for (line in lines) {
        if (line.isBlank() || line.trimStart().startsWith("#")) continue
        val indent = line.indexOfFirst { it != ' ' }.coerceAtLeast(0)
        val value = line.trim()
        if (indent == 0) {
            if (inTools) flush()
            inDependencies = value.startsWith("dependencies:")
            if (inDependencies && value != "dependencies:") unrecognized = true
            inTools = false
            toolsIndent = -1
            continue
        }
        if (!inDependencies) continue
        if (!inTools) {
            if (value.startsWith("tools:")) {
                inTools = true
                toolsIndent = indent
                if (value != "tools:") unrecognized = true
            }
            continue
        }
        if (indent <= toolsIndent) {
            flush()
            inTools = false
            if (value.startsWith("tools:")) {
                inTools = true
                toolsIndent = indent
                if (value != "tools:") unrecognized = true
            }
            continue
        }
        if (value.startsWith("- ")) {
            flush()
            dependencyIndent = indent
            val first = value.removePrefix("- ").trim()
            if (first.isNotBlank()) {
                val entry = parseCodexScalarField(first)
                if (entry == null) unrecognized = true else record[entry.first] = entry.second
            }
        } else if (indent > dependencyIndent && dependencyIndent >= 0) {
            val entry = parseCodexScalarField(value)
            if (entry == null) unrecognized = true else record[entry.first] = entry.second
        } else {
            unrecognized = true
        }
    }
    flush()
    if (unrecognized) result += CodexSkillDependency("unknown", "metadata", "包含需要人工核实的依赖声明")
    return result.distinct()
}

private fun parseCodexScalarField(source: String): Pair<String, String>? {
    val colon = source.indexOf(':')
    if (colon < 1) return null
    val key = source.take(colon).trim()
    if (key !in setOf("type", "value", "description", "transport", "url", "command")) return null
    val raw = source.substring(colon + 1).trim()
    if (raw.contains('[') || raw.contains('{') || raw.startsWith("&") || raw.startsWith("*") ||
        raw.startsWith("!") || raw.startsWith("|") || raw.startsWith(">")) return null
    val content = if (raw.length >= 2 &&
        ((raw.first() == '"' && raw.last() == '"') || (raw.first() == '\'' && raw.last() == '\''))) {
        raw.substring(1, raw.lastIndex)
    } else raw.substringBefore(" #")
    return key to content.trim()
}
