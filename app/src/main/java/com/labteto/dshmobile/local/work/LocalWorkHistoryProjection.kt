package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read bounded pages of the sole authoritative event stream, including archived segments. */
internal fun projectLocalWorkHistoryPage(log: LocalSessionEventLog, cursor: LocalWorkHistoryCursor?): LocalWorkHistoryPageUi {
    val generation = log.resetGeneration
    if (log.isClosed || cursor != null && (cursor.logIdentity != log.historyInstanceIdentity || cursor.generation != generation)) {
        return LocalWorkHistoryPageUi(emptyList(), null, invalidated = true)
    }
    val events = log.pageBeforeNewestFirst(cursor?.beforeSequence ?: Long.MAX_VALUE, PAGE_SIZE + 1)
    val page = events.take(PAGE_SIZE)
    val records = page.filter { it.type in HISTORY_TYPES }.map { event ->
        val name = (event.data["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val content = (event.data["content"] as? JsonPrimitive)?.contentOrNull
            ?: event.data["arguments"]?.toString() ?: event.data.toString()
        LocalWorkHistoryRecord(event.sequence, event.type, name,
            com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair(content, 4_000))
    }
    if (log.isClosed || generation != log.resetGeneration) return LocalWorkHistoryPageUi(emptyList(), null, true)
    return LocalWorkHistoryPageUi(records, if (events.size > PAGE_SIZE) {
        LocalWorkHistoryCursor(log.historyInstanceIdentity, generation, page.last().sequence)
    } else null)
}
private const val PAGE_SIZE = 160
private val HISTORY_TYPES = setOf("tool/call", "tool/execution-started", "tool/result", "subagent/tool-result")
