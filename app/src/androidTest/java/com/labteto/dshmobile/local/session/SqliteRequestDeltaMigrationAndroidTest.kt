package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.model.replayLocalRequestMessageSurface
import com.labteto.dshmobile.local.model.stableJsonSha256
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** V5 to V6 upgrades must keep every original sequence, digest and recoverable request. */
@RunWith(AndroidJUnit4::class)
class SqliteRequestDeltaMigrationAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyFullSurfacesBecomeVerifiedDeltasAndSearchIndexRebuilds() {
        val name = "request-delta-migration-" + UUID.randomUUID() + ".db"
        val messages = mutableListOf(buildJsonObject {
            put("role", "system")
            put("content", "长期约束".repeat(400))
        })
        var oldBytes = 0L
        val surfaces = mutableListOf<Long>()
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                repeat(45) { i ->
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("content", if (i == 2) "migration-unique-needle".repeat(20)
                            else "工具结果-$i".repeat(70))
                    }
                    val payload = buildJsonObject {
                        put("version", 3)
                        put("digest", stableJsonSha256(JsonArray(messages)))
                        put("messages", JsonArray(messages))
                        put("redacted", false)
                    }
                    oldBytes += payload.toString().toByteArray().size
                    surfaces += db.append("work", "request/message-surface", payload).sequence
                }
                assertTrue(db.search("work", "migration-unique-needle", 10, -1L).contains("2: "))
                // Simulate the released V5 schema without modifying original message facts.
                db.writableDatabase.version = 5
            }
            SqliteSessionEventDatabase(context, Json, name).use { upgraded ->
                assertEquals(6, upgraded.readableDatabase.version)
                assertTrue(upgraded.needsRequestVacuum())
                assertEquals(44L, upgraded.latestSequence("work"))
                var afterBytes = 0L
                var deltaCount = 0
                upgraded.readableDatabase.rawQuery(
                    "SELECT payload FROM session_events WHERE session_id=? ORDER BY sequence",
                    arrayOf("work"),
                ).use { rows ->
                    while (rows.moveToNext()) {
                        val payload = Json.parseToJsonElement(rows.getString(0)).jsonObject
                        afterBytes += rows.getString(0).toByteArray().size
                        if ("base_surface_seq" in payload) deltaCount++
                    }
                }
                assertTrue("delta count $deltaCount", deltaCount >= 40)
                assertTrue("before=$oldBytes after=$afterBytes", afterBytes * 3 < oldBytes)
                // This search must rebuild the derivative from the compressed event rows.
                assertTrue(upgraded.search("work", "migration-unique-needle", 10, -1L).contains("2: "))
                val events = upgraded.pageAfter("work", -1L, 100).associateBy { it.sequence }
                val last = events.getValue(surfaces.last()).let {
                    LocalSessionEventLog.Event(it.sequence, it.type, it.createdAt, it.data)
                }
                val issues = mutableListOf<String>()
                val reconstructed = replayLocalRequestMessageSurface(
                    last,
                    eventAt = { sequence -> events[sequence]?.let {
                        LocalSessionEventLog.Event(it.sequence, it.type, it.createdAt, it.data)
                    } },
                    issues = issues,
                )
                assertTrue(issues.toString(), issues.isEmpty())
                assertEquals(messages, reconstructed)
                // Event + search postings must delete together under FK cascade.
                upgraded.clearSession("work")
                assertEquals(-1L, upgraded.latestSequence("work"))
                upgraded.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM session_search_blocks", null,
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(0, rows.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun migratedDeltaWithInvalidOriginalDigestAbortsWholeUpgrade() {
        val name = "request-delta-reject-" + UUID.randomUUID() + ".db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                db.append("work", "request/message-surface", buildJsonObject {
                    put("version", 3)
                    put("digest", "deliberately-invalid")
                    put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "secret") })))
                    put("redacted", false)
                })
                db.writableDatabase.version = 5
            }
            var rejected = false
            try {
                SqliteSessionEventDatabase(context, Json, name).use { it.readableDatabase }
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("损坏的历史证据必须阻止非原子迁移", rejected)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { raw ->
                assertEquals(5, raw.version)
                raw.rawQuery(
                    "SELECT payload FROM session_events WHERE session_id='work'", null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).contains("deliberately-invalid"))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
