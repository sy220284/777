package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteSessionEventDatabaseAndroidTest {
    @Test fun eventSequencesAndIndexedPagesSurviveReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlite-events-test-${UUID.randomUUID()}.db"
        try {
            var identity = ""
            SqliteSessionEventDatabase(context, Json, name, { 1234L }).use { db ->
                repeat(120) { index ->
                    val event = db.append("chat", if (index % 2 == 0) "assistant/message" else "tool/result",
                        buildJsonObject { put("index", index) })
                    assertEquals(index.toLong(), event.sequence)
                }
                identity = requireNotNull(db.instance("chat"))
                assertEquals(119L, db.latestSequence("chat"))
                assertEquals(118L, db.latest("chat", "assistant/message")?.sequence)
                assertEquals((119L downTo 100L).toList(),
                    db.pageBeforeNewestFirst("chat", limit = 20).map { it.sequence })
                assertEquals((99L downTo 80L).toList(),
                    db.pageBeforeNewestFirst("chat", beforeSequenceExclusive = 100L, limit = 20)
                        .map { it.sequence })
                assertEquals((115L..119L).toList(),
                    db.pageAfter("chat", 114L, 20).map { it.sequence })
                assertEquals(-1L, db.latestSequence("other"))
            }
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                assertEquals(identity, db.instance("chat"))
                assertEquals(120L, db.append("chat", "turn/end", buildJsonObject { }).sequence)
                val sequences = mutableListOf<Long>()
                db.forEachAfter("chat", 118L) { sequences += it.sequence }
                assertEquals(listOf(119L, 120L), sequences)
                db.clearSession("chat")
                assertNull(db.instance("chat"))
                assertTrue(db.pageBeforeNewestFirst("chat").isEmpty())
                assertEquals(0L, db.append("chat", "turn/start", buildJsonObject {}).sequence)
                assertNotEquals(identity, db.instance("chat"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
