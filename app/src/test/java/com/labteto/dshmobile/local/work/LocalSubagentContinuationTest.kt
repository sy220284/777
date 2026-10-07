package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubagentContinuationTest {
    @Test
    fun roundTripsHistoryClaimedMessagesAndStep() {
        val history = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "规则")
            },
            buildJsonObject {
                put("role", "user")
                put("content", "任务")
            },
            buildJsonObject {
                put("role", "assistant")
                put("content", "已分析")
            },
        )

        val encoded = encodeLocalSubagentHistoryCheckpoint(
            backgroundJobId = "job-1",
            agentId = "sa-1",
            step = 7,
            history = history,
            claimedMessageIds = linkedSetOf("msg-1", "msg-2"),
        )
        val restored = decodeLocalSubagentHistoryCheckpoint(encoded)

        requireNotNull(restored)
        assertEquals(history, restored.history)
        assertEquals(setOf("msg-1", "msg-2"), restored.claimedMessageIds)
        assertEquals(7, restored.step)
    }

    @Test
    fun rejectsMalformedHistory() {
        val encoded = buildJsonObject {
            put("version", 1)
            put("background_job_id", "job-1")
            put("agent_id", "sa-1")
            put("step", 1)
            put(
                "history",
                kotlinx.serialization.json.JsonArray(
                    listOf<JsonObject>(
                        buildJsonObject {
                            put("content", "缺少 role")
                        },
                    ),
                ),
            )
        }

        assertNull(decodeLocalSubagentHistoryCheckpoint(encoded))
    }
}
