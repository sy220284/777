package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalMessageMediaSource
import com.labteto.dshmobile.uicontract.UiMessageBranch
import com.labteto.dshmobile.uicontract.UiMessageContentBlock
import com.labteto.dshmobile.uicontract.UiMessageMediaSource
import com.labteto.dshmobile.uicontract.UiMessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptUiProjectionTest {
    @Test fun projectionKeepsToolIdentitySpeakersImagesFilesAndFutureBlocks() {
        val future = buildJsonObject { put("new_field", "保留未来字段") }
        val message = LocalHarnessMessage(
            id = "tool-1", role = "tool", content = "生成文件", createdAt = 101L,
            speakerId = "worker-5", speakerName = "资料整理",
            toolName = "create_file", toolIsError = true, toolErrorCode = "WRITE_CONFLICT",
            toolCallId = "call-8", toolExecutionId = "run-4",
            toolDeclaredAt = 99L, toolDeclaredSequence = 15L,
            blocks = listOf(
                LocalMessageBlock.Text("结果"),
                LocalMessageBlock.Image(
                    "assets/sample.png", "image/png", "sample.png", 128L,
                    attachmentId = "image-1", width = 400, height = 300,
                    source = LocalMessageMediaSource.MODEL,
                ),
                LocalMessageBlock.File(
                    "files/report.md", "text/markdown", "report.md", 20L,
                    attachmentId = "file-2", source = LocalMessageMediaSource.TOOL,
                ),
                LocalMessageBlock.Unknown("future-media", future),
            ),
        )
        val ui = projectTranscriptHistoryMessage(message)
        assertEquals(UiMessageRole.TOOL, ui.role)
        assertEquals("worker-5", ui.speakerId)
        assertEquals("资料整理", ui.speakerName)
        assertEquals("WRITE_CONFLICT", ui.tool?.errorCode)
        assertEquals("call-8", ui.tool?.callId)
        assertEquals("run-4", ui.tool?.executionId)
        assertEquals(99L, ui.tool?.declaredAt)
        assertEquals(15L, ui.tool?.declaredSequence)
        assertEquals(4, ui.blocks.size)
        assertEquals("结果", (ui.blocks[0] as UiMessageContentBlock.Text).text)
        val image = ui.blocks[1] as UiMessageContentBlock.Image
        assertEquals("assets/sample.png", image.relativePath)
        assertEquals(UiMessageMediaSource.MODEL, image.source)
        assertEquals(400, image.width)
        val file = ui.blocks[2] as UiMessageContentBlock.File
        assertEquals("files/report.md", file.relativePath)
        assertEquals(UiMessageMediaSource.TOOL, file.source)
        assertEquals(future, (ui.blocks[3] as UiMessageContentBlock.Unknown).payload)
    }

    @Test fun liveAndHistoryProjectionShareSameMessageFieldsAndKeepEditing() {
        val message = LocalHarnessMessage("u-1", "user", "用户消息", createdAt = 11L)
        val history = projectTranscriptHistoryMessage(message)
        val live = projectTranscriptHistoryMessage(
            message, editableText = "更正内容", branch = UiMessageBranch(1, 3),
        )
        assertEquals(history.copy(editableText = "更正内容", branch = UiMessageBranch(1, 3)), live)
        assertNull(history.tool)
        assertTrue(history.blocks.isEmpty())
        assertNotNull(live.branch)
    }
}
