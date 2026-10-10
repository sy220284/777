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

        val activeById = entries.asSequence()
            .filter(ChatDiaryEntry::active)
            .associateBy(ChatDiaryEntry::id)
        fun supersededByValidUpdate(entry: ChatDiaryEntry): Boolean =
            entry.supersededBy?.let(activeById::get)?.let { replacement ->
                ChatDiarySupersessionPolicy.supersedes(
                    entry.copy(supersededBy = null), replacement,
                )
            } == true

        val eligible = entries.asSequence()
            .filter { entry ->
                entry.active &&
                    entry.subjectKey == cleanSubject &&
                    (!supersededByValidUpdate(entry) || historical)
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
            // Keep old, strongly related experiences findable after hundreds of turns.
            // The bounded reserve is query-driven and never widens the final prompt budget.
            val selected = (recent + important).distinctBy(ChatDiaryEntry::id)
            val selectedIds = selected.mapTo(hashSetOf(), ChatDiaryEntry::id)
            val relatedOlder = if (queryTerms.isEmpty()) emptyList() else eligible.asSequence()
                .filter { it.id !in selectedIds }
                .mapNotNull { entry ->
                    val searchable = listOf(
                        entry.event, entry.relationshipMeaning, entry.unresolvedEcho,
                    ).joinToString(" ")
                    if (ChatDiaryEntryPolicy.queryTerms(searchable).none(queryTerms::contains)) {
                        null
                    } else {
                        val score = ChatDiaryEntryPolicy.matchScore(
                            entry, queryCore, queryTerms, now,
                        )
                        entry.takeIf {
                            ChatDiaryRecallMatchPolicy.isRecallMatch(score, broad, query)
                        }?.let { it to score.semantic }
                    }
                }
                .sortedWith(
                    compareByDescending<Pair<ChatDiaryEntry, Int>> { it.second }
                        .thenByDescending { it.first.updatedAt },
                )
                .take(MAX_RELATED_OLD_CANDIDATES)
                .map(Pair<ChatDiaryEntry, Int>::first)
                .toList()
            selected + relatedOlder
        }

        return candidates.asSequence()
            .map { entry ->
                val score = ChatDiaryEntryPolicy.matchScore(entry, queryCore, queryTerms, now)
                entry to if (historical && supersededByValidUpdate(entry)) {
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
    private const val MAX_RELATED_OLD_CANDIDATES = 48
    private const val IMPORTANT_RECALL_THRESHOLD = 4
    private const val HISTORICAL_SUPERSEDED_BONUS = 36
}
