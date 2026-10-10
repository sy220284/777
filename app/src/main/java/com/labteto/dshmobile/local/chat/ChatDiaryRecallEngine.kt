package com.labteto.dshmobile.local.chat

internal object ChatDiaryRecallEngine {
    @Suppress("UNUSED_PARAMETER")
    fun search(
        entries: List<ChatDiaryEntry>,
        query: String,
        subjectKey: String,
        groupAudience: Boolean,
        maxItems: Int,
        now: Long = System.currentTimeMillis(),
    ): List<ChatDiaryEntry> {
        val cleanSubject = subjectKey.trim()
        if (cleanSubject.isBlank()) return emptyList()

        val queryCore = ChatDiaryEntryPolicy.normalizeQuery(query)
        val queryTerms = ChatDiaryEntryPolicy.queryTerms(query)
        val broad = isExplicitDiaryRecall(query)
        val historical = isHistoricalDiaryRecall(query)

        val eligible = entries.asSequence()
            .filter { entry ->
                entry.active &&
                    entry.subjectKey == cleanSubject &&
                    (entry.supersededBy == null || historical)
            }
            .toList()

        val candidates = if (broad || eligible.size <= MAX_NORMAL_RECALL_CANDIDATES) {
            eligible
        } else {
            val recent = eligible.sortedByDescending(ChatDiaryEntry::updatedAt)
                .take(MAX_NORMAL_RECALL_CANDIDATES)
            val important = eligible.asSequence()
                .filter { it.importance >= IMPORTANT_RECALL_THRESHOLD }
                .sortedByDescending(ChatDiaryEntry::updatedAt)
                .take(MAX_IMPORTANT_RECALL_CANDIDATES)
                .toList()
            (recent + important).distinctBy(ChatDiaryEntry::id)
        }

        return candidates.asSequence()
            .map { entry ->
                val score = ChatDiaryEntryPolicy.matchScore(entry, queryCore, queryTerms, now)
                entry to if (historical && entry.supersededBy != null) {
                    score.copy(total = score.total + HISTORICAL_SUPERSEDED_BONUS)
                } else {
                    score
                }
            }
            .filter { (_, score) -> ChatDiaryRecallMatchPolicy.isRecallMatch(score, broad, query) }
            .sortedWith(
                compareByDescending<Pair<ChatDiaryEntry, ChatDiaryMatchScore>> { it.second.total }
                    .thenByDescending { it.first.updatedAt },
            )
            .map(Pair<ChatDiaryEntry, ChatDiaryMatchScore>::first)
            .take(maxItems.coerceIn(1, 6))
            .toList()
    }

    private const val MAX_NORMAL_RECALL_CANDIDATES = 256
    private const val MAX_IMPORTANT_RECALL_CANDIDATES = 64
    private const val IMPORTANT_RECALL_THRESHOLD = 4
    private const val HISTORICAL_SUPERSEDED_BONUS = 36
}
