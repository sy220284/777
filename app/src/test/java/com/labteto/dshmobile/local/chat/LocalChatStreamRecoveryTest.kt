package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.CHAT_STREAM_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.decodeTranscriptMessages
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalChatStreamRecoveryTest {
    @Test
    fun strandedCheckpointBecomesOneDurableVisiblePartial() {
        val directory = Files.createTempDirectory("chat-stream-recovery").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            log.append(CHAT_STREAM_CHECKPOINT_EVENT, buildJsonObject {
                put("mode", "chat")
                put("request_uid", "request-one")
                put("content", "已交付的部分文本")
            })
            val recovered = recoverInterruptedChatReply(log)
            assertNotNull(recovered)
            assertEquals("interrupted-stream:request-one", recovered!!.id)
            assertEquals("【部分回复 · 本轮未完成】\n已交付的部分文本", recovered.content)
            assertNull(recoverInterruptedChatReply(log))
            val recorded = log.latest("assistant/partial-visible")
            assertNotNull(recorded)
            assertEquals(recovered, decodeTranscriptMessages(recorded!!.data)!!.single())
            assertEquals(1, log.snapshot().count { it.type == "assistant/partial-visible" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completedAnswerNeverProducesStalePartial() {
        val directory = Files.createTempDirectory("chat-stream-completed").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            log.append(CHAT_STREAM_CHECKPOINT_EVENT, buildJsonObject {
                put("mode", "chat")
                put("request_uid", "request-two")
                put("content", "生成中")
            })
            log.append("assistant/message", buildJsonObject {
                put("role", "assistant")
                put("content", "完整回复")
            })
            assertNull(recoverInterruptedChatReply(log))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun checkpointFromPreviousTurnCannotLeakIntoNextTurn() {
        val directory = Files.createTempDirectory("chat-stream-stale").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            log.append(CHAT_STREAM_CHECKPOINT_EVENT, buildJsonObject {
                put("request_uid", "old")
                put("content", "旧的回复")
            })
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            assertNull(recoverInterruptedChatReply(log))
        } finally {
            directory.deleteRecursively()
        }
    }
}
