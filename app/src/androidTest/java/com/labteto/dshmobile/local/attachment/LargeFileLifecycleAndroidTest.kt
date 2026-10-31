package com.labteto.dshmobile.local.attachment

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercise a real 128 MiB file in Android private storage. The source is produced
 * a block at a time: the test itself must never materialize a large ByteArray.
 */
@RunWith(AndroidJUnit4::class)
class LargeFileLifecycleAndroidTest {
    @Test
    fun imported128MiBFileCanBeListedAndPreviewedWithoutLoadingItInFull() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "large-file-${UUID.randomUUID()}")
        check(root.mkdirs())
        try {
            val size = 128L * 1024L * 1024L
            val source = object : InputStream() {
                var remaining = size
                override fun read(): Int {
                    if (remaining == 0L) return -1
                    remaining--
                    return 0
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    bytes.fill(0, offset, offset + count)
                    remaining -= count
                    return count
                }
            }
            val target = File(root, "large.bin")
            val digest = MessageDigest.getInstance("SHA-256")
            val copied = target.outputStream().use { output ->
                copyAttachmentCancellably(source, output, digest)
            }
            assertEquals(size, copied)
            assertEquals(size, target.length())
            val workspace = LocalWorkspace(root)
            val entries = workspace.directoryPage(".")
            assertTrue(entries.entries.any { it.path == "large.bin" && it.bytes == size })
            val preview = workspace.preview("large.bin")
            assertEquals(size, preview.file.bytes)
            assertNull(preview.text)
            assertTrue(preview.truncated)
            assertFalse(preview.file.path.isBlank())
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ANDROID_LARGE_FILE_SCENARIO size_bytes=$size copy_and_preview=complete")
            })
        } finally {
            root.deleteRecursively()
        }
    }
}
