package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionAttachmentDraftCacheTest {
    @Test fun switchingSessionsPreservesAttachmentsAndIsolation() {
        val cache = LocalSessionAttachmentDraftCache()
        val photo = LocalImportedAttachment("photo.jpg", "inputs/photo.jpg", "image/jpeg", 123L)
        cache.forSession("chat-1").add(photo)
        assertTrue(cache.forSession("chat-2").isEmpty())
        assertEquals(listOf(photo), cache.forSession("chat-1").toList())
        cache.forSession("chat-2").add(LocalImportedAttachment("note.txt", "inputs/note.txt", "text/plain", 8L))
        assertEquals(1, cache.forSession("chat-1").size)
    }

    @Test fun savedStateRestoresValidAttachmentDraftsOnly() {
        val cache = LocalSessionAttachmentDraftCache()
        val valid = LocalImportedAttachment("photo.jpg", "inputs/photo.jpg", "image/jpeg", 123L)
        cache.forSession("chat-1").add(valid)
        cache.forSession("chat-1").add(LocalImportedAttachment("bad", "../secret", "image/png", 1L))
        val restored = LocalSessionAttachmentDraftCache.restore(cache.save())
        assertEquals(listOf(valid), restored.forSession("chat-1").toList())
    }

    @Test fun attachmentDraftsEvictOldestSessionAfterTwelve() {
        val cache = LocalSessionAttachmentDraftCache()
        repeat(13) { i ->
            cache.forSession("chat-$i").add(LocalImportedAttachment("x", "inputs/$i", "text/plain", 1L))
        }
        assertEquals(12, cache.save().size / 2)
        assertTrue(LocalSessionAttachmentDraftCache.restore(cache.save()).forSession("chat-0").isEmpty())
    }
}
