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
    // Paginate *relevant* records, not an arbitrary 161 raw rows that can be filled
    // entirely by unrelated chat/session events. Scan in bounded batches and advance
    // the cursor over every consumed row, including non-history events.
    val page = mutableListOf<LocalSessionEventLog.Event>()
    var before = cursor?.beforeSequence ?: Long.MAX_VALUE
    var rawScanned = 0
    var exhausted = false
    while (page.size < PAGE_SIZE && rawScanned < MAX_RAW_SCAN_PER_PAGE) {
        val requested = minOf(RAW_BATCH_SIZE, MAX_RAW_SCAN_PER_PAGE - rawScanned)
        val batch = log.pageBeforeNewestFirst(before, requested)
        if (batch.isEmpty()) {
            exhausted = true
            break
        }
        for (event in batch) {
            before = event.sequence
            rawScanned++
            if (event.type in HISTORY_TYPES) page += event
            if (page.size >= PAGE_SIZE) break
        }
        if (page.size >= PAGE_SIZE) break
        if (batch.size < requested) {
            exhausted = true
            break
        }
    }
    val records = page.map { event ->
        val review = localWorkReviewHistoryDescription(event.type,
            (event.data["verdict"] as? JsonPrimitive)?.contentOrNull)
        val name = if (review != null) event.type else
            (event.data["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // Do not stringify model reports, Git diffs or credential-bearing event JSON into the UI.
        val content = review ?: (event.data["content"] as? JsonPrimitive)?.contentOrNull
            ?: event.data["arguments"]?.toString() ?: event.data.toString()
        LocalWorkHistoryRecord(event.sequence, event.type, name,
            com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair(content, 4_000),
            (event.data["id"] as? JsonPrimitive)?.contentOrNull, projectLocalWorkArtifacts(listOf(event)),
            phase = com.labteto.dshmobile.local.tools.reduceLocalToolActivity(
                com.labteto.dshmobile.local.tools.LocalToolActivityState(),
                com.labteto.dshmobile.harness.session.SessionEvent(event.sequence,
                    if (event.type == "subagent/tool-result") "tool/result" else event.type, event.createdAt, event.data),
            ).activities.lastOrNull()?.phase?.let { com.labteto.dshmobile.local.presentation.LocalToolUiPhase.valueOf(it.name) })
    }
    if (log.isClosed || generation != log.resetGeneration) return LocalWorkHistoryPageUi(emptyList(), null, true)
    val older = if (exhausted || rawScanned == 0) null else
        LocalWorkHistoryCursor(log.historyInstanceIdentity, generation, before)
    return LocalWorkHistoryPageUi(records, older)
}
private const val PAGE_SIZE = 160
private const val RAW_BATCH_SIZE = 192
private const val MAX_RAW_SCAN_PER_PAGE = 4_096
private val HISTORY_TYPES = setOf("tool/call", "tool/execution-started", "tool/result", "subagent/tool-result") +
    setOf("work/code-review-start", "work/code-review-result", "work/code-review-accepted",
        "work/security-review-start", "work/security-review-result", "work/security-review-approved")

/** Pure, non-secret receipt wording; results and human approval remain separate facts. */
internal fun localWorkReviewHistoryDescription(type: String, verdict: String?): String? = when (type) {
    "work/code-review-start" -> "独立代码审查已发起"
    "work/code-review-result" -> "模型已生成代码审查报告，等待用户确认"
    "work/code-review-accepted" -> "用户已接纳该版本的代码审查结果"
    "work/security-review-start" -> "独立安全审查已发起"
    "work/security-review-result" -> when (verdict) {
        "allow" -> "安全审查模型结论：允许，仍需用户确认"
        "block" -> "安全审查模型结论：阻止，需修复风险后重新审查"
        else -> "安全审查模型结论尚未确认"
    }
    "work/security-review-approved" -> "用户已批准该版本的安全审查结果"
    else -> null
}
