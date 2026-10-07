package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.editedChatUserModelMessage
import com.labteto.dshmobile.local.chat.buildEditedChatModelHistory
import com.labteto.dshmobile.local.chat.buildDurableChatModelHistory
import com.labteto.dshmobile.local.chat.persistActiveChatTranscript
import com.labteto.dshmobile.local.chat.persistRewrittenChatTranscript
import com.labteto.dshmobile.local.chat.withEditedChatUserBlocks

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.chat.decodeChatBranchStateEvent
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalMessageMediaSource
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatEditSupportTest {
    @Test
    fun editingStructuredUserMessagePreservesMediaBlocks() {
        val image = LocalMessageBlock.Image(
            relativePath = ".dsh/attachments/image.png",
            mediaType = "image/png",
            name = "image.png",
            bytes = 128L,
            attachmentId = "image",
            width = 320,
            height = 240,
            source = LocalMessageMediaSource.USER,
        )
        val file = LocalMessageBlock.File(
            relativePath = ".dsh/attachments/doc.txt",
            mediaType = "text/plain",
            name = "doc.txt",
            bytes = 32L,
            attachmentId = "doc",
        )
        val original = LocalHarnessMessage(
            id = "u-media",
            role = "user",
            content = "原文字",
            createdAt = 1L,
            blocks = listOf(LocalMessageBlock.Text("原文字"), image, file),
        )

        val edited = withEditedChatUserBlocks(original, "新文字")
        assertEquals(LocalMessageBlock.Text("新文字"), edited[0])
        assertEquals(image, edited[1])
        assertEquals(file, edited[2])

        val mediaOnly = withEditedChatUserBlocks(original, "")
        assertEquals(listOf(image, file), mediaOnly)
    }

    @Test
    fun branchHistoryRestoresStructuredAssistantMediaMessage() {
        withLog { log ->
            val assistant = LocalHarnessMessage(
                id = "a-media",
                role = "assistant",
                content = "生成好了",
                createdAt = 2L,
                blocks = listOf(
                    LocalMessageBlock.Text("生成好了"),
                    LocalMessageBlock.Image(
                        relativePath = ".dsh/attachments/generated.png",
                        mediaType = "image/png",
                        name = "generated.png",
                        bytes = 64L,
                        attachmentId = "generated",
                        source = LocalMessageMediaSource.MODEL,
                    ),
                ),
            )
            log.append("assistant/message", buildJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "output_text")
                        put("text", "生成好了")
                    })
                    add(buildJsonObject {
                        put("type", "local_image_ref")
                        put("path", ".dsh/attachments/generated.png")
                        put("mediaType", "image/png")
                    })
                })
                put("marker", "assistant-structured")
                put("transcript", encodeTranscriptMessages(listOf(assistant)))
            })

            val history = buildDurableChatModelHistory(
                eventLog = log,
                messages = listOf(assistant),
                systemPrompt = "system",
            )

            assertEquals("assistant-structured", history[1]["marker"]?.jsonPrimitive?.content)
            val content = history[1]["content"]!!.jsonArray
            assertEquals("local_image_ref", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        }
    }

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
                    LocalHarnessMessage(
                        id = "a1",
                        role = "assistant",
                        content = "第一答",
                        createdAt = 2L,
                    ),
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

    @Test
    fun activeBranchMaterializationDoesNotClearBranchGraph() {
        withLog { log ->
            persistActiveChatTranscript(
                eventLog = log,
                reason = "variant-selected",
                activeTranscript = listOf(message("u-active", "当前分支")),
            )

            val events = log.pageBefore(Long.MAX_VALUE, 10)
            assertEquals(listOf("chat/active-transcript"), events.map { it.type })
        }
    }

    @Test
    fun destructiveHistoryRewriteStillClearsBranchGraph() {
        withLog { log ->
            persistRewrittenChatTranscript(
                eventLog = log,
                reason = "user-edited",
                activeTranscript = listOf(message("u-edited", "改写分支")),
            )

            val events = log.pageBefore(Long.MAX_VALUE, 10)
            assertEquals(
                listOf("chat/branch-state", "chat/active-transcript"),
                events.map { it.type },
            )
            assertEquals(
                0,
                decodeChatBranchStateEvent(events.first().data)?.nodes?.size,
            )
        }
    }

    @Test
    fun branchSelectionHistoryKeepsStructuredUserPayloads() {
        withLog { log ->
            log.append(
                "user/message",
                durableUserEvent(message("u-branch", "看图"), "看图", "branch-structured"),
            )

            val history = buildDurableChatModelHistory(
                eventLog = log,
                messages = listOf(
                    message("u-branch", "看图"),
                    LocalHarnessMessage(
                        id = "a-branch",
                        role = "assistant",
                        content = "看到了",
                        createdAt = 2L,
                    ),
                ),
                systemPrompt = "system",
            )

            assertEquals("branch-structured", history[1]["marker"]?.jsonPrimitive?.content)
            assertEquals("看到了", history[2]["content"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun historicalEditKeepsStructuredQueuedUserMessagePayload() {
        withLog { log ->
            val queued = QueuedAgentInput(
                content = "看这张图",
                memoryInput = "看这张图",
                modelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "看这张图")
                    put("marker", "queued")
                },
                id = "queued-user",
            )
            log.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "queued",
                    pending = listOf(queued),
                    affected = listOf(queued),
                    transcript = listOf(message("queued-user", "看这张图")),
                ),
            )

            val edited = editedChatUserModelMessage(
                eventLog = log,
                originalMessageId = "queued-user",
                content = "重新看这张图",
            )

            assertEquals("重新看这张图", edited["content"]?.jsonPrimitive?.content)
            assertEquals("queued", edited["marker"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun editedHistoryRestoresStructuredQueuedMessages() {
        withLog { log ->
            val queued = QueuedAgentInput(
                content = "排队补充",
                memoryInput = "排队补充",
                modelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "排队补充")
                    put("marker", "queued-prefix")
                },
                id = "queued-prefix",
            )
            log.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "queued",
                    pending = listOf(queued),
                    affected = listOf(queued),
                    transcript = listOf(message("queued-prefix", "排队补充")),
                ),
            )

            val history = buildEditedChatModelHistory(
                eventLog = log,
                messages = listOf(
                    message("queued-prefix", "排队补充"),
                    message("replacement", "修改后的下一句"),
                ),
                groupMode = false,
                editedMessageId = "replacement",
                editedModelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "修改后的下一句")
                },
                systemPrompt = "system",
            )

            assertEquals("queued-prefix", history[1]["marker"]?.jsonPrimitive?.content)
            assertEquals("修改后的下一句", history[2]["content"]?.jsonPrimitive?.content)
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
