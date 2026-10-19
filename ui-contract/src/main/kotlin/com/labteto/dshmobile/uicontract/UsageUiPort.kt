package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import kotlinx.coroutines.flow.StateFlow

/** Only durable records still held by the Owner's retention policy are paginable. */
data class UiUsageHistoryPage(
    val records: List<TokenUsageRecord>,
    val nextCursor: String?,
    val totalMatching: Int,
)

/** Read-only projections of the existing request ledger; a renderer cannot write usage facts. */
interface UsageUiPort {
    val revision: StateFlow<Long>
    fun snapshot(): TokenUsageAnalyticsSnapshot
    fun sessionSnapshot(sessionId: String): TokenUsageAnalyticsSnapshot
    fun groupDetail(kind: TokenUsageGroupKind, key: String, modelFilter: String? = null): TokenUsageGroupDetail?
    fun taskDetail(runId: String): TokenUsageGroupDetail?
    fun turnSummaries(sessionId: String): Map<String, TokenUsageAggregate>
    fun turnDetail(sessionId: String, turnId: String): TokenUsageGroupDetail?
    fun requestDetail(requestId: String): TokenUsageRecord?
    fun historyPage(model: String?, sinceMillis: Long?, cursor: String?, limit: Int): UiUsageHistoryPage
}
