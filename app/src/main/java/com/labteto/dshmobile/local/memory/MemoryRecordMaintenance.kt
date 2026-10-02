package com.labteto.dshmobile.local.memory

internal fun compactMemoryRecords(
    records: List<MemoryRecord>,
    maxRecords: Int,
    protectedIds: Set<String> = emptySet(),
): List<MemoryRecord> {
    if (maxRecords <= 0 || records.isEmpty()) return emptyList()
    if (records.size <= maxRecords) return records

    val predecessors = records
        .asSequence()
        .filter { !it.supersededBy.isNullOrBlank() }
        .groupBy { it.supersededBy!! }
        .mapValues { (_, values) -> values.sortedByDescending(MemoryRecord::updatedAt) }
    val activeRoots = records
        .filter(MemoryRecord::active)
        .sortedWith(
            compareByDescending<MemoryRecord> { if (it.id in protectedIds) 1 else 0 }
                .thenByDescending { if (it.pinned) 1 else 0 }
                .thenByDescending(MemoryRecord::importance)
                .thenByDescending(MemoryRecord::updatedAt),
        )

    val selected = linkedSetOf<String>()
    val selectedRoots = mutableListOf<String>()
    activeRoots.forEach { root ->
        val nearestPredecessor = predecessors[root.id].orEmpty().firstOrNull()
        val required = 1 + if (nearestPredecessor != null && nearestPredecessor.id !in selected) 1 else 0
        if (selected.size + required > maxRecords) return@forEach
        selected += root.id
        selectedRoots += root.id
        nearestPredecessor?.let { selected += it.id }
    }
    if (selected.isEmpty()) {
        activeRoots.firstOrNull()?.let { selected += it.id }
    }

    var frontier = (selectedRoots + selected).distinct()
    val traversed = mutableSetOf<String>()
    while (frontier.isNotEmpty() && selected.size < maxRecords) {
        val nextFrontier = mutableListOf<String>()
        frontier.forEach { successorId ->
            if (!traversed.add(successorId)) return@forEach
            predecessors[successorId].orEmpty().forEach { predecessor ->
                if (selected.size >= maxRecords) return@forEach
                if (selected.add(predecessor.id)) {
                    nextFrontier += predecessor.id
                } else if (predecessor.id !in traversed) {
                    nextFrontier += predecessor.id
                }
            }
        }
        frontier = nextFrontier
    }
    return records.filter { it.id in selected }
}

internal data class MemoryConsolidationResult(
    val records: List<MemoryRecord>,
    val mergedCount: Int,
)

internal fun consolidateMemoryRecords(
    records: List<MemoryRecord>,
    maxSourceMessages: Int = 16,
): MemoryConsolidationResult {
    if (records.size < 2) return MemoryConsolidationResult(records, 0)

    data class Key(
        val scope: MemoryScope,
        val projectId: String?,
        val lineageId: String?,
        val subjectKey: String?,
        val kind: MemoryKind,
        val content: String,
    )

    val duplicateGroups = records.asSequence()
        .filter(MemoryRecord::active)
        .groupBy { record ->
            Key(
                scope = record.scope,
                projectId = record.projectId,
                lineageId = record.lineageId,
                subjectKey = record.subjectKey,
                kind = record.kind,
                content = canonicalMemoryIdentity(record.content),
            )
        }
        .values
        .filter { group -> group.size > 1 && canonicalMemoryIdentity(group.first().content).isNotBlank() }
        .toList()
    if (duplicateGroups.isEmpty()) return MemoryConsolidationResult(records, 0)

    val loserToWinner = mutableMapOf<String, String>()
    val mergedWinners = mutableMapOf<String, MemoryRecord>()
    duplicateGroups.forEach { group ->
        val winner = group.maxWith(
            compareBy<MemoryRecord> { it.updatedAt }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        group.filter { it.id != winner.id }.forEach { loser -> loserToWinner[loser.id] = winner.id }
        val allMergedSources = group.asSequence()
            .flatMap { it.sourceMessages.asSequence() }
            .distinct()
            .toList()
        val boundedSourceLimit = maxSourceMessages.coerceAtLeast(1)
        val sourcesTruncated = allMergedSources.size > boundedSourceLimit
        val mergedSources = allMergedSources.takeLast(boundedSourceLimit)
        mergedWinners[winner.id] = winner.copy(
            sourceSessionId = winner.sourceSessionId
                ?: group.asSequence().sortedByDescending(MemoryRecord::updatedAt)
                    .mapNotNull(MemoryRecord::sourceSessionId)
                    .firstOrNull(),
            sourceMessages = mergedSources,
            hasUnboundSource = group.any(MemoryRecord::hasUnboundSource) || sourcesTruncated,
            importance = group.maxOf(MemoryRecord::importance),
            pinned = group.any(MemoryRecord::pinned),
            createdAt = group.minOf(MemoryRecord::createdAt),
            updatedAt = group.maxOf(MemoryRecord::updatedAt),
        )
    }

    val merged = records.mapNotNull { record ->
        if (record.id in loserToWinner) {
            null
        } else {
            val winner = mergedWinners[record.id] ?: record
            val successor = winner.supersededBy?.let { loserToWinner[it] ?: it }
            if (successor == winner.supersededBy) winner else winner.copy(supersededBy = successor)
        }
    }
    return MemoryConsolidationResult(
        records = merged,
        mergedCount = loserToWinner.size,
    )
}

internal fun canonicalMemoryIdentity(text: String): String =
    text.lowercase()
        .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
        .take(2_000)
