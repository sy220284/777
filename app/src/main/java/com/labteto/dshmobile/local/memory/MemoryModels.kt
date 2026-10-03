package com.labteto.dshmobile.local.memory

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryScope {
    GLOBAL,
    PROJECT,
    LINEAGE,
}

@Serializable
enum class MemoryDisclosure {
    PRIVATE,
    SHAREABLE,
    PUBLIC,
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
data class MemorySourceRef(
    val sessionId: String,
    val messageId: String,
)

@Serializable
data class MemoryRecord(
    val id: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val content: String,
    val projectId: String? = null,
    val lineageId: String? = null,
    val sourceSessionId: String? = null,
    /** Exact user-message sources. Multiple confirmations can keep one memory alive independently. */
    val sourceMessages: List<MemorySourceRef> = emptyList(),
    /**
     * True when this fact also has provenance that cannot be represented by the bounded
     * message-level source list: legacy/unbound writes, non-user-message writes, or older source
     * messages intentionally omitted after the source list reached its cap. Default true keeps old
     * persisted records migration-safe and prevents destructive rollback from dropping a fact whose
     * full provenance is no longer available.
     */
    val hasUnboundSource: Boolean = true,
    /** Stable owner for character-specific relationship memory, e.g. gallery:<id> or persona:<id>. */
    val subjectKey: String? = null,
    /** Disclosure metadata. Exact facts for their owning character remain recallable in group chat; this must not gate factual recall. */
    val disclosure: MemoryDisclosure = MemoryDisclosure.SHAREABLE,
    /** True only when the user explicitly granted or revoked public disclosure. */
    val disclosureExplicit: Boolean = false,
    val importance: Int = 50,
    val pinned: Boolean = false,
    val active: Boolean = true,
    val supersededBy: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class MemoryDocument(
    val formatVersion: Int = 2,
    val records: List<MemoryRecord> = emptyList(),
)
