package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A 1-based inclusive seq span (used by compaction records). */
@Serializable
data class SeqRange(
    @SerialName("start") val start: Int,
    @SerialName("end") val end: Int,
)

/** `compaction/start` payload — marks the start of a compaction (holds the lock). */
@Serializable
data class CompactionStartData(
    @SerialName("compactionId") val compactionId: String,
    @SerialName("sourceCommandId") val sourceCommandId: String? = null,
    /** Open-turn number, or null for a standalone manual transaction between turns. */
    @SerialName("turn") val turn: Int? = null,
)

/** `compaction/summary` payload — completed summary, its inputs, and its model call facts. */
@Serializable
data class CompactionSummaryData(
    @SerialName("compactionId") val compactionId: String,
    @SerialName("sourceCommandId") val sourceCommandId: String? = null,
    @SerialName("summary") val summary: List<ContentBlock> = emptyList(),
    @SerialName("shadowedRange") val shadowedRange: SeqRange,
    @SerialName("shadowedSeqs") val shadowedSeqs: List<Int> = emptyList(),
    @SerialName("shadowedTokenCount") val shadowedTokenCount: Int,
    /** The provider route that wrote the summary. */
    @SerialName("provider") val provider: String,
    /** The model that wrote the summary. */
    @SerialName("model") val model: String,
    @SerialName("maxTokens") val maxTokens: Int? = null,
    /** Provider-reported token usage for the summarization request, when emitted. */
    @SerialName("usage") val usage: TokenUsage? = null,
    /** Complete provider output before the backend's safe summary projection. */
    @SerialName("rawOutput") val rawOutput: List<ContentBlock>? = null,
    /** True when the summary identifies exactly one call through the context's LLM seam. */
    @SerialName("llmStreamCall") val llmStreamCall: Boolean? = null,
)

/** `compaction/end` payload — releases the lock; `error` records an unsuccessful attempt. */
@Serializable
data class CompactionEndData(
    @SerialName("compactionId") val compactionId: String,
    @SerialName("sourceCommandId") val sourceCommandId: String? = null,
    @SerialName("turn") val turn: Int? = null,
    @SerialName("error") val error: String? = null,
)

/** `compaction/prune` payload — shadow price of one model-free prune replacement. */
@Serializable
data class CompactionPruneData(
    @SerialName("shadowedRange") val shadowedRange: SeqRange,
    @SerialName("shadowedSeqs") val shadowedSeqs: List<Int> = emptyList(),
    @SerialName("shadowedTokenCount") val shadowedTokenCount: Int,
)
