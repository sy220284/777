package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.session.LocalMessageBlock
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptMediaPresentationTest {
    @Test
    fun thinkingMarkerSplitAcrossTextBlocksDoesNotLeakIntoMixedMediaTranscript() {
        val picture = LocalMessageBlock.Image(
            relativePath = ".dsh/attachments/photo.png",
            mediaType = "image/png",
            name = "photo.png",
            bytes = 128L,
        )
        val file = LocalMessageBlock.File(
            relativePath = ".dsh/attachments/report.pdf",
            mediaType = "application/pdf",
            name = "report.pdf",
            bytes = 512L,
        )
        val message = LocalHarnessMessage(
            id = "assistant-media",
            role = "assistant",
            content = "<think>internal</think>Here is the result.",
            createdAt = 1L,
            blocks = listOf(
                LocalMessageBlock.Text("<thi"),
                LocalMessageBlock.Text("nk>internal</think>Here is "),
                LocalMessageBlock.Text("the result."),
                picture,
                LocalMessageBlock.Text("\nMore details"),
                file,
            ),
        )

        assertEquals(
            listOf(LocalMessageBlock.Text("Here is the result."), picture,
                LocalMessageBlock.Text("\nMore details"), file),
            localVisibleAssistantBlocks(message),
        )
    }

    @Test
    fun legacyTextAndMarkdownBlocksStayVisibleWithoutAddingSeparators() {
        val legacy = LocalHarnessMessage(
            id = "legacy",
            role = "assistant",
            content = "Normal **Markdown**",
            createdAt = 2L,
        )
        assertEquals(listOf(LocalMessageBlock.Text("Normal **Markdown**")),
            localVisibleAssistantBlocks(legacy))
        val multipart = legacy.copy(
            blocks = listOf(LocalMessageBlock.Text("**Bo"), LocalMessageBlock.Text("ld**")),
        )
        assertEquals(listOf(LocalMessageBlock.Text("**Bold**")),
            localVisibleAssistantBlocks(multipart))
    }

    @Test
    fun savedVideoPlaybackDoesNotRequireAProviderDownloadUrl() {
        assertTrue(canPlayLocalMediaVideo(LocalMediaVideoPhase.SUCCEEDED, true, null))
        assertTrue(canPlayLocalMediaVideo(LocalMediaVideoPhase.SUCCEEDED, true, ""))
        assertTrue(canPlayLocalMediaVideo(LocalMediaVideoPhase.SUCCEEDED, false,
            "https://example.invalid/video.mp4"))
        assertTrue(!canPlayLocalMediaVideo(LocalMediaVideoPhase.SUCCEEDED, false, null))
        assertTrue(!canPlayLocalMediaVideo(LocalMediaVideoPhase.PROCESSING, true,
            "https://example.invalid/video.mp4"))
    }

    @Test
    fun transcriptMediaCanOnlyResolveExistingFilesWithinItsWorkspace() {
        val parent = Files.createTempDirectory("transcript-media-").toFile()
        try {
            val root = File(parent, "workspace").apply { mkdirs() }
            val inside = File(root, ".dsh/attachments/picture.png").apply {
                parentFile!!.mkdirs()
                writeText("image")
            }
            val outside = File(parent, "outside.png").apply { writeText("not an attachment") }

            assertEquals(inside.canonicalFile,
                resolveLocalTranscriptFile(root.absolutePath, ".dsh/attachments/picture.png"))
            assertNull(resolveLocalTranscriptFile(root.absolutePath, "../outside.png"))
            assertNull(resolveLocalTranscriptFile(root.absolutePath, outside.absolutePath))
            assertNull(resolveLocalTranscriptFile(root.absolutePath, "missing.png"))
            assertNull(resolveLocalTranscriptFile("", ".dsh/attachments/picture.png"))
            assertNull(resolveLocalTranscriptFile(root.absolutePath, ""))

            val alias = File(inside.parentFile, "outside-link.png").toPath()
            Files.createSymbolicLink(alias, outside.toPath())
            assertNull(resolveLocalTranscriptFile(root.absolutePath,
                ".dsh/attachments/outside-link.png"))
            assertTrue(inside.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }
}
