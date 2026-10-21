package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.CHAT_STREAM_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalChatStreamCheckpointWriter
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
    fun strandedMultipleChunksRecoverOnceWithoutModelReplay() {
        val directory = Files.createTempDirectory("chat-stream-recovery").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            var now = 0L
            val writer = LocalChatStreamCheckpointWriter(log, "request-one", { now }, chunkChars = 4)
            writer.append("第一段")
            now = 4_000L
            writer.append("第二段")
            writer.flush()
            val recovered = recoverInterruptedChatReply(log)
            assertNotNull(recovered)
            assertEquals("interrupted-stream:request-one", recovered!!.id)
            assertEquals("【部分回复 · 本轮未完成】\n第一段第二段", recovered.content)
            assertNull(recoverInterruptedChatReply(log))
            val recorded = log.latest("assistant/partial-visible")
            assertNotNull(recorded)
            assertEquals(recovered, decodeTranscriptMessages(recorded!!.data)!!.single())
            assertEquals(1, log.snapshot().count { it.type == "assistant/partial-visible" })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun completedAnswerNeverProducesStalePartial() {
        val directory = Files.createTempDirectory("chat-stream-completed").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            val writer = LocalChatStreamCheckpointWriter(log, "request-two")
            writer.append("生成中")
            writer.flush()
            log.append("assistant/message", buildJsonObject {
                put("role", "assistant")
                put("content", "完整回复")
            })
            assertNull(recoverInterruptedChatReply(log))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun checkpointFromPreviousTurnCannotLeakIntoNextTurn() {
        val directory = Files.createTempDirectory("chat-stream-stale").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            LocalChatStreamCheckpointWriter(log, "old").apply { append("旧的回复"); flush() }
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            assertNull(recoverInterruptedChatReply(log))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun modelRetryRestoresOnlyLastAcceptedAttemptNotStaleChunks() {
        val directory = Files.createTempDirectory("chat-retry-stream").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            log.append("request/provider-attempt", buildJsonObject { put("request_uid", "same") })
            LocalChatStreamCheckpointWriter(log, "same").apply {
                append("第一次"); flush()
            }
            log.append("request/provider-attempt", buildJsonObject { put("request_uid", "same") })
            LocalChatStreamCheckpointWriter(log, "same").apply {
                append("第二次有效回复"); flush()
            }
            val recovered = recoverInterruptedChatReply(log)
            assertNotNull(recovered)
            assertEquals("【部分回复 · 本轮未完成】\n第二次有效回复", recovered!!.content)
            assertNull(recoverInterruptedChatReply(log))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun laterAdmittedAttemptWithoutTextCannotResurrectEarlierAttempt() {
        val directory = Files.createTempDirectory("chat-retry-no-text").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            log.append("request/provider-attempt", buildJsonObject { put("request_uid", "same") })
            LocalChatStreamCheckpointWriter(log, "same").apply { append("旧回答"); flush() }
            log.append("request/provider-attempt", buildJsonObject { put("request_uid", "same") })
            assertNull(recoverInterruptedChatReply(log))
            log.append("request/provider-attempt", buildJsonObject { put("request_uid", "different") })
            assertNull(recoverInterruptedChatReply(log))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun missingCheckpointChunkNeverProducesInventedAssistantText() {
        val directory = Files.createTempDirectory("chat-stream-torn").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            log.append(CHAT_STREAM_CHECKPOINT_EVENT, buildJsonObject {
                put("request_uid", "broken")
                put("chunk_index", 1)
                put("content", "尾部")
            })
            assertNull(recoverInterruptedChatReply(log))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun longStreamStoresIncrementalBoundedChunksInsteadOfSnapshots() {
        val directory = Files.createTempDirectory("chat-stream-long").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("turn/start", buildJsonObject { put("mode", "chat") })
            val writer = LocalChatStreamCheckpointWriter(log, "long", chunkChars = 16)
            val expected = "ab".repeat(200)
            expected.chunked(7).forEach(writer::append)
            writer.flush()
            val rows = log.snapshot().filter { it.type == CHAT_STREAM_CHECKPOINT_EVENT }
            assertEquals(expected, rows.joinToString("") { it.data["content"]!!.toString().trim('"') })
            assertEquals(rows.indices.toList(), rows.map { it.data["chunk_index"].toString().toInt() })
            assertEquals("【部分回复 · 本轮未完成】\n$expected", recoverInterruptedChatReply(log)!!.content)
        } finally { directory.deleteRecursively() }
    }
}
