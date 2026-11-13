package com.labteto.dshmobile.local.files

/** Canonical local skill metadata derived from the existing SKILL.md; it is not a second store. */
internal data class LocalSkillMetadata(
    val name: String,
    val description: String = "",
    val displayName: String? = null,
    val whenToUse: String? = null,
    val modelInvocable: Boolean = true,
)

/**
 * Read only scalar front-matter fields used by the existing skill contract. Unknown fields stay in
 * the source document untouched. Incomplete front matter is hidden from automatic model access.
 */
internal fun parseLocalSkillMetadata(name: String, document: String): LocalSkillMetadata {
    val lines = document.lineSequence().take(96).toList()
    if (lines.firstOrNull()?.trim() != "---") return LocalSkillMetadata(name)
    val closing = lines.indexOfFirstAfterHeader { it.trim() == "---" }
    if (closing < 0) return LocalSkillMetadata(name, modelInvocable = false)
    val entries = buildMap<String, String> {
        lines.subList(1, closing).forEach { line ->
            val match = SKILL_FRONT_MATTER_FIELD.matchEntire(line) ?: return@forEach
            val key = match.groupValues[1].lowercase().replace("_", "-")
            val raw = match.groupValues[2].trim().substringBefore(" #").trim()
            val value = raw.removeSurrounding("\"").removeSurrounding("'").trim()
            putIfAbsent(key, value)
        }
    }
    val disabled = entries["disable-model-invocation"]?.equals("true", ignoreCase = true) == true
    return LocalSkillMetadata(
        name = name,
        displayName = (entries["display-name"] ?: entries["displayname"] ?: entries["title"])
            ?.takeIf { it.length in 1..40 && it.any { c -> c in '\u4e00'..'\u9fff' } },
        description = entries["description"].orEmpty().take(MAX_SKILL_SUMMARY_CHARS),
        whenToUse = (entries["when-to-use"] ?: entries["whentouse"])
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_SKILL_SUMMARY_CHARS),
        modelInvocable = !disabled,
    )
}

private fun List<String>.indexOfFirstAfterHeader(predicate: (String) -> Boolean): Int {
    for (index in 1 until size) if (predicate(this[index])) return index
    return -1
}

private const val MAX_SKILL_SUMMARY_CHARS = 240
private val SKILL_FRONT_MATTER_FIELD = Regex("^([A-Za-z][A-Za-z0-9_-]*):\\s*(.*?)\\s*$")
