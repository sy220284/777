package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.memory.MemorySourceRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat-owned invalidation boundary invoked when a durable fact is explicitly corrected/deactivated.
 * This only invalidates derived diary summaries with exact matching raw-message provenance.
 * It never rewrites the original Session EventLog or attempts to infer missing source identities.
 */
@Singleton
internal class LocalChatMemoryProjectionPort @Inject constructor(
    private val persistence: LocalChatPersistence,
) {
    fun invalidateGeneratedDiaryForSources(sources: List<MemorySourceRef>): Int {
        val exact = sources.filter {
            it.sessionId.isNotBlank() && it.messageId.isNotBlank()
        }.distinct()
        return exact.sumOf { source ->
            persistence.diaryStore.invalidateGeneratedFromMessage(source.sessionId, source.messageId)
        }
    }
}
