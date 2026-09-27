package com.labteto.dshmobile.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatEditSupportTest {
    @Test
    fun historicalEditLookupPagesPastRecentEventChunk() {
        withLog { log ->
            log.append(
                "user/message",
                durableUserEvent(message("old-user", "原问题"), "原问题", "old"),
            )
            repeat(230) { index ->
                log.append("step/end", buildJsonObject { put("index", index) })
            }

            val edited = editedChatUserModelMessage(
                eventLog = log,
                originalMessageId = "old-user",
                content = "修改后的问题",
            )

            assertEquals("修改后的问题", edited["content"]?.jsonPrimitive?.content)
            assertEquals("old", edited["marker"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun editedHistoryRestoresStructuredUserMessagesAcrossEventPages() {
        withLog { log ->
            log.append(
                "user/message",
                durableUserEvent(message("u1", "第一问"), "第一问", "first"),
            )
            repeat(230) { index ->
                log.append("request/header", buildJsonObject { put("index", index) })
            }
            log.append(
                "user/message",
                durableUserEvent(message("u2", "第二问"), "第二问", "second"),
            )

            val history = buildEditedChatModelHistory(
                eventLog = log,
                messages = listOf(
                    message("u1", "第一问"),
                    LocalHarnessMessage("a1", "assistant", "第一答", 2L),
                    message("u2", "第二问"),
                ),
                groupMode = false,
                editedMessageId = "replacement",
                editedModelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "replacement")
                },
                systemPrompt = "system",
            )

            assertEquals("first", history[1]["marker"]?.jsonPrimitive?.content)
            assertEquals("second", history[3]["marker"]?.jsonPrimitive?.content)
        }
    }

    private fun durableUserEvent(
        message: LocalHarnessMessage,
        modelText: String,
        marker: String,
    ) = buildJsonObject {
        put("transcript", encodeTranscriptMessages(listOf(message)))
        put("model_message", buildJsonObject {
            put("role", "user")
            put("content", modelText)
            put("marker", marker)
        })
    }

    private fun message(id: String, content: String) = LocalHarnessMessage(
        id = id,
        role = "user",
        content = content,
        createdAt = 1L,
    )

    private fun withLog(block: (LocalSessionEventLog) -> Unit) {
        val root = createTempDir(prefix = "chat-edit-support-")
        try {
            block(
                LocalSessionEventLog(
                    file = File(root, "session.events.jsonl"),
                    json = Json { ignoreUnknownKeys = true },
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
