package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.model.LocalHistoryCompaction
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun recordSubagentCompaction(
    eventLog: LocalSessionEventLog,
    subagentId: String,
    compaction: LocalHistoryCompaction,
) {
    eventLog.append("subagent/compaction", buildJsonObject {
        put("agent_id", subagentId)
        put("omitted_messages", compaction.omittedMessages)
        put("summary", compaction.summary)
        put("estimated_tokens_before", compaction.estimatedTokensBefore)
        put("estimated_tokens_after", compaction.estimatedTokensAfter)
    })
}
