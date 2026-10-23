package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full SQLite archive/restore, without overwriting app or JVM-test data. */
@RunWith(AndroidJUnit4::class)
class SqliteStorageArchiveAndroidTest {
    @Test fun exportedSqliteDatabaseRetainsSessionAndAllEventsAcrossRestartRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = UUID.randomUUID().toString()
        val srcSnapshot = "backup-source-$uid-snap.db"
        val srcEvent = "backup-source-$uid-event.db"
        val dstSnapshot = "backup-target-$uid-snap.db"
        val dstEvent = "backup-target-$uid-event.db"
        val restoreKey = "test-$uid"
        try {
            val bytes = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(context, Json, srcSnapshot).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, srcEvent).use { events ->
                    val session = LocalHarnessSession(id = "exported", title = "恢复的会话")
                    snapshots.write(
                        "exported",
                        Json.encodeToJsonElement(LocalHarnessSession.serializer(), session).jsonObject,
                        100L,
                    )
                    repeat(140) { idx ->
                        events.append("exported", "assistant/message",
                            buildJsonObject { put("ordinal", idx) })
                    }
                    val manager = SqliteSessionStorageManager(
                        context, snapshots, events,
                        snapshotDatabaseName = srcSnapshot, eventDatabaseName = srcEvent,
                        restoreKey = "source-$uid",
                    )
                    assertEquals(1, manager.status().sessionCount)
                    val originalBytes = manager.exportAll(bytes)
                    assertTrue(originalBytes > 100L)
                }
            }
            SqliteSessionSnapshotStore(context, Json, dstSnapshot).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, dstEvent).use { events ->
                    val manager = SqliteSessionStorageManager(
                        context, snapshots, events,
                        snapshotDatabaseName = dstSnapshot, eventDatabaseName = dstEvent,
                        restoreKey = restoreKey,
                    )
                    assertEquals(1, manager.stageRestore(bytes.toByteArray()))
                }
            }
            // Process-start boundary: both target SQLite helpers were closed above.
            val installer = SqliteSessionStorageManager(
                context,
                SqliteSessionSnapshotStore(context, Json, dstSnapshot),
                SqliteSessionEventDatabase(context, Json, dstEvent),
                snapshotDatabaseName = dstSnapshot,
                eventDatabaseName = dstEvent,
                restoreKey = restoreKey,
            )
            assertEquals(1, installer.applyPendingRestore())
            assertEquals(0, installer.applyPendingRestore())
            SqliteSessionSnapshotStore(context, Json, dstSnapshot).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, dstEvent).use { events ->
                    assertEquals("恢复的会话", snapshots.read("exported")?.document?.payload
                        ?.get("title")?.toString()?.trim('"'))
                    assertEquals(139L, events.latestSequence("exported"))
                    assertEquals((139L downTo 120L).toList(),
                        events.pageBeforeNewestFirst("exported", limit = 20).map { it.sequence })
                }
            }
        } finally {
            listOf(srcSnapshot, srcEvent, dstSnapshot, dstEvent).forEach(context::deleteDatabase)
            java.io.File(context.filesDir, "local-harness/.sqlite-restore-pending-$restoreKey")
                .deleteRecursively()
        }
    }
}
