package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.LOCAL_FILE_REF
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileAttachmentMultimodalTest {
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
