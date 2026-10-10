package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekPricingRepository
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Settings-facing data facade for memory and DeepSeek accounting. */
@Singleton
class LocalSettingsDataFacade @Inject constructor(
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val pricing: DeepSeekPricingRepository,
    private val usage: DeepSeekUsageTracker,
) {
    internal val deepSeekPricing: StateFlow<DeepSeekPricingState> = pricing.state
    internal val usageRevision: StateFlow<Long> = usage.analyticsRevision
    internal suspend fun refreshDeepSeekPricing() = pricing.refreshFromOfficial()
    internal fun usageSnapshot(): TokenUsageAnalyticsSnapshot = usage.analyticsSnapshot()
    internal fun usageGroupDetail(kind: TokenUsageGroupKind, key: String): TokenUsageGroupDetail? = usage.analyticsGroupDetail(kind, key)
    internal fun usageRecord(requestId: String): TokenUsageRecord? = usage.analyticsRecord(requestId)

    internal fun memories(scopes: Set<MemoryScope>, projectId: String?, lineageId: String?): List<MemoryRecord> =
        memoryStore.listActive(allowedScopes = scopes, projectId = projectId, lineageId = lineageId, limit = 100)
    internal fun updateMemory(existing: MemoryRecord, content: String, pinned: Boolean): MemoryRecord =
        memoryManager.update(existing = existing, content = content, pinned = pinned)
    internal fun forgetMemory(id: String, expectedUpdatedAt: Long): Boolean =
        memoryStore.forget(id, expectedUpdatedAt)
    internal fun memoriesFromSourceMessage(sessionId: String, messageId: String): List<MemoryRecord> =
        memoryStore.listActiveFromMessage(sessionId, messageId)
}
