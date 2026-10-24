package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two methods MUST run as separate `am instrument` invocations, with
 * external `am force-stop` between. They are skipped in normal discovery:
 * executing two methods in a single test process would not verify recovery.
 */
@RunWith(AndroidJUnit4::class)
class SqliteCrossProcessRestoreAcceptanceAndroidTest {
    private val snapshotName = "process-acceptance-snapshot.db"
    private val eventName = "process-acceptance-events.db"
    private val restoreKey = "process-acceptance"
    private val sessionId = "process-recovery-proof"

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pending(): File = File(
        context().filesDir, "local-harness/.sqlite-restore-pending-$restoreKey",
    )
    private fun marker(): File = File(context().filesDir, "local-harness/process-restore-marker")
    private fun requirePhase(phase: String) {
        assumeTrue("Run with -e dsh_acceptance $phase, separated by adb force-stop",
            InstrumentationRegistry.getArguments().getString("dsh_acceptance") == phase)
    }

    @Test fun stageRestoreForExternalProcessRestart() {
        requirePhase("stage")
        val context = context()
        assertTrue("Use a fresh app install; stale restore must not be overwritten",
            !pending().exists() && !marker().exists())
        val sourceSnap = "process-acceptance-source-snapshot.db"
        val sourceEvents = "process-acceptance-source-events.db"
        val archive = ByteArrayOutputStream()
        try {
            SqliteSessionSnapshotStore(context, Json, sourceSnap).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, sourceEvents).use { events ->
                    val session = LocalHarnessSession(id = sessionId, title = "真实进程中断保留")
                    snapshots.write(sessionId,
                        Json.encodeToJsonElement(LocalHarnessSession.serializer(), session).jsonObject, 1L)
                    repeat(24) { index ->
                        events.append(sessionId, "assistant/message",
                            buildJsonObject { put("ordinal", index) })
                    }
                    val source = SqliteSessionStorageManager(context, snapshots, events,
                        sourceSnap, sourceEvents, "source-$restoreKey")
                    assertEquals(1, source.exportAll(archive).let { source.status().sessionCount })
                }
            }
            SqliteSessionSnapshotStore(context, Json, snapshotName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    val installer = SqliteSessionStorageManager(context, snapshots, events,
                        snapshotName, eventName, restoreKey)
                    assertEquals(1, installer.stageRestore(archive.toByteArray()))
                    assertTrue(installer.status().pendingRestore)
                }
            }
            assertTrue(File(pending(), "READY").isFile)
            // Persist the witness ONLY after staging succeeded.
            marker().parentFile?.mkdirs()
            marker().writeText("staged:$sessionId")
            assertTrue("durable witness not flushed", marker().isFile)
        } finally {
            context.deleteDatabase(sourceSnap)
            context.deleteDatabase(sourceEvents)
        }
    }

    @Test fun resumeRestoreAfterExternalProcessDeath() {
        requirePhase("resume")
        val context = context()
        assertEquals("staged:$sessionId", marker().readText())
        assertTrue("stage was lost across process death", File(pending(), "READY").isFile)
        try {
            SqliteSessionSnapshotStore(context, Json, snapshotName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    val installer = SqliteSessionStorageManager(context, snapshots, events,
                        snapshotName, eventName, restoreKey)
                    assertEquals(1, installer.applyPendingRestore())
                    assertEquals(0, installer.applyPendingRestore())
                }
            }
            SqliteSessionSnapshotStore(context, Json, snapshotName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    assertEquals("真实进程中断保留",
                        snapshots.read(sessionId)?.document?.payload?.get("title")
                            ?.toString()?.trim('"'))
                    assertEquals(23L, events.latestSequence(sessionId))
                    assertEquals(24, events.pageAfter(sessionId, -1L).size)
                }
            }
            assertTrue("restore intent was not retired", !pending().exists())
        } finally {
            marker().delete()
            pending().deleteRecursively()
            context.deleteDatabase(snapshotName)
            context.deleteDatabase(eventName)
        }
    }
}
