package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionBackupRestoreTest {
    @Test
    fun realExportRoundTripsBackupAndRegistersInExistingCatalog() {
        val source = Files.createTempDirectory("session-backup-source").toFile()
        val destination = Files.createTempDirectory("session-backup-destination").toFile()
        try {
            val id = "story.with.dot"
            val sourceStore = VersionedSessionStore(source, Json)
            sourceStore.write(id, buildJsonObject { put("title", "导入故事") })
            SessionEventLog(source.resolve("$id.events.jsonl"), Json)
                .append("user/message", buildJsonObject { put("content", "test") })
            val output = ByteArrayOutputStream()
            LocalSessionStorageManager(source, Json).exportAll(output)
            val zipNames = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    zipNames += entry.name
                    zip.closeEntry()
                }
            }
            assertTrue("$id.backup.json" in zipNames)
            assertTrue("$id.events.jsonl" in zipNames)

            val existing = VersionedSessionStore(destination, Json)
            existing.write("existing", buildJsonObject { put("title", "旧会话") })
            assertEquals(listOf("existing"), existing.ids())
            val manager = LocalSessionStorageManager(destination, Json)
            assertEquals(1, manager.stageRestore(output.toByteArray()))
            assertTrue(manager.applyPendingRestore() >= 3)
            val reopened = VersionedSessionStore(destination, Json)
            assertTrue("existing" in reopened.ids())
            assertTrue(id in reopened.ids())
            assertEquals(id, reopened.read(id)?.document?.id)
            assertTrue(destination.resolve("$id.events.jsonl").isFile)
            assertFalse(destination.resolve(".restore-pending").exists())
        } finally {
            source.deleteRecursively()
            destination.deleteRecursively()
        }
    }
}
