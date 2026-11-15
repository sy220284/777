package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.LOCAL_QUEUED_USER_TIMELINE_ACTION
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures SQLite's indexed visible feed includes compact edits and survives a cold reopen. */
@RunWith(AndroidJUnit4::class)
class SqliteQueuedTranscriptDeltaAndroidTest {
    @Test fun indexedTranscriptDeltaSurvivesDatabaseReopenAndKeepsOriginalPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlite-queued-delta-${UUID.randomUUID()}.db"
        val initial = listOf(
            LocalHarnessMessage("user-1", "user", "原始问题", createdAt = 1L),
            LocalHarnessMessage("user-2", "user", "第二个问题", createdAt = 2L),
        )
        val replacement = initial.first().copy(content = "修订问题")
        val index = buildLocalTranscriptRuntimeIndex(listOf(replacement, initial.last()))
        fun openLog(db: SqliteSessionEventDatabase) = LocalSessionEventLog(
            File(context.cacheDir, "$name-events-unused"), Json,
            sessionId = "history", sqlite = db,
        )
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                db.append("history", "user/message", buildJsonObject {
                    put("transcript", encodeTranscriptMessages(initial))
                })
                db.append("history", LOCAL_AGENT_INBOX_EVENT_TYPE, buildJsonObject {
                    put("action", LOCAL_QUEUED_USER_TIMELINE_ACTION)
                    put("transcript_delta", encodeQueuedUserTranscriptDelta("user-1", replacement))
                    put("transcript_index",
                        Json.encodeToJsonElement(LocalTranscriptRuntimeIndex.serializer(), index))
                })
                assertEquals(listOf(1L, 0L),
                    db.pageVisibleBeforeNewestFirst("history", limit = 10).map { it.sequence })
                openLog(db).use { log ->
                    assertEquals(listOf(replacement, initial.last()), LocalSessionTranscriptPager(log).all())
                }
            }
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                openLog(db).use { log ->
                    assertEquals(listOf(replacement, initial.last()), LocalSessionTranscriptPager(log).all())
                    assertEquals(listOf(replacement, initial.last()), projectSessionTranscriptTail(
                        emptyList(), log.visibleTranscriptEventsAfter(-1L), -1L,
                    ).messages)
                    assertEquals(index, projectLocalTranscriptRuntimeIndexTail(
                        LocalTranscriptRuntimeIndex(), log.visibleTranscriptEventsAfter(-1L), -1L,
                    ))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
