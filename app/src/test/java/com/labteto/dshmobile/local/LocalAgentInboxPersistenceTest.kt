package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind
import com.labteto.dshmobile.harness.agent.AgentContinuationRef
import com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
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
    fun internalWorkContinuationSurvivesQueueEventRecovery() {
        val input = QueuedAgentInput(
            content = "resume",
            memoryInput = "",
            modelMessage = buildJsonObject {
                put("role", "system")
                put("content", "resume")
            },
            id = "continuation-run-1",
            kind = QueuedAgentInputKind.WORK_CONTINUATION,
            continuation = AgentContinuationRef("run-1", 177L, "MODEL_STREAM_STALLED", 1, "message-42"),
        )
        val encoded = encodeLocalAgentInboxEvent("queued", listOf(input))
        assertEquals(listOf(input), decodeLocalAgentInboxPending(encoded))
        val recovered = decodeLocalAgentInboxPending(encoded)?.single()
        assertEquals(QueuedAgentInputKind.WORK_CONTINUATION, recovered?.kind)
        assertEquals(177L, recovered?.continuation?.checkpointSequence)
        assertEquals("run-1", recovered?.continuation?.sourceRunId)
        assertEquals("message-42", recovered?.continuation?.sourceMessageId)
    }

    @Test
    fun malformedInputKindDoesNotBecomeUserRequest() {
        val payload = buildJsonObject {
            put("version", 1)
            put("action", "queued")
            put("pending", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("id", "continuation-unknown")
                put("content", "resume")
                put("kind", "UNKNOWN_EXECUTION_ORIGIN")
            })))
        }
        assertNull(decodeLocalAgentInboxPending(payload))
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
