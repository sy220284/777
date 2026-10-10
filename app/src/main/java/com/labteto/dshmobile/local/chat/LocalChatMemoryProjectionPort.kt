package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.memory.MemorySourceRef
import com.labteto.dshmobile.local.memory.MemoryStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat-owned invalidation boundary invoked when a durable fact is explicitly corrected/deactivated.
 * This only invalidates derived diary summaries with exact matching raw-message provenance.
 * It never rewrites the original Session EventLog or attempts to infer missing source identities.
 */
@Singleton
class LocalChatMemoryProjectionPort @Inject constructor(
    private val persistence: LocalChatPersistence,
    private val memoryStore: MemoryStore,
) {
    @Synchronized
    fun recoverPendingDiaryInvalidations() {
        recoverMemoryDiaryInvalidations(memoryStore, ::invalidateGeneratedDiaryForSources)
    }

    fun invalidateGeneratedDiaryForSources(sources: List<MemorySourceRef>): Int {
        val exact = sources.filter {
            it.sessionId.isNotBlank() && it.messageId.isNotBlank()
        }.distinct()
        return exact.sumOf { source ->
            persistence.diaryStore.invalidateGeneratedFromMessage(source.sessionId, source.messageId)
        }
    }
}

/** Replay is idempotent. A failed write or interrupted process leaves the outbox for the next read/startup. */
internal fun recoverMemoryDiaryInvalidations(
    memoryStore: MemoryStore,
    invalidate: (List<MemorySourceRef>) -> Int,
) {
    memoryStore.pendingSourceInvalidations().forEach { pending ->
        invalidate(pending.pendingSourceInvalidations)
        memoryStore.acknowledgeSourceInvalidation(pending)
    }
    check(memoryStore.pendingSourceInvalidations().isEmpty()) {
        "日记清理期间记忆再次更新，将在下次读取时继续恢复"
    }
}
