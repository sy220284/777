package com.labteto.dshmobile.local.memory

/** A management cursor orders facts, never relaxes their visibility scope. */
data class MemoryManagementCursor(val updatedAt: Long, val id: String)

data class MemoryManagementQuery(
    val scopes: Set<MemoryScope>,
    val projectId: String? = null,
    val lineageId: String? = null,
    val sourceSessionId: String? = null,
    val sourceMessageId: String? = null,
    val text: String = "",
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),
    val cursor: MemoryManagementCursor? = null,
    val pageSize: Int = 20,
)

data class MemoryManagementPage(
    val records: List<MemoryRecord> = emptyList(),
    val next: MemoryManagementCursor? = null,
    val totalMatching: Int = 0,
    val kindCounts: Map<MemoryKind, Int> = emptyMap(),
)

/** Search precedes paging. Source focus is exact, as with listActiveFromMessage. */
internal fun queryManagedMemories(records: List<MemoryRecord>, query: MemoryManagementQuery): MemoryManagementPage {
    val sourceFocused = !query.sourceSessionId.isNullOrBlank() && !query.sourceMessageId.isNullOrBlank()
    val matching = records.asSequence().filter(MemoryRecord::active).filter { record ->
        if (sourceFocused) record.sourceMessages.any {
            it.sessionId == query.sourceSessionId && it.messageId == query.sourceMessageId
        } else record.scope in query.scopes && when (record.scope) {
            MemoryScope.GLOBAL -> true
            MemoryScope.PROJECT -> query.projectId != null && record.projectId == query.projectId
            MemoryScope.LINEAGE -> query.lineageId != null && record.lineageId == query.lineageId
        }
    }.filter { query.text.isBlank() || it.content.contains(query.text.trim(), ignoreCase = true) }.toList()
    val selected = matching.filter { it.kind in query.kinds }
        .sortedWith(compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id })
    val cursor = query.cursor
    val remaining = selected.asSequence().filter {
        cursor == null || it.updatedAt < cursor.updatedAt || it.updatedAt == cursor.updatedAt && it.id > cursor.id
    }.take(query.pageSize.coerceIn(1, 100) + 1).toList()
    val page = remaining.take(query.pageSize.coerceIn(1, 100))
    return MemoryManagementPage(page,
        page.lastOrNull()?.takeIf { remaining.size > page.size }?.let { MemoryManagementCursor(it.updatedAt, it.id) },
        selected.size, matching.groupingBy(MemoryRecord::kind).eachCount())
}
