package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelHistoryProjectionTest {
    private val codec = ModelHistoryCheckpointCodec()

    @Test
    fun usesLatestValidCheckpointAndReplaysSemanticTail() {
        val old = listOf(message("system", "系统"), message("user", "旧问题"))
        val events = listOf(
            event(0L, ModelHistoryCheckpointCodec.EVENT_TYPE, codec.encode(old, "old")),
            event(1L, "user/message", buildJsonObject { put("content", "新问题") }),
            event(2L, ModelHistoryCheckpointCodec.EVENT_TYPE, buildJsonObject {
                put("version", 999)
                put("messages", "broken")
            }),
            event(3L, "assistant/message", message("assistant", "新回答")),
        )

        val restored = restoreLocalModelHistory(events, emptyList(), codec)

        assertEquals(
            listOf("system", "user", "user", "assistant"),
            restored.messages.map { it["role"].toString().trim('"') },
        )
        assertEquals("新问题", restored.messages[2]["content"].toString().trim('"'))
        assertEquals("新回答", restored.messages[3]["content"].toString().trim('"'))
        assertTrue(restored.replayedTail)
        assertTrue(restored.checkpointRecommended)
        assertFalse(restored.usedLegacyFallback)
    }

    @Test
    fun rebuildsFromSemanticEventsWhenNoCheckpointOrLegacyFallbackExists() {
        val events = listOf(
            event(0L, "system/prompt", buildJsonObject { put("content", "系统") }),
            event(1L, "user/message", buildJsonObject { put("content", "问题") }),
            event(2L, "assistant/message", message("assistant", "回答")),
            event(3L, "tool/result", buildJsonObject {
                put("id", "call-1")
                put("content", "结果")
            }),
        )

        val restored = restoreLocalModelHistory(events, emptyList(), codec)

        assertEquals(listOf("system", "user", "assistant", "tool"), restored.messages.map {
            it["role"].toString().trim('"')
        })
        assertEquals("call-1", restored.messages.last()["tool_call_id"].toString().trim('"'))
        assertTrue(restored.replayedTail)
        assertTrue(restored.checkpointRecommended)
        assertFalse(restored.usedLegacyFallback)
    }

    @Test
    fun structuredUserMessageKeepsDurableImageReference() {
        val structured = buildLocalUserModelMessage(
            visibleText = "看这张图",
            attachments = listOf(
                LocalImportedAttachment(
                    name = "sample.png",
                    relativePath = ".dsh/attachments/abc.png",
                    mediaType = "image/png",
                    bytes = 3,
                    attachmentId = "abc",
                ),
            ),
        )
        val events = listOf(
            event(0L, "user/message", buildJsonObject {
                put("content", "看这张图")
                put("model_message", structured)
            }),
        )

        val restored = restoreLocalModelHistory(events, emptyList(), codec)

        assertEquals(structured, restored.messages.single())
        assertTrue(restored.messages.single()["content"].toString().contains(LOCAL_IMAGE_REF))
    }


    @Test
    fun unconsumedQueuedUserMessageIsNotReplayedAfterRestart() {
        val checkpoint = listOf(message("system", "系统"))
        val events = listOf(
            event(0L, ModelHistoryCheckpointCodec.EVENT_TYPE, codec.encode(checkpoint, "before-queue")),
            event(1L, "user/message", buildJsonObject {
                put("content", "已经取消的补充消息")
                put("queued", true)
            }),
            event(2L, "user/queue", buildJsonObject {
                put("action", "cancelled")
                put("count", 1)
            }),
        )

        val restored = restoreLocalModelHistory(events, emptyList(), codec)

        assertEquals(checkpoint, restored.messages)
        assertFalse(restored.replayedTail)
    }

    @Test
    fun legacyFallbackIsUsedWithoutReplayingUnknownOverlap() {
        val fallback = listOf(
            message("system", "旧系统"),
            message("user", "已在旧快照里的问题"),
        )
        val events = listOf(
            event(0L, "user/message", buildJsonObject { put("content", "已在旧快照里的问题") }),
            event(1L, "assistant/message", message("assistant", "可能也已在旧快照里")),
        )

        val restored = restoreLocalModelHistory(events, fallback, codec)

        assertEquals(fallback, restored.messages)
        assertFalse(restored.replayedTail)
        assertTrue(restored.usedLegacyFallback)
        assertTrue(restored.checkpointRecommended)
    }

    @Test
    fun toolReplayPrefersExactModelVisibleContentOverAuditContent() {
        val events = listOf(
            event(0L, "tool/result", buildJsonObject {
                put("id", "call-exact")
                put("content", "审计保留的原始截断内容")
                put("model_content", "模型实际看到的压缩内容")
            }),
        )

        val restored = restoreLocalModelHistory(events, emptyList(), codec)

        assertEquals(1, restored.messages.size)
        assertEquals(
            "模型实际看到的压缩内容",
            restored.messages.single()["content"].toString().trim('"'),
        )
    }

    @Test
    fun duplicateToolResultIsNotReapplied() {
        val checkpoint = listOf(
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "call-1")
                put("content", "原结果")
            },
        )
        val events = listOf(
            event(0L, ModelHistoryCheckpointCodec.EVENT_TYPE, codec.encode(checkpoint, "tool")),
            event(1L, "tool/result", buildJsonObject {
                put("id", "call-1")
                put("content", "重复结果")
            }),
        )

        val restored = restoreLocalModelHistory(events, emptyList(), codec)

        assertEquals(1, restored.messages.size)
        assertFalse(restored.replayedTail)
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun event(sequence: Long, type: String, data: JsonObject) = LocalSessionEventLog.Event(
        sequence = sequence,
        type = type,
        createdAt = 1L,
        data = data,
    )

    @Test
    fun durableInboxQueuedMessageStaysHiddenUntilClaimed() {
        val structured = message("user", "持久补充消息")
        val queuedEvent = event(
            0L,
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "queued",
                pending = listOf(
                    com.labteto.dshmobile.harness.agent.QueuedAgentInput(
                        content = "持久补充消息",
                        modelMessage = structured,
                        id = "q1",
                    ),
                ),
            ),
        )
        val claimedEvent = event(
            1L,
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "claimed",
                pending = emptyList(),
                affected = listOf(
                    com.labteto.dshmobile.harness.agent.QueuedAgentInput(
                        content = "持久补充消息",
                        modelMessage = structured,
                        id = "q1",
                    ),
                ),
                modelMessages = listOf(structured),
            ),
        )

        assertEquals(
            emptyList<JsonObject>(),
            restoreLocalModelHistory(listOf(queuedEvent), emptyList(), codec).messages,
        )
        assertEquals(
            listOf(structured),
            restoreLocalModelHistory(listOf(queuedEvent, claimedEvent), emptyList(), codec).messages,
        )
    }

    @Test
    fun consumedAndResumedQueuedMessagesReplayWithoutFullCheckpoint() {
        for (action in listOf("consumed", "resumed")) {
            val structured = message("user", "补充消息-$action")
            val events = listOf(
                event(0L, "user/message", buildJsonObject {
                    put("content", "补充消息-$action")
                    put("queued", true)
                    put("model_message", structured)
                }),
                event(1L, "user/queue", buildJsonObject {
                    put("action", action)
                    put("model_messages", JsonArray(listOf(structured)))
                }),
            )

            val restored = restoreLocalModelHistory(events, emptyList(), codec)

            assertEquals(listOf(structured), restored.messages)
            assertTrue(restored.replayedTail)
        }
    }

}
