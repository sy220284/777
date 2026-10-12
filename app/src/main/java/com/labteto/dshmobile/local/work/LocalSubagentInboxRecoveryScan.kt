package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A recovery scan has a fixed high-water event sequence. Page-count caps are unnecessary:
 * every successful page advances strictly, and future events cannot extend the scan.
 * Missing/invalid pages are an explicit recovery blocker, never a silently omitted message.
 */
internal fun recoverSubagentInboxClaims(
    backgroundJobId: String,
    checkpointSequence: Long,
    scanThrough: Long,
    alreadyClaimed: Set<String>,
    pendingIds: Set<String>,
    pageAfter: (Long) -> List<LocalSessionEventLog.Event>,
): List<QueuedAgentInput> {
    if (checkpointSequence >= scanThrough) return emptyList()
    val recovered = mutableListOf<QueuedAgentInput>()
    val seen = alreadyClaimed.toMutableSet()
    var cursor = checkpointSequence
    while (cursor < scanThrough) {
        val page = pageAfter(cursor)
        check(page.isNotEmpty()) {
            "SUBAGENT_RECOVERY_HISTORY_GAP：持久事件日志缺少待恢复区间；停止续跑以防遗漏用户消息"
        }
        for (event in page) {
            if (event.sequence > scanThrough) break
            if (event.type != LOCAL_SUBAGENT_INBOX_CLAIM_EVENT ||
                event.data["background_job_id"]?.jsonPrimitive?.contentOrNull != backgroundJobId
            ) continue
            val messages = decodeLocalSubagentInboxClaimedMessages(event.data) ?: continue
            for (message in messages) {
                if (message.id in pendingIds || !seen.add(message.id)) continue
                recovered += message
            }
        }
        val nextCursor = page.last().sequence
        check(nextCursor > cursor) {
            "SUBAGENT_RECOVERY_NO_PROGRESS：持久事件日志未推进读取位置，停止续跑避免无限扫描"
        }
        cursor = nextCursor
    }
    return recovered
}
