package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecallEvidenceEventTest {
    @Test fun eachAssistantFindsOnlyItsOwnDurableEvidence() {
        val root = Files.createTempDirectory("chat-recall-source").toFile()
        try {
            val log = LocalSessionEventLog(root.resolve("history.jsonl"), Json)
            log.append("chat/recall-used", buildJsonObject {
                put("assistant_message_id", "answer-a")
                put("source_user_message_id", "user-a")
                put("references", JsonArray(listOf(buildJsonObject {
                    put("kind", "fact")
                    put("content", "用户曾约定周末见面")
                    put("source_session_id", "old-session")
                    put("source_message_id", "old-message")
                })))
            })
            log.append("chat/recall-used", buildJsonObject {
                put("assistant_message_id", "answer-b")
                put("references", JsonArray(listOf(buildJsonObject {
                    put("kind", "diary")
                    put("content", "人物曾担心那次误会")
                    put("source_session_id", "story-b")
                    put("source_message_id", "assistant-old")
                })))
            })
            assertEquals(listOf(ChatRecallEvidence(
                kind = "fact",
                content = "用户曾约定周末见面",
                sourceSessionId = "old-session",
                sourceMessageId = "old-message",
            )), readChatRecallEvidence(log, "answer-a"))
            assertEquals("diary", readChatRecallEvidence(log, "answer-b").single().kind)
            assertTrue(readChatRecallEvidence(log, "unknown").isEmpty())
            log.close()
        } finally {
            root.deleteRecursively()
        }
    }
}
