package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionEventFileSnapshot
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionStorageManager
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSessionStorageManagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun statusCountsDurableBytesAndLegacySegmentsOnly() {
        val root = temporary.newFolder("sessions-status")
        File(root, "a.json").writeText("snapshot")
        File(root, "a.events.jsonl").writeText("active")
        File(root, "a.events.jsonl.part-0").writeText("raw")
        File(root, "a.events.jsonl.part-1.gz").writeText("compressed")
        File(root, "ignored.tmp").writeText("temporary")
        File(root, "a.json.corrupt-123").writeText("corrupt")

        val status = manager(root).status()

        assertEquals(1, status.sessionCount)
        assertEquals(1, status.rawSegmentCount)
        assertEquals(1, status.compressedSegmentCount)
        assertEquals(
            listOf(
                File(root, "a.json"),
                File(root, "a.events.jsonl"),
                File(root, "a.events.jsonl.part-0"),
                File(root, "a.events.jsonl.part-1.gz"),
            ).sumOf { it.length() },
            status.totalBytes,
        )
        assertEquals(LocalSessionStorageStatus.DEFAULT_BUDGET_BYTES, status.budgetBytes)
    }

    @Test
    fun compactAllConvertsLegacyRawSegmentWithoutDroppingEvent() = runTest {
        val root = temporary.newFolder("sessions-compact")
        val raw = File(root, "legacy.events.jsonl.part-0")
        val event = SessionEvent(
            sequence = 0L,
            type = "user/message",
            createdAt = 123L,
            data = buildJsonObject { put("content", "保留我") },
        )
        raw.writeText(json.encodeToString(event) + "\n")

        val after = manager(root).compactAll()

        assertFalse(raw.exists())
        assertTrue(File(root, "legacy.events.jsonl.part-0.gz").isFile)
        assertEquals(0, after.rawSegmentCount)
        assertEquals(1, after.compressedSegmentCount)

        val reopened = LocalSessionEventLog(File(root, "legacy.events.jsonl"), json)
        assertEquals("保留我", reopened.pageBeforeChronological(limit = 10).single().data["content"]?.toString()?.trim('"'))
    }

    @Test
    fun exportAllProducesLosslessArchiveWithoutTemporaryFiles() {
        val root = temporary.newFolder("sessions-export")
        val snapshot = File(root, "a.json").apply { writeText("snapshot-a") }
        val events = File(root, "a.events.jsonl").apply { writeText("event-a\n") }
        File(root, "orphan.tmp").writeText("ignore-me")

        val output = ByteArrayOutputStream()
        val bytes = manager(root).exportAll(output)

        assertEquals(snapshot.length() + events.length(), bytes)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals(setOf("a.json", "a.events.jsonl"), entries.keys)
        assertEquals("snapshot-a", entries["a.json"])
        assertEquals("event-a\n", entries["a.events.jsonl"])
    }

    @Test
    fun exportFailureClosesEveryOpenedEventSnapshot() {
        val root = temporary.newFolder("sessions-export-failure")
        File(root, "a.events.jsonl").writeText("event-a\n")
        val first = TrackingInputStream("first\n".toByteArray())
        val second = TrackingInputStream("second\n".toByteArray())
        val third = TrackingInputStream("third\n".toByteArray())
        val snapshots = listOf(
            SessionEventFileSnapshot("a.events.jsonl.part-0", 1L, 6L, first),
            SessionEventFileSnapshot("a.events.jsonl.part-1", 2L, 7L, second),
            SessionEventFileSnapshot("a.events.jsonl", 3L, 6L, third),
        )
        val manager = LocalSessionStorageManager(
            root = root,
            json = json,
            eventSnapshotOpener = { _, _ -> snapshots },
        )

        assertThrows(IOException::class.java) {
            manager.exportAll(FailingOutputStream(failAfterBytes = 64))
        }

        assertTrue(first.closed)
        assertTrue(second.closed)
        assertTrue(third.closed)
    }

    private fun manager(root: File) = LocalSessionStorageManager(
        root = root,
        json = json,
    )

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FailingOutputStream(
        private val failAfterBytes: Int,
    ) : OutputStream() {
        private var written = 0

        override fun write(value: Int) {
            if (written >= failAfterBytes) throw IOException("forced export failure")
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (written + length > failAfterBytes) throw IOException("forced export failure")
            written += length
        }
    }
}
