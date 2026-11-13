package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelRouteIdentity
import com.labteto.dshmobile.local.usage.saturatingUsageAdd
import kotlinx.serialization.Serializable

/** Canonical read-only usage values; the existing Usage owner keeps the ledger and calculations. */
@Serializable
enum class TokenUsageAction {
    CHAT_REPLY,
    CHAT_REPAIR,
    CHAT_STATE_REFRESH,
    REPLY_SUGGESTIONS,
    UI_STRUCTURE_GENERATION,
    GROUP_REPLY,
    GROUP_STATE_REFRESH,
    GROUP_ANNOUNCEMENT,
    PERSONA_AUTOFILL,
    PERSONA_INSPECTION,
    WORK_MAIN,
    WORK_IMPROVEMENT,
    WORK_SUMMARY,
    WORK_SUBAGENT,
    AUTOMATION,
    AUTOMATION_CHAT,
    WEB_SEARCH,
    VISION,
    OTHER,
}

@Serializable
data class TokenPromptBreakdown(
    val systemBaseTokens: Int = 0,
    val personaStateTokens: Int = 0,
    val memoryRuleTokens: Int = 0,
    val historyTokens: Int = 0,
    val currentUserTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val otherSystemTokens: Int = 0,
) {
    val estimatedInputTokens: Long
        get() = listOf(
            systemBaseTokens,
            personaStateTokens,
            memoryRuleTokens,
            historyTokens,
            currentUserTokens,
            toolDefinitionTokens,
            otherSystemTokens,
        ).fold(0L) { total, value ->
            saturatingUsageAdd(total, value.toLong())
        }
}

@Serializable
data class TokenUsageContext(
    val mode: LocalUsageMode? = null,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val turnId: String? = null,
    val runId: String? = null,
    val parentRunId: String? = null,
    val runKind: String? = null,
    val agentId: String? = null,
    val taskLabel: String? = null,
    val step: Int? = null,
    val action: TokenUsageAction = TokenUsageAction.OTHER,
    val taskRunId: String? = null,
)

data class ForegroundTokenUsageSeed(
    val turnId: String? = null,
    val runId: String? = null,
    val taskLabel: String? = null,
)

@Serializable
data class TokenUsageRecord(
    val requestId: String,
    val timestamp: Long,
    val model: String,
    val context: TokenUsageContext = TokenUsageContext(),
    val route: LocalModelRouteIdentity? = null,
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val estimatedCostCny: Double = 0.0,
    val reported: Boolean = false,
    val promptBreakdown: TokenPromptBreakdown = TokenPromptBreakdown(),
) {
    val totalTokens: Long get() = saturatingUsageAdd(inputTokens, outputTokens)
}

@Serializable
data class TokenUsageAggregate(
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val requestCount: Long = 0L,
    val unreportedRequestCount: Long = 0L,
    val estimatedCostCny: Double = 0.0,
) {
    val totalTokens: Long get() = saturatingUsageAdd(inputTokens, outputTokens)
    val cacheMeasuredTokens: Long get() = saturatingUsageAdd(cacheHitTokens, cacheMissTokens)
    val cacheHitRate: Double
        get() = if (cacheMeasuredTokens <= 0L) 0.0 else cacheHitTokens.toDouble() / cacheMeasuredTokens.toDouble()
}

data class TokenUsageDailyBucket(
    val epochDay: Long,
    val chat: TokenUsageAggregate = TokenUsageAggregate(),
    val work: TokenUsageAggregate = TokenUsageAggregate(),
    val other: TokenUsageAggregate = TokenUsageAggregate(),
) {
    fun forMode(mode: LocalUsageMode): TokenUsageAggregate = when (mode) {
        LocalUsageMode.CHAT -> chat
        LocalUsageMode.WORK -> work
    }
}

data class TokenUsageActionSummary(
    val action: TokenUsageAction,
    val aggregate: TokenUsageAggregate,
)

data class TokenUsageGroupSummary(
    val key: String,
    val title: String,
    val mode: LocalUsageMode,
    val aggregate: TokenUsageAggregate,
    val lastUsedAt: Long,
    val turnCount: Int = 0,
    val mainTokens: Long = 0L,
    val subagentTokens: Long = 0L,
)

data class TokenUsageModeAnalytics(
    val aggregate: TokenUsageAggregate = TokenUsageAggregate(),
    val turnCount: Int = 0,
    val averageInputPerTurn: Long = 0L,
    val averageOutputPerTurn: Long = 0L,
    val averageBackgroundPerTurn: Long = 0L,
    val mainTokens: Long = 0L,
    val subagentTokens: Long = 0L,
    val actions: List<TokenUsageActionSummary> = emptyList(),
)

data class TokenUsageAnalyticsSnapshot(
    val trackedSince: Long = 0L,
    val tracked: TokenUsageAggregate = TokenUsageAggregate(),
    val chat: TokenUsageModeAnalytics = TokenUsageModeAnalytics(),
    val work: TokenUsageModeAnalytics = TokenUsageModeAnalytics(),
    val days: List<TokenUsageDailyBucket> = emptyList(),
    val sessions: List<TokenUsageGroupSummary> = emptyList(),
    val tasks: List<TokenUsageGroupSummary> = emptyList(),
    val recentRecords: List<TokenUsageRecord> = emptyList(),
    /** Per-model views use the same arithmetic and detail projections as the combined view. */
    val byModel: Map<String, TokenUsageAnalyticsSnapshot> = emptyMap(),
    /** Older versions may have pruned records before per-model lifetime totals were introduced. */
    val modelHistoryPartial: Boolean = false,
)

enum class TokenUsageGroupKind {
    SESSION,
    TASK,
    TURN,
}

data class TokenUsageAgentSummary(
    val key: String,
    val title: String,
    val runKind: String,
    val aggregate: TokenUsageAggregate,
)

data class TokenUsageGroupDetail(
    val kind: TokenUsageGroupKind,
    val key: String,
    val title: String,
    val aggregate: TokenUsageAggregate,
    val actions: List<TokenUsageActionSummary>,
    val agents: List<TokenUsageAgentSummary>,
    val records: List<TokenUsageRecord>,
)
