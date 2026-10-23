package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    @Test fun atomicBatchAppendAndConcurrentWritersNeverLeaveSequenceGaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlite-batch-test-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                val firstBatch = db.appendBatch("chat", (0 until 100).map { index ->
                    "assistant/message" to buildJsonObject { put("index", index) }
                })
                assertEquals((0L..99L).toList(), firstBatch.map { it.sequence })
                // Invalid batch is rejected without advancing the durable cursor.
                var invalidRejected = false
                try {
                    db.appendBatch("chat", listOf(
                        "turn/start" to buildJsonObject {},
                        "" to buildJsonObject {},
                    ))
                } catch (_: IllegalArgumentException) {
                    invalidRejected = true
                }
                assertTrue(invalidRejected)
                assertEquals(99L, db.latestSequence("chat"))

                val executor = Executors.newFixedThreadPool(4)
                try {
                    val tasks = (0 until 4).map { writer ->
                        Callable {
                            repeat(8) { batch ->
                                db.appendBatch("chat", (0 until 25).map { index ->
                                    "tool/result" to buildJsonObject {
                                        put("writer", writer)
                                        put("batch", batch)
                                        put("index", index)
                                    }
                                })
                            }
                        }
                    }
                    val futures = executor.invokeAll(tasks)
                    futures.forEach { it.get(90, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

                assertEquals(899L, db.latestSequence("chat"))
                val encountered = mutableListOf<Long>()
                db.forEachAfter("chat", -1L) { encountered += it.sequence }
                assertEquals((0L..899L).toList(), encountered)
                assertEquals((899L downTo 880L).toList(),
                    db.pageBeforeNewestFirst("chat", limit = 20).map { it.sequence })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun hundredThousandEventsStillReturnOnlyTheRequestedRecentPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlite-long-session-${UUID.randomUUID()}.db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { store ->
                // Fifty hundred? Exactly 100,000 durable events, always in small
                // WAL batches; nothing needs to be decoded before the latest page.
                repeat(400) { batch ->
                    store.appendBatch("long", (0 until 250).map { i ->
                        "assistant/message" to buildJsonObject { put("ordinal", batch * 250 + i) }
                    })
                }
                assertEquals(99_999L, store.latestSequence("long"))
                val newest = store.pageBeforeNewestFirst("long", limit = 12)
                assertEquals((99_999L downTo 99_988L).toList(), newest.map { it.sequence })
                val earlier = store.pageBeforeNewestFirst(
                    "long", beforeSequenceExclusive = 99_988L, limit = 24,
                )
                assertEquals((99_987L downTo 99_964L).toList(), earlier.map { it.sequence })
                assertEquals(99_999L, store.latest("long", "assistant/message")?.sequence)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

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
                val generation = requireNotNull(db.instance("chat"))
                assertTrue(db.writeProjectionCheckpoint(
                    "chat", "ui-transcript", LocalSessionProjectionCheckpoint(
                        version = 2,
                        identity = generation,
                        throughSequence = 100L,
                        payload = "indexed through 100",
                    ),
                ))
                assertEquals("indexed through 100",
                    db.readProjectionCheckpoint("chat", "ui-transcript", 2)?.payload)
                assertTrue(!db.writeProjectionCheckpoint(
                    "chat", "ui-transcript", LocalSessionProjectionCheckpoint(
                        2, generation, 99L, "stale",
                    ),
                ))
                assertEquals("indexed through 100",
                    db.readProjectionCheckpoint("chat", "ui-transcript", 2)?.payload)
                assertTrue(!db.writeProjectionCheckpoint(
                    "chat", "ui-transcript", LocalSessionProjectionCheckpoint(
                        2, "wrong-generation", 110L, "invalid",
                    ),
                ))
            }
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                assertEquals(identity, db.instance("chat"))
                assertEquals(120L, db.append("chat", "turn/end", buildJsonObject { }).sequence)
                val sequences = mutableListOf<Long>()
                db.forEachAfter("chat", 118L) { sequences += it.sequence }
                assertEquals(listOf(119L, 120L), sequences)
                db.clearSession("chat")
                assertNull(db.instance("chat"))
                assertNull(db.readProjectionCheckpoint("chat", "ui-transcript", 2))
                assertTrue(db.pageBeforeNewestFirst("chat").isEmpty())
                assertEquals(0L, db.append("chat", "turn/start", buildJsonObject {}).sequence)
                assertNotEquals(identity, db.instance("chat"))
                assertNull(db.readProjectionCheckpoint("chat", "ui-transcript", 2))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
