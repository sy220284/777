package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatHistorySummaryStrategy
import com.labteto.dshmobile.local.work.LocalWorkHistorySummaryStrategy
import com.labteto.dshmobile.local.model.LocalHistorySummaryInput
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalHistorySummaryProvider
import kotlinx.serialization.json.JsonObject

/** Application composition chooses the owner; shared model code sees only the neutral strategy. */
internal object LocalFeatureHistorySummaries : LocalHistorySummaryProvider {
    override fun summarize(mode: LocalHistorySummaryMode, omitted: List<JsonObject>, fullHistory: List<JsonObject>,
        maxChars: Int, input: LocalHistorySummaryInput?) = when (mode) {
        LocalHistorySummaryMode.WORK -> LocalWorkHistorySummaryStrategy.summarize(omitted, fullHistory, maxChars, input)
        LocalHistorySummaryMode.CHAT -> LocalChatHistorySummaryStrategy.summarize(omitted, maxChars)
    }
}
