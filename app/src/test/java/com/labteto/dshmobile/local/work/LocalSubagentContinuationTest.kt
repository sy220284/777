package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubagentContinuationTest {
    @Test
    fun historyCheckpointRoundTripsModelHistoryAndClaimedInboxIds() {
        val history = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "只读子代理")
            },
            buildJsonObject {
                put("role", "user")
                put("content", "核查问题")
            },
            buildJsonObject {
                put("role", "assistant")
                put("content", "已定位第一处证据")
            },
        )

        val encoded = encodeLocalSubagentHistoryCheckpoint(
            backgroundJobId = "job-agent",
            agentId = "sa-agent",
            step = 7,
            history = history,
            claimedMessageIds = linkedSetOf("msg-1", "msg-2"),
        )
        val decoded = decodeLocalSubagentHistoryCheckpoint(encoded)

        requireNotNull(decoded)
        assertEquals(history, decoded.history)
        assertEquals(setOf("msg-1", "msg-2"), decoded.claimedMessageIds)
        assertEquals(7, decoded.step)
    }

    @Test
    fun rejectsCheckpointWithMalformedModelHistory() {
        val encoded = buildJsonObject {
            put("version", 1)
            put("background_job_id", "job-agent")
            put("agent_id", "sa-agent")
            put("step", 1)
            put(
                "history",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("content", "缺少 role") })
                },
            )
        }

        assertNull(decodeLocalSubagentHistoryCheckpoint(encoded))
    }
}
