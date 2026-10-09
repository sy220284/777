package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObject

/** Opaque Feature-owned input. Shared compaction never interprets business state. */
internal interface LocalHistorySummaryInput
internal data class LocalRenderedHistorySummary(val summary: String, val modelBlock: String)
internal interface LocalHistorySummaryProvider {
    fun summarize(mode: LocalHistorySummaryMode, omitted: List<JsonObject>, fullHistory: List<JsonObject>,
        maxChars: Int, input: LocalHistorySummaryInput?): LocalRenderedHistorySummary
}
