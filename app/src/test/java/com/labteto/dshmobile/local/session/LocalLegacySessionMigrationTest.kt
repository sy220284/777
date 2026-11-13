package com.labteto.dshmobile.local.session

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLegacySessionMigrationTest {
    @Test
    fun oldSessionIdentityCannotBeClonedIntoNewlySelectedSession() {
        val root = Files.createTempDirectory("legacy-session-owner").toFile()
        try {
            val sessions = File(root, "sessions")
            File(root, "session.json").writeText("""{"id":"original","title":"private"}""")
            File(root, "session.events.jsonl").writeText("old-tool-results\n")
            migrateLegacySessionFiles(root, sessions, "currently-selected")
            assertEquals("""{"id":"original","title":"private"}""", File(sessions, "original.json").readText())
            assertEquals("old-tool-results\n", File(sessions, "original.events.jsonl").readText())
            assertFalse(File(sessions, "currently-selected.json").exists())
            migrateLegacySessionFiles(root, sessions, "some-other-session")
            assertFalse(File(sessions, "some-other-session.json").exists())
            assertEquals("original", File(root, "session.legacy-migration.owner").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun partialLegacyImportContinuesItsOriginalOwnerWithoutOverwritingCanonicalData() {
        val root = Files.createTempDirectory("legacy-resume").toFile()
        try {
            val sessions = File(root, "sessions")
            File(root, "session.json").writeText("""{"legacy":true}""")
            File(root, "session.events.jsonl").writeText("old-event\n")
            migrateLegacySessionFiles(root, sessions, "first-owner")
            File(sessions, "first-owner.events.jsonl").delete()
            File(sessions, "first-owner.json").writeText("canonical-new-user-state")
            migrateLegacySessionFiles(root, sessions, "different-owner")
            assertEquals("canonical-new-user-state", File(sessions, "first-owner.json").readText())
            assertEquals("old-event\n", File(sessions, "first-owner.events.jsonl").readText())
            assertFalse(File(sessions, "different-owner.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun corruptOrPathEscapingSourceFailsClosedWithoutCreatingNewSessionFiles() {
        val root = Files.createTempDirectory("legacy-invalid").toFile()
        try {
            val sessions = File(root, "sessions")
            val legacy = File(root, "session.json")
            legacy.writeText("this is not JSON")
            assertThrows(IllegalStateException::class.java) {
                migrateLegacySessionFiles(root, sessions, "fallback")
            }
            assertFalse(File(sessions, "fallback.json").exists())
            assertFalse(File(root, "session.legacy-migration.owner").exists())
            legacy.writeText("""{"id":"../../outside"}""")
            assertThrows(IllegalArgumentException::class.java) {
                migrateLegacySessionFiles(root, sessions, "fallback")
            }
            assertFalse(File(sessions, "fallback.json").exists())
            assertTrue(legacy.exists())
        } finally { root.deleteRecursively() }
    }
}
