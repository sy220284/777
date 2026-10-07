package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAttachmentAdmissionTest {
    @Test
    fun filePickerCannotBypassImageLimit() {
        val current = (0 until MAX_LOCAL_IMAGE_SELECTION).map { index ->
            LocalImportedAttachment(
                name = "image-$index.png",
                relativePath = ".dsh/attachments/image-$index.png",
                mediaType = "image/png",
                bytes = 1L,
                attachmentId = "image-$index",
            )
        }
        val candidate = LocalImportedAttachment(
            name = "extra.webp",
            relativePath = ".dsh/attachments/extra.webp",
            mediaType = "image/webp",
            bytes = 1L,
            attachmentId = "extra",
        )

        assertEquals(
            LocalComposerAttachmentDecision.IMAGE_LIMIT,
            localComposerAttachmentDecision(current, candidate),
        )
    }

    @Test
    fun duplicateAttachmentIsIgnoredForBothPickerRoutes() {
        val existing = LocalImportedAttachment(
            name = "same.pdf",
            relativePath = ".dsh/attachments/digest.pdf",
            mediaType = "application/pdf",
            bytes = 12L,
            attachmentId = "digest",
        )
        val duplicate = existing.copy(name = "copy.pdf")

        assertEquals(
            LocalComposerAttachmentDecision.DUPLICATE,
            localComposerAttachmentDecision(listOf(existing), duplicate),
        )
    }

    @Test
    fun ordinaryFileRemainsAcceptedWithoutInventingAFormatWhitelist() {
        val candidate = LocalImportedAttachment(
            name = "archive.custom",
            relativePath = ".dsh/attachments/archive.custom",
            mediaType = "application/octet-stream",
            bytes = 12L,
            attachmentId = "archive",
        )

        assertEquals(
            LocalComposerAttachmentDecision.ACCEPT,
            localComposerAttachmentDecision(emptyList(), candidate),
        )
    }
}
