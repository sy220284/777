package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAgentInboxPersistenceTest {
    @Test
    fun durableSnapshotRoundTripsQueuedInputs() {
        val structured = buildJsonObject {
            put("role", "user")
            put("content", "带结构的补充")
        }
        val pending = listOf(
            QueuedAgentInput(
                content = "补充一",
                memoryInput = "记忆一",
                modelMessage = structured,
                id = "q1",
            ),
            QueuedAgentInput(
                content = "补充二",
                memoryInput = "记忆二",
                id = "q2",
            ),
        )

        val encoded = encodeLocalAgentInboxEvent(
            action = "queued",
            pending = pending,
            affected = listOf(pending.last()),
        )

        assertEquals(pending, decodeLocalAgentInboxPending(encoded))
    }

    @Test
    fun malformedDuplicateIdsFailClosed() {
        val encoded = buildJsonObject {
            put("version", 1)
            put("action", "queued")
            put(
                "pending",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "same")
                            put("content", "一")
                            put("memory_input", "一")
                        },
                        buildJsonObject {
                            put("id", "same")
                            put("content", "二")
                            put("memory_input", "二")
                        },
                    ),
                ),
            )
        }

        assertNull(decodeLocalAgentInboxPending(encoded))
    }
}
