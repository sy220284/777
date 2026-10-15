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

