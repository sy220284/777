package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalConversationFilesCoordinator
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import kotlinx.serialization.json.Json
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.session.localConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
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

        val index = localConversationFiles(events.asSequence(), files)

        assertEquals(listOf("out/report.md"), index.artifacts.map { it.path })
        assertEquals(
            setOf(".dsh/uploads/input.txt", "src/A.kt", "src/B.kt"),
            index.involved.map { it.path }.toSet(),
        )
        assertFalse(index.involved.any { it.path == "src" })
    }

    @Test
    fun conversationIndexResolvesOnlyReferencedFiles() {
        val paths = mapOf("任务/资料/说明.md" to file("任务/资料/说明.md"))
        val lookups = mutableListOf<String>()
        val events = sequenceOf(
            toolCall(1, "read", buildJsonObject { put("path", "任务/资料/说明.md") }),
            toolCall(2, "present", buildJsonObject { put("path", "任务/不存在.md") }),
        )
        val index = localConversationFiles(events) { path ->
            lookups += path
            paths[path]
        }
        assertEquals(listOf("任务/资料/说明.md"), index.involved.map { it.path })
        assertTrue(index.artifacts.isEmpty())
        assertEquals(setOf("任务/资料/说明.md", "任务/不存在.md"), lookups.toSet())
    }

    @Test
    fun conversationCacheTracksReferencedFileAppearAndDisappearWithoutGlobalScans() {
        val root = Files.createTempDirectory("conversation-files-index").toFile()
        try {
            val workspace = LocalWorkspace(root.resolve("work"))
            val log = LocalSessionEventLog(root.resolve("session.events.jsonl"), Json)
            log.append("tool/call", buildJsonObject {
                put("name", "read")
                put("arguments", buildJsonObject { put("path", "任务/待生成资料.md") })
            })
            val coordinator = LocalConversationFilesCoordinator(
                workspaceFilesProvider = { error("会话文件不能扫描整个工作区") },
                workspaceFileAt = workspace::workspaceFileAt,
                previewWorkspaceFile = workspace::preview,
                eventLogFor = { log },
                currentSessionId = { "current" },
                currentEventLog = { log },
            )
            assertTrue(coordinator.conversationFiles("current").involved.isEmpty())
            workspace.write("任务/待生成资料.md", "内容")
            assertEquals(listOf("任务/待生成资料.md"),
                coordinator.conversationFiles("current").involved.map { it.path })
            root.resolve("work/任务/待生成资料.md").delete()
            assertTrue(coordinator.conversationFiles("current").involved.isEmpty())
            log.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun excludesStaleAndEscapingPaths() {
        val files = listOf(file("src/App.kt"))
        val events = listOf(
            toolCall(1, "read", buildJsonObject { put("path", "../secret.txt") }),
            toolCall(2, "read", buildJsonObject { put("path", "deleted.txt") }),
            toolCall(3, "read", buildJsonObject { put("path", "./src/App.kt") }),
        )

        val index = localConversationFiles(events.asSequence(), files)

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

    @Test
    fun activeTranscriptBoundaryDropsFilesFromDiscardedFuture() {
        val files = listOf(
            file(".dsh/uploads/old.txt"),
            file(".dsh/uploads/kept.txt"),
            file("out/old-report.md"),
            file("src/After.kt"),
        )
        val keptMessage = LocalHarnessMessage(
            id = "u-kept",
            role = "user",
            content = "- 文件：kept.txt → .dsh/uploads/kept.txt（10 B）",
            createdAt = 3L,
        )
        val events = listOf(
            event(
                1,
                "user/message",
                buildJsonObject {
                    put("content", "- 文件：old.txt → .dsh/uploads/old.txt（10 B）")
                },
            ),
            toolCall(2, "present", buildJsonObject { put("path", "out/old-report.md") }),
            event(
                3,
                "chat/active-transcript",
                buildJsonObject {
                    put("reason", "user-edited")
                    put("transcript", encodeTranscriptMessages(listOf(keptMessage)))
                },
            ),
            toolCall(4, "read", buildJsonObject { put("path", "src/After.kt") }),
        )

        val index = localConversationFiles(events.asSequence(), files)

        assertEquals(setOf(".dsh/uploads/kept.txt", "src/After.kt"), index.involved.map { it.path }.toSet())
        assertTrue(index.artifacts.isEmpty())
        assertFalse(index.involved.any { it.path == ".dsh/uploads/old.txt" })
        assertFalse(index.artifacts.any { it.path == "out/old-report.md" })
    }

}
