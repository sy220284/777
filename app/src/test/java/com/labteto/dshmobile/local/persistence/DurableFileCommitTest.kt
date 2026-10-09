package com.labteto.dshmobile.local.persistence

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableFileCommitTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyCommitInterruptionRetainsRecoverableSnapshotAndJournal() {
        for (stage in SnapshotCommitStage.entries) {
            val root = temporary.newFolder()
            val primary = File(root, "snapshot.json").apply { writeText("old") }
            val backup = File(root, "snapshot.json.bak").apply { writeText("old") }
            val journal = File(root, "snapshot.wal").apply { writeText("set:new\n") }
            assertThrows(IllegalStateException::class.java) {
                commitSnapshotAndClearJournal(primary, backup, journal, "new", { it in listOf("old", "new") }) {
                    if (it == stage) error("injected interruption")
                }
            }
            assertEquals("new", primary.readText())
            if (stage != SnapshotCommitStage.JOURNAL_CLEARED) assertEquals("set:new\n", journal.readText())
            if (stage != SnapshotCommitStage.PRIMARY_DURABLE) assertEquals("new", backup.readText())
            // Replay is an idempotent set operation, so both old and new bases recover the same fact.
            assertEquals("new", if (journal.length() > 0) journal.readText().substringAfter("set:").trim() else primary.readText())
            assertTrue(primary.delete())
            assertEquals(if (stage == SnapshotCommitStage.PRIMARY_DURABLE) "old" else "new",
                RecoveringDocumentFile(primary).read({ "missing" }, { it }))
        }
    }

    @Test fun invalidSnapshotCannotClearJournalOrChangeExistingFiles() {
        val file = File(temporary.root, "data").apply { writeText("old") }
        val backup = File(temporary.root, "data.bak").apply { writeText("old") }
        val wal = File(temporary.root, "data.wal").apply { writeText("pending") }
        assertThrows(IllegalArgumentException::class.java) {
            commitSnapshotAndClearJournal(file, backup, wal, "invalid", { it == "old" })
        }
        assertEquals("old", file.readText())
        assertEquals("old", backup.readText())
        assertEquals("pending", wal.readText())
    }
}
