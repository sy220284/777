package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite verifies exact substring semantics, incremental postings and lossless rebuild. */
@RunWith(AndroidJUnit4::class)
class SqliteEventSearchBlockIndexAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun indexedSearchAcrossBlocksAndRebuildPreservesExactEventMatches() {
        val name = "search-blocks-" + UUID.randomUUID() + ".db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                for (index in 0 until 150) {
                    db.append("work", "work/progress", buildJsonObject {
                        put("content", if (index == 73) "needle-WORK-特殊字符"
                            else "ordinary progress $index")
                    })
                }
                val expected = db.search("needle-WORK", 10, -1L)
                assertTrue(expected.contains("73: "))
                assertFalse(expected.contains("72: "))
                assertEquals(expected, db.search("needle-work", 10, -1L))
                assertTrue(db.search("特殊字符", 10, -1L).contains("73: "))
                assertEquals("未找到会话事件", db.search("not-present", 10, -1L))
                // The event type and numeric sequence are part of the full
                // serialized event contract, not merely its JSON payload.
                assertTrue(db.search("work/progress", 2, -1L).contains("结果已分页"))
                assertTrue(db.search("ordinary progress", 1, 2L)
                    .contains("after_sequence="))
                db.rebuildSearchIndex("work")
                assertEquals(expected, db.search("needle-WORK", 10, -1L))
                db.append("work", "assistant/message", buildJsonObject {
                    put("content", "newly indexed content")
                })
                assertTrue(db.search("newly indexed", 10, 100L).contains("150: "))
            }
            SqliteSessionEventDatabase(context, Json, name).use { reopened ->
                assertTrue(reopened.search("特殊字符", 10, -1L).contains("73: "))
                assertTrue(reopened.search("newly indexed", 10, -1L).contains("150: "))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun v4EventArchiveBackfillsSearchBlocksWithoutModifyingAuthoritativeEvents() {
        val name = "search-upgrade-" + UUID.randomUUID() + ".db"
        try {
            SqliteSessionEventDatabase(context, Json, name).use { db ->
                db.append("legacy", "user/message", buildJsonObject {
                    put("content", "preupgrade LEGACY needle")
                })
                val sqlite = db.writableDatabase
                sqlite.execSQL("DROP TABLE session_search_blocks")
                sqlite.execSQL("DROP TABLE session_search_coverage")
                sqlite.version = 4
            }
            SqliteSessionEventDatabase(context, Json, name).use { restored ->
                assertTrue(restored.search("legacy needle", 10, -1L).contains("0: "))
                assertEquals(0L, restored.latestSequence("legacy"))
                assertTrue(restored.search("user/message", 10, -1L).contains("0: "))
                restored.append("legacy", "user/message", buildJsonObject {
                    put("content", "post-upgrade remains searchable")
                })
                assertTrue(restored.search("post-upgrade", 10, -1L).contains("1: "))
            }
        } finally { context.deleteDatabase(name) }
    }
}
