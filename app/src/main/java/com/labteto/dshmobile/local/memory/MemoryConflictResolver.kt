package com.labteto.dshmobile.local.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryConflictResolver @Inject constructor() {
    fun findReplacement(
        candidate: MemoryCandidate,
        existing: List<MemoryRecord>,
    ): MemoryRecord? {
        val comparable = existing.filter {
            it.active &&
                it.scope == candidate.scope &&
                it.kind == candidate.kind
        }
        return comparable
            .map { it to similarity(it.content, candidate.content) }
            .filter { (_, score) -> score >= REPLACE_THRESHOLD }
            .maxWithOrNull(
                compareBy<Pair<MemoryRecord, Double>> { it.second }
                    .thenBy { it.first.updatedAt },
            )
            ?.first
    }

    internal fun similarity(left: String, right: String): Double {
        val a = terms(left)
        val b = terms(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (normalize(left) == normalize(right)) return 1.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union
        val containment = intersection / minOf(a.size, b.size).toDouble()
        return maxOf(jaccard, containment * 0.92)
    }

    private fun terms(text: String): Set<String> {
        val normalized = normalize(text)
        val words = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { it.value }
            .take(64)
            .toMutableSet()
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(normalized).forEach { match ->
            match.value.windowed(2).take(48).forEach(words::add)
        }
        return words
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private companion object {
        const val REPLACE_THRESHOLD = 0.82
    }
}
