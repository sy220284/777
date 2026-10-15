package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaGalleryHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun message(id: String, index: Long) =
        LocalHarnessMessage(id = id, role = "assistant", content = "turn-$index", createdAt = index)

    @Test
    fun pageCountTracksAppendDeleteAndReopenWithoutMaterializingHistory() {
        val root = temporary.newFolder("histories")
        val store = PersonaGalleryHistoryStore(root, json)
        store.merge("entry", "story", (1L..120L).map { message("m$it", it) })
        assertEquals(120, store.tail("entry", "story", 3).totalCount)
        assertEquals(listOf("m118", "m119", "m120"), store.tail("entry", "story", 3).messages.map { it.id })
        store.merge("entry", "story", listOf(message("m121", 121)))
        assertEquals(121, store.tail("entry", "story", 2).totalCount)
        assertTrue(store.deleteMessage("entry", "story", galleryMessageArchiveKey(message("m10", 10))))
        assertEquals(120, store.tail("entry", "story", 2).totalCount)
        assertEquals(120, PersonaGalleryHistoryStore(root, json).tail("entry", "story", 2).totalCount)
    }

    @Test
    fun chronologicalHistoryPagesRemainOrderedWithoutOverlapAndRejectStaleCursor() {
        val root = temporary.newFolder("paged-history")
        val store = PersonaGalleryHistoryStore(root, json)
        store.merge("entry", "story", (1L..75L).map { message("m$it", it) })

        val newest = checkNotNull(store.pageBeforeChronological(
            "entry", "story", null, null, null, {},
        ))
        assertEquals((44L..75L).map { "m$it" }, newest.messages.map { it.id })
        val middle = checkNotNull(store.pageBeforeChronological(
            "entry", "story", newest.nextOffset, newest.archiveLength, newest.archiveModified, {},
        ))
        assertEquals((12L..43L).map { "m$it" }, middle.messages.map { it.id })
        val oldest = checkNotNull(store.pageBeforeChronological(
            "entry", "story", middle.nextOffset, middle.archiveLength, middle.archiveModified, {},
        ))
        assertEquals((1L..11L).map { "m$it" }, oldest.messages.map { it.id })
        assertEquals(null, oldest.nextOffset)

        store.merge("entry", "story", listOf(message("m76", 76L)))
        assertEquals(null, store.pageBeforeChronological(
            "entry", "story", newest.nextOffset, newest.archiveLength, newest.archiveModified, {},
        ))
    }

    @Test
    fun malformedArchiveRowIsSkippedWithDiagnosticEvidence() {
        val root = temporary.newFolder("malformed")
        val store = PersonaGalleryHistoryStore(root, json)
        store.merge("entry", "story", listOf(message("m1", 1)))
        File(File(root, "entry"), "story.jsonl").appendText("{invalid-json}\n")
        AppLog.clear()
        val page = store.tail("entry", "story", 3)
        assertEquals(listOf("m1"), page.messages.map { it.id })
        assertTrue(AppLog.snapshot().any {
            it.tag == "PersonaHistory" && it.message.contains("decode_persona_archive_row")
        })
    }

    @Test
    fun tornTailRecoveryInvalidatesStaleCount() {
        val root = temporary.newFolder("torn-history")
        val store = PersonaGalleryHistoryStore(root, json)
        store.merge("entry", "story", listOf(message("m1", 1)))
        assertEquals(1, store.tail("entry", "story", 1).totalCount)
        File(File(root, "entry"), "story.jsonl").appendText("partial")
        assertEquals(1, store.tail("entry", "story", 1).totalCount)
    }
}
