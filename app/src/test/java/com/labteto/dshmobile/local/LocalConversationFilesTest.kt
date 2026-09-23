package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationFilesTest {
    @Test
    fun rebuildsArtifactsAndInvolvedFilesFromDurableEvents() {
        val files = listOf(
            file(".dsh/uploads/input.txt"),
            file("src/A.kt"),
            file("src/B.kt"),
            file("out/report.md"),
        )
        val events = listOf(
            event(
                1,
                "user/message",
                buildJsonObject {
                    put("content", "- 文件：input.txt → .dsh/uploads/input.txt（10 B）")
                },
            ),
            toolCall(2, "read", buildJsonObject { put("path", "src/A.kt") }),
            toolCall(3, "list_files", buildJsonObject { put("path", "src") }),
            event(
                4,
                "tool/result",
                buildJsonObject {
                    put("name", "glob_files")
                    put("content", "src/A.kt\nsrc/B.kt")
                },
            ),
            toolCall(5, "present", buildJsonObject { put("path", "out/report.md") }),
            event(
                6,
                "tool/result",
                buildJsonObject {
                    put("name", "present")
                    put("content", "成果已确认：out/report.md（12 字节）")
                },
            ),
        )

        val index = localConversationFiles(events, files)

        assertEquals(listOf("out/report.md"), index.artifacts.map { it.path })
        assertEquals(
            setOf(".dsh/uploads/input.txt", "src/A.kt", "src/B.kt"),
            index.involved.map { it.path }.toSet(),
        )
        assertFalse(index.involved.any { it.path == "src" })
    }

    @Test
    fun excludesStaleAndEscapingPaths() {
        val files = listOf(file("src/App.kt"))
        val events = listOf(
            toolCall(1, "read", buildJsonObject { put("path", "../secret.txt") }),
            toolCall(2, "read", buildJsonObject { put("path", "deleted.txt") }),
            toolCall(3, "read", buildJsonObject { put("path", "./src/App.kt") }),
        )

        val index = localConversationFiles(events, files)

        assertEquals(listOf("src/App.kt"), index.involved.map { it.path })
        assertTrue(index.artifacts.isEmpty())
    }

    private fun toolCall(
        sequence: Long,
        name: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) = event(
        sequence,
        "tool/call",
        buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        },
    )

    private fun event(
        sequence: Long,
        type: String,
        data: kotlinx.serialization.json.JsonObject,
    ) = LocalSessionEventLog.Event(
        sequence = sequence,
        type = type,
        createdAt = sequence,
        data = data,
    )

    private fun file(path: String) = LocalWorkspaceFile(
        path = path,
        bytes = 12,
        modifiedAt = 1,
    )
}
