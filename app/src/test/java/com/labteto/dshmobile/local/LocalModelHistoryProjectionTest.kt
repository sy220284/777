package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
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
}
