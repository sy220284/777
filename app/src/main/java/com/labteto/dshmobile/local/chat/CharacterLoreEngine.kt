package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Activates only the character lore relevant to the current turn.
 *
 * This is intentionally a thin, deterministic layer. Long-term memory continues to use the
 * existing MemoryStore/MemoryManager pipeline; lore stays attached to the persona so character
 * canon does not leak into global user memory.
 */
@Singleton
class CharacterLoreEngine @Inject constructor() {

    fun activated(
        persona: PersonaProfile,
        query: String,
        maxSpoilerLevel: Int = 0,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<PersonaLoreEntry> {
        if (persona.loreEntries.isEmpty()) return emptyList()
        val normalizedQuery = normalize(query)
        val terms = terms(query)
        val candidates = persona.loreEntries.asSequence()
            .filter { it.content.isNotBlank() && it.spoilerLevel <= maxSpoilerLevel.coerceIn(0, 3) }
            .map { entry -> entry to score(entry, normalizedQuery, terms) }
            .filter { (entry, score) -> entry.alwaysOn || score > 0 }
            .sortedWith(
                compareByDescending<Pair<PersonaLoreEntry, Int>> { if (it.first.alwaysOn) 1 else 0 }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.priority },
            )
            .map { it.first }
            .toList()

        var used = 0
        val selected = mutableListOf<PersonaLoreEntry>()
        for (entry in candidates) {
            val cost = entry.title.length + entry.content.length + 24
            if (selected.isNotEmpty() && used + cost > maxChars.coerceIn(400, 6_000)) break
            selected += entry
            used += cost
            if (selected.size >= maxItems.coerceIn(1, 12)) break
        }
        return selected
    }

    fun prompt(
        persona: PersonaProfile,
        query: String,
        maxSpoilerLevel: Int = 0,
    ): String {
        val active = activated(persona, query, maxSpoilerLevel)
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("【本轮相关世界信息】")
            appendLine("只把下面内容当作角色已知的背景资料；没有激活的条目不要自行补成已知事实。")
            active.forEach { entry ->
                if (entry.title.isNotBlank()) appendLine("【${entry.title}】")
                appendLine(entry.content)
            }
        }.trim()
    }

    private fun score(entry: PersonaLoreEntry, normalizedQuery: String, queryTerms: Set<String>): Int {
        if (entry.alwaysOn) return 1_000 + entry.priority.coerceIn(0, 100)
        var matched = false
        var score = 0
        entry.keywords.forEach { keyword ->
            val normalized = normalize(keyword)
            if (normalized.isNotBlank() && normalizedQuery.contains(normalized)) {
                matched = true
                score += 80
            }
        }
        entry.secondaryKeywords.forEach { keyword ->
            val normalized = normalize(keyword)
            if (normalized.isNotBlank() && normalizedQuery.contains(normalized)) {
                matched = true
                score += 35
            }
        }
        val titleTerms = terms(entry.title)
        val titleOverlap = queryTerms.count(titleTerms::contains)
        if (titleOverlap > 0) {
            matched = true
            score += titleOverlap * 18
        }
        return if (matched) score + entry.priority.coerceIn(0, 100) / 10 else 0
    }

    private fun terms(text: String): Set<String> {
        val normalized = text.lowercase().take(2_000)
        val result = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { it.value }
            .take(48)
            .toMutableSet()
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(normalized).forEach { match ->
            match.value.windowed(2).take(24).forEach(result::add)
        }
        return result
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】—_-]+"""), "")

    private companion object {
        const val DEFAULT_MAX_ITEMS = 6
        const val DEFAULT_MAX_CHARS = 2_400
    }
}
