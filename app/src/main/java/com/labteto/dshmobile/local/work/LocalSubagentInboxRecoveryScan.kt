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
        check(page.last().sequence > cursor) {
            "SUBAGENT_RECOVERY_NO_PROGRESS：持久事件日志未推进读取位置，停止续跑避免无限扫描"
        }
        check(page.first().sequence == cursor + 1L) {
            "SUBAGENT_RECOVERY_SEQUENCE_GAP：读取页跳过高水位之前的事件，禁止冒充恢复完成"
        }
        // Append-only logs have contiguous event sequences. A page can silently skip
        // damaged rows or unreadable segments: never mark that recovery as complete.
        var expected = cursor + 1L
        for (event in page) {
            if (event.sequence > scanThrough) {
                check(expected > scanThrough) {
                    "SUBAGENT_RECOVERY_SEQUENCE_GAP：当前页越过固定高水位，仍有未审计事件"
                }
                break
            }
            check(event.sequence == expected) {
                "SUBAGENT_RECOVERY_SEQUENCE_GAP：期望事件序号 $expected，实际 ${event.sequence}；不能丢弃未确认的用户消息"
            }
            expected++
            if (event.type != LOCAL_SUBAGENT_INBOX_CLAIM_EVENT ||
                event.data["background_job_id"]?.jsonPrimitive?.contentOrNull != backgroundJobId
            ) continue
            // A matching claim has already been durably admitted. Skipping a malformed
            // payload would advance the recovery cursor and permanently lose that input.
            val messages = checkNotNull(decodeLocalSubagentInboxClaimedMessages(event.data)) {
                "SUBAGENT_RECOVERY_INVALID_CLAIM：job=$backgroundJobId，事件序号=${event.sequence}，已领取消息损坏；停止续跑以防丢失输入"
            }
            for (message in messages) {
                if (message.id in pendingIds || !seen.add(message.id)) continue
                recovered += message
            }
        }
        val nextCursor = minOf(page.last().sequence, scanThrough)
        check(nextCursor > cursor) {
            "SUBAGENT_RECOVERY_NO_PROGRESS：持久事件日志未推进读取位置，停止续跑避免无限扫描"
        }
        cursor = nextCursor
    }
    return recovered
}
