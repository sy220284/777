package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFilesTest {
    @Test
    fun executionTouchedFilesNeverBecomeConversationFiles() {
        val nodes = listOf<ChatNode>(
            ToolCallNode(1, "read", "read", """{"file_path":"/work/app/src/Main.kt"}""", 1, 1),
            ToolResultNode(
                2,
                "read",
                JsonArray(emptyList()),
                false,
                1,
                1,
                JsonObject(mapOf("path" to JsonPrimitive("/work/app/src/Main.kt"))),
            ),
            ToolCallNode(3, "edit", "edit", """{"file_path":"src/Main.kt","old_string":"a","new_string":"b"}""", 1, 2),
            ToolResultNode(
                4,
                "edit",
                JsonArray(emptyList()),
                false,
                1,
                2,
                JsonObject(
                    mapOf(
                        "diffs" to JsonArray(
                            listOf(JsonObject(mapOf("path" to JsonPrimitive("/work/app/src/Main.kt")))),
                        ),
                    ),
                ),
            ),
            ToolCallNode(5, "write", "write", """{"file_path":"build/report.md","content":"ok"}""", 1, 3),
            ToolResultNode(6, "write", JsonArray(emptyList()), false, 1, 3),
            ToolCallNode(7, "grep", "grep", """{"pattern":"TODO","path":"src"}""", 1, 4),
            ToolCallNode(8, "fetch", "web_fetch", """{"url":"https://example.test"}""", 1, 5),
            ToolResultNode(
                9,
                "fetch",
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive("工作区文件：.dsh/fetches/fetch-1.txt"),
                            ),
                        ),
                    ),
                ),
                false,
                1,
                5,
            ),
        )

        val index = conversationFileIndex(nodes, "/work/app")

        assertTrue(index.artifacts.isEmpty())
        assertTrue(index.involved.isEmpty())
        assertTrue(index.isEmpty)
    }

    @Test
    fun explicitPresentedDeliverableIsVisible() {
        val nodes = listOf<ChatNode>(
            OtherNode(
                seq = 10,
                type = "deliverables/presented",
                data = JsonObject(
                    mapOf(
                        "files" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "path" to JsonPrimitive("/work/app/output/final.pdf"),
                                        "description" to JsonPrimitive("最终报告"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val index = conversationFileIndex(nodes, "/work/app")

        assertEquals(listOf("output/final.pdf"), index.artifacts.map { it.path })
        assertTrue(index.involved.isEmpty())
    }

    @Test
    fun explicitArtifactToolIsVisibleAsCompatibilityFallback() {
        val nodes = listOf<ChatNode>(
            ToolCallNode(
                1,
                "export",
                "export_file",
                """{"output_path":"output/result.zip"}""",
                1,
                1,
            ),
            ToolResultNode(2, "export", JsonArray(emptyList()), false, 1, 1),
        )

        assertEquals(
            listOf("output/result.zip"),
            conversationFileIndex(nodes, "/work/app").artifacts.map { it.path },
        )
    }

    @Test
    fun rejectsDeliverablesOutsideTheSessionWorkspace() {
        val nodes = listOf<ChatNode>(
            OtherNode(
                seq = 1,
                type = "deliverables/presented",
                data = JsonObject(
                    mapOf(
                        "files" to JsonArray(
                            listOf(
                                JsonObject(mapOf("path" to JsonPrimitive("/etc/passwd"))),
                                JsonObject(mapOf("path" to JsonPrimitive("../secret.txt"))),
                                JsonObject(mapOf("path" to JsonPrimitive("./output/inside.txt"))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("output/inside.txt"),
            conversationFileIndex(nodes, "/work/app").artifacts.map { it.path },
        )
    }
}
