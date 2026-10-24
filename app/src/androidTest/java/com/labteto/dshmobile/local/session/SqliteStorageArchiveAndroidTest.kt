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
    @Test fun exportRejectsSnapshotProjectionAheadOfDurableEventAuthority() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = UUID.randomUUID().toString()
        val snapName = "cursor-safety-$uid-snap.db"
        val eventName = "cursor-safety-$uid-event.db"
        try {
            SqliteSessionSnapshotStore(context, Json, snapName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    val invalid = LocalHarnessSession(
                        id = "mismatch", transcriptProjectedThroughSequence = 50L,
                    )
                    snapshots.write(
                        "mismatch",
                        Json.encodeToJsonElement(LocalHarnessSession.serializer(), invalid).jsonObject,
                        1L,
                    )
                    events.append("mismatch", "assistant/message", buildJsonObject {})
                    val manager = SqliteSessionStorageManager(
                        context, snapshots, events, snapName, eventName, "cursor-$uid",
                    )
                    var blocked = false
                    try {
                        manager.exportAll(ByteArrayOutputStream())
                    } catch (_: IllegalStateException) {
                        blocked = true
                    }
                    assertTrue("Cursor-ahead snapshot must not be exported", blocked)
                    assertEquals(0L, events.latestSequence("mismatch"))
                }
            }
        } finally {
            context.deleteDatabase(snapName)
            context.deleteDatabase(eventName)
        }
    }

    @Test fun stagedArchiveCannotOverwriteNewBusinessEventsCommittedInWalDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = UUID.randomUUID().toString()
        val srcSnap = "wal-source-$uid-snap.db"
        val srcEvents = "wal-source-$uid-events.db"
        val dstSnap = "wal-destination-$uid-snap.db"
        val dstEvents = "wal-destination-$uid-events.db"
        val key = "wal-refuse-$uid"
        val pending = java.io.File(context.filesDir, "local-harness/.sqlite-restore-pending-$key")
        try {
            val archive = ByteArrayOutputStream()
            SqliteSessionSnapshotStore(context, Json, srcSnap).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, srcEvents).use { events ->
                    snapshots.write(
                        "incoming",
                        Json.encodeToJsonElement(LocalHarnessSession.serializer(),
                            LocalHarnessSession(id = "incoming", title = "需要恢复的备份")).jsonObject,
                        10L,
                    )
                    events.append("incoming", "user/message", buildJsonObject { put("text", "old") })
                    SqliteSessionStorageManager(context, snapshots, events, srcSnap, srcEvents,
                        "wal-export-$uid").exportAll(archive)
                }
            }
            SqliteSessionSnapshotStore(context, Json, dstSnap).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, dstEvents).use { events ->
                    val manager = SqliteSessionStorageManager(context, snapshots, events,
                        dstSnap, dstEvents, key)
                    assertEquals(1, manager.stageRestore(archive.toByteArray()))
                    assertTrue(java.io.File(pending, "READY").isFile)
                    // An active user can create a new business fact between staging and restart.
                    // It is never legal to overwrite that event database with the staged ZIP.
                    events.append("already-has-user-data", "work/tool-result",
                        buildJsonObject { put("receipt", "must-preserve") })
                    assertEquals(0L, events.latestSequence("already-has-user-data"))
                }
            }
            val resumed = SqliteSessionStorageManager(context,
                SqliteSessionSnapshotStore(context, Json, dstSnap),
                SqliteSessionEventDatabase(context, Json, dstEvents),
                dstSnap, dstEvents, key)
            val rejected = runCatching { resumed.applyPendingRestore() }.exceptionOrNull()
            assertTrue("A staged archive cannot replace a committed business WAL fact",
                rejected is IllegalArgumentException)
            assertTrue("Staged data must be retained for explicit recovery", pending.isDirectory)
            SqliteSessionEventDatabase(context, Json, dstEvents).use { events ->
                assertEquals(0L, events.latestSequence("already-has-user-data"))
                assertTrue(events.pageAfter("already-has-user-data", -1L)
                    .single().data.toString().contains("must-preserve"))
                assertEquals(-1L, events.latestSequence("incoming"))
            }
        } finally {
            listOf(srcSnap, srcEvents, dstSnap, dstEvents).forEach(context::deleteDatabase)
            pending.deleteRecursively()
        }
    }

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
            // Fault injection: simulate process death after the snapshot SQLite file
            // was installed but before the event database was installed.
            // The next launch must recognize the identical file and finish the
            // outstanding event copy rather than failing or deleting existing data.
            val stagedSnapshot = java.io.File(
                context.filesDir, "local-harness/.sqlite-restore-pending-$restoreKey/snapshot.sqlite",
            )
            assertTrue(stagedSnapshot.isFile)
            stagedSnapshot.copyTo(context.getDatabasePath(dstSnapshot), overwrite = true)
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
