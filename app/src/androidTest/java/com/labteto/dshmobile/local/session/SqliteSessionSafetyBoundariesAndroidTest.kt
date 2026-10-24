package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.continuePendingInSession
import com.labteto.dshmobile.local.chat.enqueuePendingDurably
import com.labteto.dshmobile.local.chat.loadPendingBatch
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for the two old file-based bypasses of the production SQLite authority. */
@RunWith(AndroidJUnit4::class)
class SqliteSessionSafetyBoundariesAndroidTest {
    @Test fun interruptedDeletionNeverErasesEventsWhileSqlSnapshotSurvives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val snapName = "deletion-safety-$suffix.db"
        val eventName = "deletion-events-$suffix.db"
        val root = File(context.cacheDir, "deletion-safety-$suffix").apply { mkdirs() }
        try {
            SqliteSessionSnapshotStore(context, Json, snapName).use { snapshots ->
                SqliteSessionEventDatabase(context, Json, eventName).use { events ->
                    val stored = LocalHarnessSession(id = "guarded")
                    snapshots.write("guarded",
                        Json.encodeToJsonElement(LocalHarnessSession.serializer(), stored).jsonObject,
                        1L)
                    events.append("guarded", "turn/start", buildJsonObject {})
                    val cleanup = LocalSessionDeletionCleanup(
                        sessionsRoot = root,
                        clearEvents = { ids -> ids.forEach(events::clearSession) },
                        clearToolOutputs = {},
                        persistedSnapshotExists = snapshots::hasRecoverableSnapshot,
                    )
                    cleanup.markIntent("guarded")
                    // Simulate process death after the durable delete intent but before
                    // the SQL primary row is deleted; no legacy JSON file exists.
                    assertTrue(cleanup.recoverPending().isEmpty())
                    assertEquals(0L, events.latestSequence("guarded"))
                    assertTrue(File(root, ".pending-deletions/guarded.pending").exists())
                    snapshots.delete("guarded")
                    assertTrue(cleanup.recoverPending().isEmpty())
                    assertEquals(-1L, events.latestSequence("guarded"))
                    assertFalse(File(root, ".pending-deletions/guarded.pending").exists())
                }
            }
        } finally {
            context.deleteDatabase(snapName)
            context.deleteDatabase(eventName)
            root.deleteRecursively()
        }
    }

    @Test fun chatContinuationWritesPendingFactsOnlyToSqliteAuthority() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "continue-safety-$suffix.db"
        val root = File(context.cacheDir, "continue-safety-$suffix").apply { mkdirs() }
        try {
            SqliteSessionEventDatabase(context, Json, databaseName).use { events ->
                val from = LocalSessionEventLog(
                    File(root, "source.events.jsonl"), Json, sessionId = "source", sqlite = events)
                val to = LocalSessionEventLog(
                    File(root, "target.events.jsonl"), Json, sessionId = "target", sqlite = events)
                val sourceMessage = from.append("assistant/message", buildJsonObject {})
                val original = ChatContextState().enqueuePendingDurably(
                    ChatPendingTurn(
                        sequence = sourceMessage.sequence,
                        assistantMessageId = "reply-1",
                        assistantMessage = "unfinished",
                    ), from,
                )
                val moved = original.continuePendingInSession(
                    root, "source", "target", "direct",
                    eventLogFor = { id -> if (id == "source") from else to },
                )
                assertEquals(1L, events.latestSequence("target"))
                assertEquals("chat/pending-turn", events.latest("target", "chat/pending-turn")?.type)
                assertEquals("reply-1", moved.loadPendingBatch(to, 8).single().assistantMessageId)
                assertFalse(File(root, "source.events.jsonl").exists())
                assertFalse(File(root, "target.events.jsonl").exists())
            }
        } finally {
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }
}
