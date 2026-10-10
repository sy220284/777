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
class LocalUsageUiFacade @Inject constructor(private val usage: DeepSeekUsageTracker) {
    internal val revision: StateFlow<Long> = usage.analyticsRevision
    internal fun sessionSnapshot(sessionId: String): TokenUsageAnalyticsSnapshot = usage.analyticsSessionSnapshot(sessionId)
    internal fun taskDetail(runId: String): TokenUsageGroupDetail? = usage.analyticsGroupDetail(TokenUsageGroupKind.TASK, runId)
    internal fun requestDetail(requestId: String): TokenUsageRecord? = usage.analyticsRecord(requestId)
}
