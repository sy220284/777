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
        storyStage: String = "",
        unlockedStages: Collection<String> = emptyList(),
    ): List<PersonaLoreEntry> {
        if (persona.loreEntries.isEmpty()) return emptyList()
        val normalizedQuery = normalize(query)
        val terms = terms(query)
        val visibleStages = (unlockedStages + storyStage)
            .map(String::trim).filter(String::isNotBlank).toSet()
        val candidates = persona.loreEntries.asSequence()
            // A chosen plot stage grants only lore bound to that stage. Unscoped high-
            // spoiler content still requires an independent spoiler-level grant.
            .filter { entry ->
                val stage = entry.temporalScope.trim()
                // A stage-scoped entry is released only by an explicitly visited stage.
                // An unscoped high-spoiler entry still needs a spoiler-level grant.
                val stageUnlocked = stage.isNotBlank() && stage in visibleStages
                entry.content.isNotBlank() &&
                    (stage.isBlank() || stageUnlocked) &&
                    (entry.spoilerLevel <= maxSpoilerLevel.coerceIn(0, 3) || stageUnlocked)
            }
            .map { entry -> entry to score(entry, normalizedQuery, terms) }
            .filter { (entry, score) -> entry.alwaysOn || score > 0 }
            .sortedWith(
                compareByDescending<Pair<PersonaLoreEntry, Int>> { if (it.first.alwaysOn) 1 else 0 }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.priority },
            )
            .map { it.first }
            .toList()

        val characterBudget = maxChars.coerceIn(400, 6_000)
        val entryLimit = maxItems.coerceIn(1, 12)
        var used = 0
        val selected = mutableListOf<PersonaLoreEntry>()
        for (entry in candidates) {
            if (selected.size >= entryLimit) break
            val remaining = characterBudget - used - entry.title.length - 24
            if (remaining <= 0) continue

            // A long high-priority entry must not prevent shorter relevant lore from being considered.
            // Keep the canonical persona entry untouched; only project a bounded excerpt for this turn.
            val projected = if (entry.content.length <= remaining) entry else {
                val excerptBudget = minOf(remaining, maxOf(160, characterBudget / minOf(entryLimit, 3)))
                if (excerptBudget < 40) continue
                entry.copy(content = relevantLoreExcerpt(entry.content, query, excerptBudget))
            }
            val cost = projected.title.length + projected.content.length + 24
            if (cost > characterBudget - used) continue
            selected += projected
            used += cost
        }
        return selected
    }

    private fun relevantLoreExcerpt(content: String, query: String, limit: Int): String {
        if (content.length <= limit) return content
        val queryTerms = terms(query).filter { it.length >= 2 }
        val matched = queryTerms.asSequence().map { content.indexOf(it, ignoreCase = true) }
            .firstOrNull { it >= 0 } ?: 0
        val available = (limit - 2).coerceAtLeast(1)
        val start = (matched - available / 3).coerceIn(0, content.length - available)
        val end = start + available
        return (if (start > 0) "…" else "") +
            content.substring(start, end) +
            (if (end < content.length) "…" else "")
    }

    fun prompt(
        persona: PersonaProfile,
        query: String,
        maxSpoilerLevel: Int = 0,
        storyStage: String = "",
        unlockedStages: Collection<String> = emptyList(),
    ): String {
        val active = activated(
            persona, query, maxSpoilerLevel,
            storyStage = storyStage, unlockedStages = unlockedStages,
        )
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("【相关世界信息】以下是当前已解锁的故事背景，不等于人物亲历或已知。")
            appendLine("只在符合人物当前认知、经历与记忆时使用；未获知的秘密不得以本人见闻自述，不补写未提供的设定。")
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
