package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Read-only access to the same retained request ledger used by Settings usage details. */
@Singleton
class LocalUsageUiFacade @Inject constructor(private val usage: DeepSeekUsageTracker) : com.labteto.dshmobile.uicontract.UsageUiPort {
    override val revision: StateFlow<Long> = usage.analyticsRevision
    override fun snapshot() = usage.analyticsSnapshot()
    override fun groupDetail(kind: TokenUsageGroupKind, key: String, modelFilter: String?) =
        usage.analyticsGroupDetail(kind, key, modelFilter = modelFilter)
    override fun sessionSnapshot(sessionId: String): TokenUsageAnalyticsSnapshot = usage.analyticsSessionSnapshot(sessionId)
    override fun taskDetail(runId: String): TokenUsageGroupDetail? = usage.analyticsGroupDetail(TokenUsageGroupKind.TASK, runId)
    override fun turnSummaries(sessionId: String) = usage.analyticsTurnSummaries(sessionId)
    override fun turnDetail(sessionId: String, turnId: String) = usage.analyticsTurnDetail(sessionId, turnId)
    override fun requestDetail(requestId: String): TokenUsageRecord? = usage.analyticsRecord(requestId)
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalUsageUiBindings {
    @dagger.Binds @Singleton
    abstract fun usage(implementation: LocalUsageUiFacade): com.labteto.dshmobile.uicontract.UsageUiPort
}
