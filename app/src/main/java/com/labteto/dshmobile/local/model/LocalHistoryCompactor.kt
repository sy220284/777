package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.context.LocalContextCheckpointKind
import com.labteto.dshmobile.local.context.buildTrustedContextCheckpointModelMessage
import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class LocalHistoryCompaction(
    val messages: List<JsonObject>,
    val omittedMessages: Int,
    val summary: String,
    val estimatedTokensBefore: Int = 0,
    val estimatedTokensAfter: Int = 0,
)

internal enum class LocalHistorySummaryMode {
    WORK,
    CHAT,
}

internal fun applyOverflowCompaction(
    history: MutableList<JsonObject>,
    compactor: LocalHistoryCompactor,
    summaryMode: LocalHistorySummaryMode,
): LocalHistoryCompaction? {
    val compacted = compactor.compactForOverflow(history.toList(), summaryMode) ?: return null
    history.clear()
    history += compacted.messages
    return compacted
}

/**
 * Keeps a recent verbatim tail while turning the older part of model history into a bounded,
 * extractive summary. The summary only quotes facts already present in the model-visible history;
 * it never asks another model to invent a recap.
 */
internal class LocalHistoryCompactor(
    private val summaries: LocalHistorySummaryProvider,
    private val maxHistoryChars: Int = DEFAULT_MAX_HISTORY_CHARS,
    private val tailChars: Int = DEFAULT_TAIL_CHARS,
    private val maxSummaryChars: Int = DEFAULT_MAX_SUMMARY_CHARS,
) {
    fun compact(
        history: List<JsonObject>,
        budget: LocalHistoryBudget? = null,
        currentChars: Int? = null,
        currentTokens: Int? = null,
        extraTokens: Int = 0,
        summaryMode: LocalHistorySummaryMode = LocalHistorySummaryMode.WORK,
        structuredWorkState: LocalHistorySummaryInput? = null,
    ): LocalHistoryCompaction? {
        val source = durableModelHistorySnapshot(history)
        val sourceChanged = source !== history
        val effectiveMaxHistoryChars = budget?.maxHistoryChars ?: maxHistoryChars
        val effectiveTailChars = budget?.tailChars ?: tailChars
        val effectiveSummaryChars = budget?.maxSummaryChars ?: maxSummaryChars
        val effectiveMaxHistoryTokens = budget?.maxHistoryTokens
        val effectiveTailTokens = budget?.tailTokens
        val encodedChars = if (sourceChanged) source.sumOf { it.toString().length }
            else currentChars ?: source.sumOf { it.toString().length }
        val encodedTokens = if (sourceChanged) source.sumOf { estimateModelTokens(it.toString()) }
            else currentTokens ?: source.sumOf { estimateModelTokens(it.toString()) }
        val extraTokenRatio = effectiveMaxHistoryTokens?.takeIf { it > 0 }?.let {
            extraTokens.toDouble() / it.toDouble()
        } ?: 0.0
        val historyDepth = (source.size.toDouble() / 80.0).coerceIn(0.0, 1.0)
        val triggerRatio = if (budget?.adaptiveCompactionTrigger == false) {
            1.0
        } else {
            (0.92 - historyDepth * 0.08 - extraTokenRatio.coerceIn(0.0, 0.5) * 0.28)
                .coerceIn(0.68, 0.92)
        }
        val charPressure = encodedChars > (effectiveMaxHistoryChars * triggerRatio).toInt()
        val tokenPressure = effectiveMaxHistoryTokens?.let {
            encodedTokens + extraTokens > (it * triggerRatio).toInt()
        } == true
        if (source.size < 3 || (!charPressure && !tokenPressure)) return null

        val leadingSystemCount = source.takeWhile { it["role"].asText() == "system" }.size
        val firstBodyIndex = maxOf(1, leadingSystemCount)
        if (firstBodyIndex >= source.lastIndex) return null

        var start = firstBodyIndex
        var keptChars = 0
        var keptTokens = 0
        for (index in source.lastIndex downTo firstBodyIndex) {
            val encoded = source[index].toString()
            keptChars += encoded.length
            keptTokens += estimateModelTokens(encoded)
            if (
                keptChars > effectiveTailChars ||
                (effectiveTailTokens != null && keptTokens > effectiveTailTokens)
            ) {
                start = (index until source.size).firstOrNull { candidate ->
                    source[candidate]["role"].asText() == "user"
                } ?: index
                break
            }
        }
        // A tail can begin inside a large tool batch. Keep its assistant call and every result
        // together; cutting only by message size would manufacture an orphan tool result.
        if (source.getOrNull(start)?.get("role").asText() == "tool") {
            while (start > firstBodyIndex && source[start - 1]["role"].asText() == "tool") start -= 1
            if (
                start > firstBodyIndex &&
                source[start - 1]["role"].asText() == "assistant"
            ) {
                start -= 1
            }
        }
        if (start <= firstBodyIndex || start >= source.size) return null

        val latestTailSystemIndex = (source.lastIndex downTo firstBodyIndex)
            .firstOrNull { source[it]["role"].asText() == "system" }
        val protectedTailSystem = latestTailSystemIndex
            ?.takeIf { it < start }
            ?.let(source::get)
        val omitted = source.subList(firstBodyIndex, start).filterIndexed { offset, _ ->
            firstBodyIndex + offset != latestTailSystemIndex
        }
        if (omitted.isEmpty()) return null
        val rendered = summaries.summarize(summaryMode, omitted, source, effectiveSummaryChars, structuredWorkState)
        val summary = rendered.summary
        val compacted = buildList {
            addAll(source.take(firstBodyIndex))
            add(
                buildTrustedContextCheckpointModelMessage(summaryMode, rendered.modelBlock),
            )

            addAll(source.drop(start).filterNot { isTrustedContextCheckpointModelMessage(it) })
        }
        val estimatedTokensBefore = encodedTokens + extraTokens
        val estimatedTokensAfter = compacted.sumOf { estimateModelTokens(it.toString()) } + extraTokens
        // Compaction is allowed to change durable history only when it actually releases context.
        // A short older span can be smaller than the extractive summary header itself.
        if (estimatedTokensAfter >= estimatedTokensBefore) return null
        return LocalHistoryCompaction(
            messages = compacted,
            omittedMessages = omitted.size,
            summary = summary,
            estimatedTokensBefore = estimatedTokensBefore,
            estimatedTokensAfter = estimatedTokensAfter,
        )
    }

    fun compactForOverflow(
        history: List<JsonObject>,
        summaryMode: LocalHistorySummaryMode = LocalHistorySummaryMode.WORK,
        structuredWorkState: LocalHistorySummaryInput? = null,
    ): LocalHistoryCompaction? {
        val source = durableModelHistorySnapshot(history)
        if (source.size < 3) return null

        // Preserve the leading system prefix exactly. Request-only context such as persona/profile
        // facts is injected immediately after the base system prompt and must survive emergency
        // recovery. A later system message (for example the current chat turn's dynamic context)
        // stays at its original tail boundary instead of being hoisted ahead of retained history.
        val leadingSystemCount = source.takeWhile { it["role"].asText() == "system" }.size
        val protectedHead = source.take(leadingSystemCount)
        val firstBodyIndex = leadingSystemCount
        if (firstBodyIndex >= source.lastIndex) return null

        val latestTailSystemIndex = (source.lastIndex downTo firstBodyIndex)
            .firstOrNull { source[it]["role"].asText() == "system" }
        val compactableEndExclusive = latestTailSystemIndex ?: source.size
        val compactableBody = source.subList(firstBodyIndex, compactableEndExclusive)
        if (compactableBody.size < 2) return null

        val leadingSystem = protectedHead.firstOrNull() ?: buildJsonObject {
            put("role", "system")
            put("content", "")
        }
        val protectedSuffix = latestTailSystemIndex?.let { source.subList(it, source.size) }.orEmpty()
        val protectedTokens =
            protectedHead.drop(1).sumOf { estimateModelTokens(it.toString()) } +
                protectedSuffix.sumOf { estimateModelTokens(it.toString()) }
        val working = listOf(leadingSystem) + compactableBody
        val encodedChars = working.sumOf { it.toString().length }
        val encodedTokens = working.sumOf { estimateModelTokens(it.toString()) }
        val aggressiveTailChars = minOf(
            tailChars,
            maxOf(1_000, encodedChars / 3),
        )
        val aggressiveTailTokens = maxOf(512, encodedTokens / 3)
        val aggressiveSummaryChars = minOf(
            maxSummaryChars,
            maxOf(1_000, encodedChars / 8),
        )
        val overflowBudget = LocalHistoryBudget(
            maxHistoryChars = 0,
            tailChars = aggressiveTailChars,
            maxSummaryChars = aggressiveSummaryChars,
            maxToolResultChars = 1,
            maxHistoryTokens = null,
            tailTokens = aggressiveTailTokens,
            maxToolResultTokens = 1,
        )
        // Emergency overflow recovery follows the same first step as normal context governance:
        // shrink stale tool payloads before discarding older semantic history.
        val projection = projectStaleToolResults(working, overflowBudget)
        val projectedWorking = projection?.messages ?: working
        val projectedChars = if (projection == null) encodedChars else projectedWorking.sumOf { it.toString().length }
        val projectedTokens = projection?.estimatedTokensAfter ?: encodedTokens
        val compacted = compact(
            history = projectedWorking,
            budget = overflowBudget,
            currentChars = projectedChars,
            currentTokens = projectedTokens,
            extraTokens = protectedTokens,
            summaryMode = summaryMode,
            structuredWorkState = structuredWorkState,
        )
        val recovered = compacted ?: projection ?: return null

        val rebuilt = buildList {
            addAll(protectedHead)
            addAll(recovered.messages.drop(1))
            addAll(protectedSuffix)
        }
        val before = history.sumOf { estimateModelTokens(it.toString()) }
        val after = rebuilt.sumOf { estimateModelTokens(it.toString()) }
        if (after >= before) return null
        return recovered.copy(
            messages = rebuilt,
            estimatedTokensBefore = before,
            estimatedTokensAfter = after,
        )
    }

    private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val DEFAULT_MAX_HISTORY_CHARS = 500_000
        const val DEFAULT_TAIL_CHARS = 240_000
        const val DEFAULT_MAX_SUMMARY_CHARS = 16_000
    }
}
