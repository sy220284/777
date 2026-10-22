package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.chat.LocalChatMemoryProjectionPort
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekPricingRepository
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.DeepSeekBillingSchedule
import com.labteto.dshmobile.uicontract.UiProviderPricingSnapshot
import com.labteto.dshmobile.uicontract.UiProviderPriceModel
import com.labteto.dshmobile.uicontract.UiProviderPriceTier
import com.labteto.dshmobile.uicontract.PricingUiPort
import com.labteto.dshmobile.uicontract.PricingUiReceipt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import com.labteto.dshmobile.local.TokenUsageAnalyticsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Settings-facing data facade for memory and DeepSeek accounting. */
@Singleton
class LocalSettingsDataFacade @Inject internal constructor(
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val chatMemoryProjection: LocalChatMemoryProjectionPort,
    private val pricing: DeepSeekPricingRepository,
    private val usage: TokenUsageAnalyticsStore,
) : com.labteto.dshmobile.uicontract.MemoryUiPort, PricingUiPort {
    internal val deepSeekPricing: StateFlow<DeepSeekPricingState> = pricing.state
    internal val usageRevision: StateFlow<Long> = usage.revision
    internal fun usageSnapshot(): TokenUsageAnalyticsSnapshot = usage.snapshot()
    internal fun usageGroupDetail(kind: TokenUsageGroupKind, key: String, modelFilter: String?): TokenUsageGroupDetail? = usage.groupDetail(kind, key, modelFilter = modelFilter)
    internal fun usageRecord(requestId: String): TokenUsageRecord? = usage.recordById(requestId)
    override val snapshots: Flow<UiProviderPricingSnapshot> = pricing.state.map {
        it.asUiProviderPricing()
    }
    override fun snapshot(): UiProviderPricingSnapshot = pricing.state.value.asUiProviderPricing()
    override suspend fun refreshOfficial(): PricingUiReceipt = try {
        pricing.refreshFromOfficial()
        pricing.state.value.error?.let(PricingUiReceipt::Failed) ?: PricingUiReceipt.Completed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PricingUiReceipt.Failed(failure.message)
    }
    internal suspend fun refreshDeepSeekPricing() = pricing.refreshFromOfficial()

    override fun memoryPage(query: com.labteto.dshmobile.local.memory.MemoryManagementQuery) = memoryStore.managementPage(query)
    override fun memories(scopes: Set<MemoryScope>, projectId: String?, lineageId: String?): List<MemoryRecord> =
        memoryStore.listActive(allowedScopes = scopes, projectId = projectId, lineageId = lineageId, limit = 100)
    override fun submit(command: com.labteto.dshmobile.uicontract.MemoryUiCommand): com.labteto.dshmobile.uicontract.MemoryUiReceipt = try {
        when (command) {
            is com.labteto.dshmobile.uicontract.MemoryUiCommand.Update ->
                com.labteto.dshmobile.uicontract.MemoryUiReceipt.Updated(updateMemory(command.expected, command.content, command.pinned))
            is com.labteto.dshmobile.uicontract.MemoryUiCommand.Forget ->
                if (forgetMemory(command.expected)) com.labteto.dshmobile.uicontract.MemoryUiReceipt.Forgotten
                else com.labteto.dshmobile.uicontract.MemoryUiReceipt.StaleOrMissing
        }
    } catch (pending: MemoryCleanupPendingException) {
        com.labteto.dshmobile.uicontract.MemoryUiReceipt.CommittedWithWarning(requireNotNull(pending.message))
    } catch (failure: Exception) { com.labteto.dshmobile.uicontract.MemoryUiReceipt.Failed(failure.message) }
    internal fun updateMemory(existing: MemoryRecord, content: String, pinned: Boolean): MemoryRecord {
        val updated = memoryManager.update(existing = existing, content = content, pinned = pinned)
        recoverDiaryAfterMemorySave("记忆已保存")
        return updated
    }

    internal fun forgetMemory(existing: MemoryRecord): Boolean {
        val removed = memoryStore.forget(existing.id, existing.updatedAt)
        if (removed) recoverDiaryAfterMemorySave("记忆已停用")
        return removed
    }
    private fun recoverDiaryAfterMemorySave(savedMessage: String) {
        try {
            chatMemoryProjection.recoverPendingDiaryInvalidations()
        } catch (error: Exception) {
            throw MemoryCleanupPendingException("$savedMessage，关联日记清理待恢复；重开或下次召回时会重试", error)
        }
    }
    override fun memoriesFromSourceMessage(sessionId: String, messageId: String): List<MemoryRecord> =
        memoryStore.listActiveFromMessage(sessionId, messageId)
}

internal fun DeepSeekPricingState.asUiProviderPricing() = UiProviderPricingSnapshot(
    models = models.map { model ->
        UiProviderPriceModel(
            modelId = model.modelId,
            displayName = model.displayName,
            version = model.version,
            peak = UiProviderPriceTier(model.peak.cacheHitCnyPerMillion,
                model.peak.cacheMissCnyPerMillion, model.peak.outputCnyPerMillion),
            offPeak = UiProviderPriceTier(model.offPeak.cacheHitCnyPerMillion,
                model.offPeak.cacheMissCnyPerMillion, model.offPeak.outputCnyPerMillion),
        )
    },
    sourceUrl = sourceUrl,
    lastUpdatedAt = lastUpdatedAt,
    refreshing = refreshing,
    activePeriod = DeepSeekBillingSchedule.periodAt(System.currentTimeMillis()).name,
    error = error,
)

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalMemoryUiBindings {
    @dagger.Binds @Singleton
    abstract fun memory(implementation: LocalSettingsDataFacade): com.labteto.dshmobile.uicontract.MemoryUiPort
    @dagger.Binds @Singleton
    abstract fun providerPricing(implementation: LocalSettingsDataFacade): PricingUiPort
}

private class MemoryCleanupPendingException(message: String, cause: Throwable) : IllegalStateException(message, cause)
