package com.labteto.dshmobile.local.persistence

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveringDocumentFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun decode(raw: String): String = raw.also { check(it.startsWith("valid:")) }

    @Test fun corruptionStaysClosedAcrossReadsRestartAndBlindWrite() {
        for (withBackup in listOf(false, true)) {
            val root = temporary.newFolder()
            val file = File(root, "data.json").apply { writeText("broken-primary") }
            if (withBackup) File(root, "data.json.bak").writeText("broken-backup")
            val store = RecoveringDocumentFile(file)
            repeat(2) { assertThrows(IllegalStateException::class.java) { store.read({ "empty" }, ::decode) } }
            assertThrows(IllegalStateException::class.java) { RecoveringDocumentFile(file).read({ "empty" }, ::decode) }
            assertThrows(IllegalStateException::class.java) { store.write("valid:new") { it.startsWith("valid:") } }
            assertFalse(file.exists())
            val retained = root.listFiles().orEmpty().filter { it.name.endsWith(".quarantine") }.map { it.readText() }
            assertTrue("broken-primary" in retained)
            assertEquals(if (withBackup) 2 else 1, retained.size)
            store.restoreFromRecoverySource("valid:restored") { it.startsWith("valid:") }
            assertEquals("valid:restored", RecoveringDocumentFile(file).read({ "empty" }, ::decode))
        }
    }

    @Test fun missingPrimaryRecoversBackupAndBrandNewStateCanBeCreated() {
        val file = File(temporary.root, "new.json")
        val store = RecoveringDocumentFile(file)
        assertEquals("empty", store.read({ "empty" }, ::decode))
        store.write("valid:first") { it.startsWith("valid:") }
        assertTrue(file.delete())
        assertEquals("valid:first", RecoveringDocumentFile(file).read({ "empty" }, ::decode))
        assertEquals("valid:first", file.readText())
    }

    @Test fun preMarkerQuarantineDoesNotLookLikeNewData() {
        val file = File(temporary.root, "diary.json")
        File(temporary.root, "diary.primary.corrupt-1.json").writeText("broken")
        assertThrows(IllegalStateException::class.java) { RecoveringDocumentFile(file).read({ "empty" }, ::decode) }
        assertTrue(File(temporary.root, "diary.json.recovery-required").isFile)
    }

    @Test fun explicitRecreatePolicyCanReplaceQuarantineWithoutLosingOriginal() {
        val file = File(temporary.root, "profile.json").apply { writeText("broken") }
        val store = RecoveringDocumentFile(file, failurePolicy = RecoveringDocumentFailurePolicy.RECREATE_DEFAULT)
        assertEquals("empty", store.read({ "empty" }, ::decode))
        store.write("valid:default") { it.startsWith("valid:") }
        assertEquals("valid:default", store.read({ "empty" }, ::decode))
        assertTrue(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".quarantine") && it.readText() == "broken" })
    }
}
