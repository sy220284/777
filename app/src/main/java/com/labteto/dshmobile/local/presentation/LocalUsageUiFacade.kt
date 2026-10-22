package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.TokenUsageAnalyticsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Read-only access to the same retained request ledger used by Settings usage details. */
@Singleton
class LocalUsageUiFacade @Inject constructor(private val usage: TokenUsageAnalyticsStore) : com.labteto.dshmobile.uicontract.UsageUiPort {
    override val revision: StateFlow<Long> = usage.revision
    override fun snapshot() = usage.snapshot()
    override fun groupDetail(kind: TokenUsageGroupKind, key: String, modelFilter: String?) =
        usage.groupDetail(kind, key, modelFilter = modelFilter)
    override fun sessionSnapshot(sessionId: String): TokenUsageAnalyticsSnapshot = usage.sessionSnapshot(sessionId)
    override fun taskDetail(runId: String): TokenUsageGroupDetail? = usage.groupDetail(TokenUsageGroupKind.TASK, runId)
    override fun turnSummaries(sessionId: String) = usage.turnSummaries(sessionId)
    override fun turnDetail(sessionId: String, turnId: String) = usage.groupDetail(TokenUsageGroupKind.TURN, turnId, sessionId = sessionId)
    override fun requestDetail(requestId: String): TokenUsageRecord? = usage.recordById(requestId)
    override fun historyPage(model: String?, sinceMillis: Long?, cursor: String?, limit: Int) =
        usage.historyPage(model, sinceMillis, cursor, limit)
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalUsageUiBindings {
    @dagger.Binds @Singleton
    abstract fun usage(implementation: LocalUsageUiFacade): com.labteto.dshmobile.uicontract.UsageUiPort
}
