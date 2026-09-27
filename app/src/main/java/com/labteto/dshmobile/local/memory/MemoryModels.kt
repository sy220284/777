package com.labteto.dshmobile.local.memory

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryScope {
    GLOBAL,
    PROJECT,
    LINEAGE,
}

@Serializable
enum class MemoryKind {
    RULE,
    PREFERENCE,
    FACT,
    DECISION,
    CONSTRAINT,
    STATE,
    SUMMARY,
    RELATIONSHIP_FACT,
    RELATIONSHIP_STATE,
    RELATIONSHIP_PREFERENCE,
}

@Serializable
data class MemoryRecord(
    val id: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val content: String,
    val projectId: String? = null,
    val lineageId: String? = null,
    val sourceSessionId: String? = null,
    /** Stable owner for character-specific relationship memory, e.g. gallery:<id> or persona:<id>. */
    val subjectKey: String? = null,
    val importance: Int = 50,
    val pinned: Boolean = false,
    val active: Boolean = true,
    val supersededBy: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class MemoryDocument(
    val formatVersion: Int = 1,
    val records: List<MemoryRecord> = emptyList(),
)
