package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.LOCAL_FILE_REF
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileAttachmentMultimodalTest {
    @Test
    fun officialResponsesRouteSendsNativePdfWithoutReplayingItToHistory() = runTest {
        val root = kotlin.io.path.createTempDirectory("siwc-file-").toFile()
        try {
            val file = File(root, ".dsh/attachments/manual.pdf").apply {
                parentFile?.mkdirs()
                writeBytes("%PDF-1.4\\n1 0 obj\\n<< >>\\nendobj".toByteArray())
            }
            val source = buildLocalUserModelMessage(
                "总结附件", listOf(LocalImportedAttachment(
                    name = "manual.pdf", relativePath = file.relativeTo(root).invariantSeparatorsPath,
                    mediaType = "application/pdf", bytes = file.length(),
                )),
            )
            val prepared = prepareLocalMultimodalMessages(
                messages = listOf(source), workspaceRoot = root,
                mode = LocalImageInputMode.NATIVE, model = "gpt-6-astra",
                baseUrl = "https://api.openai.com/v1", planSharing = true,
            )
            val part = ((prepared.single()["content"] as JsonArray)[1] as JsonObject)
            assertEquals("input_file", part["type"]?.jsonPrimitive?.content)
            assertTrue(part["file_data"]!!.jsonPrimitive.content.startsWith("data:application/pdf;base64,"))
            assertFalse(source.toString().contains("base64"))
            assertFalse(com.labteto.dshmobile.local.model.redactModelImages(prepared).toString().contains("base64"))
            val older = prepareLocalMultimodalMessages(
                messages = listOf(source, buildJsonObject { put("role", "assistant"); put("content", "好了") },
                    buildJsonObject { put("role", "user"); put("content", "继续") }),
                workspaceRoot = root, mode = LocalImageInputMode.NATIVE,
                model = "gpt-6-astra", baseUrl = "https://api.openai.com/v1", planSharing = true,
            )
            assertFalse(older.first().toString().contains("base64"))
            val fallback = prepareLocalMultimodalMessages(
                messages = listOf(source), workspaceRoot = root, mode = LocalImageInputMode.TOOL,
                model = "gpt-6-astra", baseUrl = "https://api.openai.com/v1", planSharing = true,
            )
            assertFalse(fallback.single().toString().contains("file_data"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun activeTextFileIsMaterializedForDirectChatWithoutToolAccess() = runTest {
        val root = kotlin.io.path.createTempDirectory("file-multimodal-").toFile()
        try {
            val file = File(root, ".dsh/attachments/note.txt").apply {
                parentFile?.mkdirs()
                writeText("这是附件正文。\n第二行内容。")
            }
            val durable = buildLocalUserModelMessage(
                visibleText = "帮我总结这个文件",
                attachments = listOf(
                    LocalImportedAttachment(
                        name = "note.txt",
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        mediaType = "text/plain",
                        bytes = file.length(),
                        attachmentId = "note",
                    ),
                ),
            )

            assertTrue(durable.toString().contains(LOCAL_FILE_REF))
            assertFalse(durable.toString().contains("这是附件正文"))

            val prepared = prepareLocalMultimodalMessages(
                messages = listOf(durable),
                workspaceRoot = root,
                mode = LocalImageInputMode.TOOL,
            )

            assertTrue(prepared.single().toString().contains("这是附件正文"))
            assertTrue(prepared.single().toString().contains("note.txt"))
            assertFalse(prepared.single().toString().contains(LOCAL_FILE_REF))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedLocalDocumentIsAcceptedWithBoundedPreviewAndWorkspacePath() = runTest {
        val root = kotlin.io.path.createTempDirectory("large-file-input-").toFile()
        try {
            val file = File(root, ".dsh/attachments/source.txt").apply {
                parentFile?.mkdirs()
                java.io.RandomAccessFile(this, "rw").use { output ->
                    output.write("Large attachment stays readable".toByteArray())
                    output.setLength(24L * 1024L * 1024L)
                }
            }
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val source = buildLocalUserModelMessage("阅读这个文件", listOf(
                LocalImportedAttachment("source.txt", relative, "text/plain", file.length()),
            ))
            val prepared = prepareLocalMultimodalMessages(
                messages = listOf(source), workspaceRoot = root, mode = LocalImageInputMode.TOOL,
            ).single().toString()
            assertTrue(prepared.contains("Large attachment stays readable"))
            assertTrue(prepared.contains(relative))
            assertTrue(prepared.contains("仅为请求上下文预览"))
            assertEquals(24L * 1024L * 1024L, file.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun olderFileBodyIsNotInjectedAgainOnEveryTurn() = runTest {
        val root = kotlin.io.path.createTempDirectory("file-history-").toFile()
        try {
            val file = File(root, ".dsh/attachments/history.md").apply {
                parentFile?.mkdirs()
                writeText("旧附件里的大段正文")
            }
            val first = buildLocalUserModelMessage(
                visibleText = "先看附件",
                attachments = listOf(
                    LocalImportedAttachment(
                        name = "history.md",
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        mediaType = "text/markdown",
                        bytes = file.length(),
                        attachmentId = "history",
                    ),
                ),
            )
            val prepared = prepareLocalMultimodalMessages(
                messages = listOf(
                    first,
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", "已经看过")
                    },
                    buildJsonObject {
                        put("role", "user")
                        put("content", "继续")
                    },
                ),
                workspaceRoot = root,
                mode = LocalImageInputMode.TOOL,
            )

            assertTrue(prepared.first().toString().contains("历史文件引用"))
            assertFalse(prepared.first().toString().contains("旧附件里的大段正文"))
        } finally {
            root.deleteRecursively()
        }
    }
}
