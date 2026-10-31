package com.labteto.dshmobile.local.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalIntermediateMaintenanceAndroidTest {
    @Test fun clearsOldIntermediateCacheWithoutBreakingRestartResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "maintenance-device-" + UUID.randomUUID())
        check(root.mkdirs())
        try {
            val now = System.currentTimeMillis()
            val index = File(root, ".dsh/text-index/" + "e".repeat(64) + ".index")
            check(index.parentFile.mkdirs())
            index.outputStream().use { stream ->
                val chunk = ByteArray(64 * 1024)
                repeat(256) { stream.write(chunk) }
            }
            assertTrue(index.setLastModified(now - 20L * 24 * 60 * 60 * 1000))
            val workspace = LocalWorkspace(root)
            workspace.write("src.kt", "original version")
            val start = workspace.writeChunk("src.kt", "partial source", 0L, false)
            val sessionId = Regex("session_id=([0-9a-f-]+)").find(start)!!.groupValues[1]
            val stage = File(root, ".src.kt.write-in-progress-" + sessionId)
            assertTrue(stage.isFile)
            val report = LocalWorkspaceIntermediateMaintenance(root).cleanup(now)
            assertEquals(1, report.deletedFiles)
            assertEquals(16L * 1024 * 1024, report.deletedBytes)
            assertFalse(index.exists())
            assertTrue(stage.isFile)
            assertEquals("original version", File(root, "src.kt").readText())
            val offset = Regex("next_byte=([0-9]+)").find(start)!!.groupValues[1].toLong()
            LocalWorkspace(root).writeChunk("src.kt", " complete", offset, true, sessionId)
            assertEquals("partial source complete", File(root, "src.kt").readText())
            assertFalse(stage.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
