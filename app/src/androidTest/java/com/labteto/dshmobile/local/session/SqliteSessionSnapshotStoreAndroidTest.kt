package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level SQLite WAL, revision, and reopen durability coverage. */
@RunWith(AndroidJUnit4::class)
class SqliteSessionSnapshotStoreAndroidTest {
    @Test fun recentMessageCacheIsBoundedOrderedAndTransactional() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlite-preview-test-" + UUID.randomUUID() + ".db"
        try {
            SqliteSessionSnapshotStore(context, Json, name).use { store ->
                val original = LocalHarnessSession(
                    id = "preview",
                    transcriptWindow = (1..50).map { number ->
                        LocalHarnessMessage(
                            id = "message-$number",
                            role = if (number % 2 == 0) "assistant" else "user",
                            content = "body-$number",
                            createdAt = number.toLong(),
                        )
                    },
                )
                store.write(
                    "preview",
                    Json.encodeToJsonElement(LocalHarnessSession.serializer(), original).jsonObject,
                    100L,
                )
                val recent = store.recentMessages("preview", 12)
                assertEquals((39..50).map { "message-$it" }, recent.map { it.id })
                store.write(
                    "preview",
                    Json.encodeToJsonElement(
                        LocalHarnessSession.serializer(),
                        original.copy(transcriptWindow = original.transcriptWindow.take(3)),
                    ).jsonObject,
                    101L,
                )
                assertEquals(listOf("message-1", "message-2", "message-3"),
                    store.recentMessages("preview", 12).map { it.id })
                assertTrue(store.delete("preview"))
                assertTrue(store.recentMessages("preview", 12).isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun durableWriteRevisionAndDeletionSurviveReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlite-session-test-" + UUID.randomUUID() + ".db"
        try {
            SqliteSessionSnapshotStore(context, Json, name).use { first ->
                val initial = buildJsonObject {
                    put("id", "s1")
                    put("title", "第一次")
                }
                first.write("s1", initial, 10L)
                assertEquals(1L, first.lastModified("s1"))
                first.write("s1", buildJsonObject {
                    put("id", "s1")
                    put("title", "第二次")
                }, 10L)
                assertEquals(2L, first.lastModified("s1"))
                assertEquals(listOf("s1"), first.ids())
                assertEquals("第二次", first.readSummaries().single().title)
                // Snapshot's visible window is the source of the materialized preview.
                assertTrue(first.recentMessages("s1", 12).isEmpty())
                assertEquals(10L, first.readSummaries().single().updatedAt)
            }
            SqliteSessionSnapshotStore(context, Json, name).use { second ->
                assertEquals("第二次", second.read("s1")?.document?.payload?.get("title")?.jsonPrimitive?.content)
                assertEquals(2L, second.lastModified("s1"))
                assertTrue(second.hasRecoverableSnapshot("s1"))
                assertEquals(1, second.readSummaries().size)
                assertTrue(second.delete("s1"))
                assertNull(second.read("s1"))
                assertFalse(second.hasRecoverableSnapshot("s1"))
                assertTrue(second.readSummaries().isEmpty())
                assertTrue(second.recentMessages("s1", 12).isEmpty())
            }
            SqliteSessionSnapshotStore(context, Json, name).use { third ->
                assertTrue(third.ids().isEmpty())
                assertNull(third.lastModified("s1"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
