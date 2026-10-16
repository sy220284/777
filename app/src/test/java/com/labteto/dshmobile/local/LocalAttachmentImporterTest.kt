package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.attachment.copyAttachmentCancellably
import com.labteto.dshmobile.local.attachment.commitImportedAttachmentFile
import com.labteto.dshmobile.local.attachment.inspectImportedAttachmentFile
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.UiAttachmentInspectionReceipt
import java.io.File
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAttachmentImporterTest {
    private fun stored(root: File, text: String): LocalImportedAttachment {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val dir = File(root, ".dsh/attachments").apply { mkdirs() }
        File(dir, "$sha.txt").writeText(text)
        return LocalImportedAttachment("note.txt", ".dsh/attachments/$sha.txt",
            "text/plain", text.toByteArray().size.toLong(), sha)
    }

    @Test fun importedTextIsPreviewedWithVerifiedDigest() {
        val root = kotlin.io.path.createTempDirectory("attachment-preview").toFile()
        try {
            val item = stored(root, "local attachment")
            val result = inspectImportedAttachmentFile(root, item) as UiAttachmentInspectionReceipt.Ready
            assertEquals("local attachment", result.value.preview)
            assertTrue(result.value.digestVerified)
            assertFalse(result.value.truncated)
        } finally { root.deleteRecursively() }
    }

    @Test fun tamperedOrMissingAttachmentCannotAppearHealthy() {
        val root = kotlin.io.path.createTempDirectory("attachment-preview-changed").toFile()
        try {
            val item = stored(root, "original")
            val file = File(root, item.relativePath)
            file.writeText("tampered")
            assertTrue(inspectImportedAttachmentFile(root, item) is UiAttachmentInspectionReceipt.Unavailable)
            file.delete()
            assertTrue(inspectImportedAttachmentFile(root, item) is UiAttachmentInspectionReceipt.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test fun attachmentInspectorRejectsTraversalAndSymlinks() {
        val root = kotlin.io.path.createTempDirectory("attachment-preview-link").toFile()
        try {
            val item = stored(root, "secret")
            assertTrue(inspectImportedAttachmentFile(root, item.copy(relativePath = "../secret"))
                is UiAttachmentInspectionReceipt.Unavailable)
            val storedFile = File(root, item.relativePath)
            val other = File(root, "other.txt").apply { writeText("secret") }
            storedFile.delete()
            Files.createSymbolicLink(storedFile.toPath(), other.toPath())
            assertTrue(inspectImportedAttachmentFile(root, item) is UiAttachmentInspectionReceipt.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test fun largeTextPreviewStaysBoundedWithoutFalseDigestClaim() {
        val root = kotlin.io.path.createTempDirectory("attachment-preview-long").toFile()
        try {
            val item = stored(root, "A".repeat(9 * 1024 * 1024))
            val result = inspectImportedAttachmentFile(root, item) as UiAttachmentInspectionReceipt.Ready
            assertFalse(result.value.digestVerified)
            assertTrue(result.value.truncated)
            assertEquals(16_384, result.value.preview?.length)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun onlySystemContentUrisMayCrossTheAttachmentPort() {
        assertTrue(isSupportedPickerAttachmentUri("content", "com.android.providers.media.documents"))
        assertFalse(isSupportedPickerAttachmentUri("file", ""))
        assertFalse(isSupportedPickerAttachmentUri("https", "example.com"))
        assertFalse(isSupportedPickerAttachmentUri("content", null))
        assertFalse(isSupportedPickerAttachmentUri("content", " "))
        assertFalse(isSupportedPickerAttachmentUri(null, "provider"))
    }

    @Test
    fun committingAttachmentDoesNotReplaceAnExistingSharedHash() {
        val dir = kotlin.io.path.createTempDirectory("attachment-dedup").toFile()
        try {
            val incoming = File(dir, ".incoming").apply { writeText("new contents") }
            val existing = File(dir, "digest.txt").apply { writeText("existing") }
            val failed = runCatching { commitImportedAttachmentFile(incoming, existing) }
            assertTrue("Conflicting shared hash must fail closed", failed.isFailure)
            assertEquals("existing", existing.readText())
            // Caller owns this temporary file even when a duplicate target exists.
            assertEquals("new contents", incoming.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun committingNewAttachmentPublishesACompleteFile() {
        val dir = kotlin.io.path.createTempDirectory("attachment-commit").toFile()
        try {
            val incoming = File(dir, ".incoming").apply { writeText("complete attachment") }
            val target = File(dir, "digest.txt")
            commitImportedAttachmentFile(incoming, target)
            assertEquals("complete attachment", target.readText())
            // Caller cleanup owns the original temporary file; the published inode is stable.
            assertTrue(incoming.exists())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(".commit-") })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun publishingSameHashTwiceReusesOnlyIdenticalCompleteContent() {
        val dir = kotlin.io.path.createTempDirectory("attachment-same-bytes").toFile()
        try {
            val first = File(dir, ".incoming-a").apply { writeText("same bytes") }
            val second = File(dir, ".incoming-b").apply { writeText("same bytes") }
            val target = File(dir, "content-addressed.txt")
            commitImportedAttachmentFile(first, target)
            commitImportedAttachmentFile(second, target)
            assertEquals("same bytes", target.readText())
            second.writeText("DIFFERENT")
            assertTrue(runCatching { commitImportedAttachmentFile(second, target) }.isFailure)
            assertEquals("same bytes", target.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun symlinkAtAnExistingDigestCannotBeAcceptedAsAnAttachment() {
        val dir = kotlin.io.path.createTempDirectory("attachment-symlink").toFile()
        try {
            val outside = File(dir, "other.txt").apply { writeText("same bytes") }
            val target = File(dir, "digest.txt")
            Files.createSymbolicLink(target.toPath(), outside.toPath())
            val source = File(dir, ".incoming").apply { writeText("same bytes") }
            assertTrue(runCatching { commitImportedAttachmentFile(source, target) }.isFailure)
            assertEquals("same bytes", outside.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun attachmentLargerThanOldTwentyMegabyteLimitCopiesWithoutRejection() = runBlocking {
        val size = 21L * 1024L * 1024L
        val source = object : InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining > 0L) {
                remaining -= 1
                0
            } else -1

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                bytes.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        val output = object : OutputStream() {
            var written = 0L
            override fun write(value: Int) { written++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                written += length
            }
        }

        val copied = copyAttachmentCancellably(
            source = source,
            output = output,
            digest = MessageDigest.getInstance("SHA-256"),
        )

        assertEquals(size, copied)
        assertEquals(size, output.written)
    }

    @Test
    fun cancellationInterruptsBlockedAttachmentCopy() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val source = object : InputStream() {
            private fun blockingRead(): Int {
                started.countDown()
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                return -1
            }

            override fun read(): Int = blockingRead()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = blockingRead()
        }
        val job = launch(Dispatchers.Default) {
            copyAttachmentCancellably(
                source = source,
                output = ByteArrayOutputStream(),
                digest = MessageDigest.getInstance("SHA-256"),
            )
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        withTimeout(3_000L) {
            job.cancelAndJoin()
        }

        assertTrue(interrupted.get())
    }
}
