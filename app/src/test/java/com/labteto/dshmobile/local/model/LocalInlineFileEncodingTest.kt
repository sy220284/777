package com.labteto.dshmobile.local.model

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInlineFileEncodingTest {
    @Test fun streamingEncodingMatchesStandardBase64AcrossChunkBoundaries() {
        val root = kotlin.io.path.createTempDirectory("inline-file-stream").toFile()
        try {
            val file = File(root, "attachment.bin")
            for (length in listOf(1, 2, 3, 8191, 8192, 8193, 1024 * 1024 + 5)) {
                val bytes = ByteArray(length) { ((it * 31) and 0xff).toByte() }
                file.writeBytes(bytes)
                val encoded = encodeLocalFileDataUrl(file, "application/octet-stream", bytes.size.toLong())
                assertEquals("data:application/octet-stream;base64," +
                    Base64.getEncoder().encodeToString(bytes), encoded)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun encodingRejectsFilesThatExceedRequestBudget() {
        val root = kotlin.io.path.createTempDirectory("inline-file-budget").toFile()
        try {
            val file = File(root, "larger.bin").apply { writeBytes(ByteArray(4097) { 1 }) }
            val failure = runCatching {
                encodeLocalFileDataUrl(file, "application/octet-stream", 4096)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            root.deleteRecursively()
        }
    }
}
