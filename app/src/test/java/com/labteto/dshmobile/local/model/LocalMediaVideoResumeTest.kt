package com.labteto.dshmobile.local.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaVideoResumeTest {
    @get:Rule val root = TemporaryFolder()

    @Test fun coldResumePreservesOnlyContentAuthenticatedByLocalDigestAndRemoteVersion() {
        val part = root.newFile("task.part").apply { writeBytes(ByteArray(300) { 7 }) }
        val meta = root.newFile("task.checkpoint")
        saveLocalVideoResumeCheckpoint(part, meta, "fingerprint", "\"v1\"")
        assertEquals(LocalVideoResumeCheckpoint(300, "\"v1\""),
            localVideoResumeCheckpoint(part, meta, "fingerprint"))
        assertNull(localVideoResumeCheckpoint(part, meta, "different"))

        // An old v1 ETag-only metadata record is not permission to append unknown bytes.
        meta.writeText("fingerprint\n\"v1\"\n")
        assertNull(localVideoResumeCheckpoint(part, meta, "fingerprint"))
        saveLocalVideoResumeCheckpoint(part, meta, "fingerprint", "\"v1\"")
        part.writeBytes(ByteArray(300) { 9 })
        assertNull(localVideoResumeCheckpoint(part, meta, "fingerprint"))

        part.writeBytes(ByteArray(300) { 7 })
        saveLocalVideoResumeCheckpoint(part, meta, "fingerprint", "\"v1\"")
        meta.writeText(meta.readText().replace("\"v1\"", "W/\"v1\""))
        assertNull(localVideoResumeCheckpoint(part, meta, "fingerprint"))
    }

    @Test fun restartDiscardsTrailingBytesNeverCoveredByFsyncedCheckpoint() {
        val part = root.newFile("interrupted.part").apply { writeBytes(ByteArray(300) { 1 }) }
        val meta = root.newFile("interrupted.checkpoint")
        saveLocalVideoResumeCheckpoint(part, meta, "fingerprint", "\"v1\"")
        part.appendBytes(ByteArray(27) { 3 }) // Power loss before the next checkpoint.
        assertEquals(327L, part.length())
        assertEquals(LocalVideoResumeCheckpoint(300, "\"v1\""),
            localVideoResumeCheckpoint(part, meta, "fingerprint"))
        assertEquals(300L, part.length())
        assertTrue(part.readBytes().all { it == 1.toByte() })
    }

    @Test fun exactRangeAndVersionAreRequiredBeforeAppending() {
        val previous = LocalVideoResumeCheckpoint(300, "\"v1\"")
        assertEquals(LocalVideoTransferPlan(true, "\"v1\"", 500),
            localVideoTransferPlan(previous, 206, "\"v1\"", "bytes 300-499/500", 200))
        listOf(
            arrayOf("\"v2\"", "bytes 300-499/500"),
            arrayOf("\"v1\"", "bytes 0-499/500"),
            arrayOf("\"v1\"", "bytes 300-499/400"),
        ).forEach { (etag, range) ->
            assertThrows(IllegalArgumentException::class.java) {
                localVideoTransferPlan(previous, 206, etag, range, 200)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            localVideoTransferPlan(null, 206, "\"v1\"", "bytes 0-9/10", 10)
        }
    }

    @Test fun serverFullReplyStartsNewVersionWithoutAppendingOldBytes() {
        val previous = LocalVideoResumeCheckpoint(300, "\"v1\"")
        assertEquals(LocalVideoTransferPlan(false, "\"v2\"", 550),
            localVideoTransferPlan(previous, 200, "\"v2\"", null, 550))
        assertEquals(LocalVideoTransferPlan(false, null, null),
            localVideoTransferPlan(null, 200, null, null, -1))
    }
}
