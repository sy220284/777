package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFilesTest {
    @Test
    fun indexesArtifactsAndTouchedFilesWithoutDuplicates() {
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
        )

        val index = conversationFileIndex(nodes, "/work/app")

        assertEquals(listOf("build/report.md"), index.artifacts.map { it.path })
        assertEquals(listOf("src/Main.kt"), index.involved.map { it.path })
    }

    @Test
    fun recognisesToolGeneratedArtifactPathsFromResultText() {
        val nodes = listOf<ChatNode>(
            ToolCallNode(1, "fetch", "web_fetch", """{"url":"https://example.test"}""", 1, 1),
            ToolResultNode(
                2,
                "fetch",
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive("工作区文件：.dsh/fetches/fetch-1.txt\n内容预览：..."),
                            ),
                        ),
                    ),
                ),
                false,
                1,
                1,
            ),
        )

        assertEquals(
            listOf(".dsh/fetches/fetch-1.txt"),
            conversationFileIndex(nodes, "/work/app").artifacts.map { it.path },
        )
    }

    @Test
    fun doesNotTreatSearchRootsAsFiles() {
        val nodes = listOf<ChatNode>(
            ToolCallNode(1, "grep", "grep", """{"pattern":"TODO","path":"src"}""", 1, 1),
            ToolResultNode(
                2,
                "grep",
                JsonArray(emptyList()),
                false,
                1,
                1,
                JsonObject(
                    mapOf(
                        "files" to JsonArray(
                            listOf(JsonObject(mapOf("path" to JsonPrimitive("src/App.kt")))),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("src/App.kt"),
            conversationFileIndex(nodes, "/work/app").involved.map { it.path },
        )
    }

    @Test
    fun rejectsFilesOutsideTheSessionWorkspace() {
        val nodes = listOf<ChatNode>(
            ToolCallNode(1, "outside", "read", """{"file_path":"/etc/passwd"}""", 1, 1),
            ToolCallNode(2, "escape", "read", """{"file_path":"../secret.txt"}""", 1, 2),
            ToolCallNode(3, "inside", "read", """{"file_path":"./src/App.kt"}""", 1, 3),
        )

        assertEquals(
            listOf("src/App.kt"),
            conversationFileIndex(nodes, "/work/app").involved.map { it.path },
        )
    }
}
