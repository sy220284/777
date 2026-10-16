package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalConversationDraftCacheTest {
    @Test fun restoredAttachmentDraftRejectsPathTraversalWithoutDiscardingValidEntries() {
        val valid = com.labteto.dshmobile.local.attachment.LocalImportedAttachment(
            "photo", ".dsh/attachments/photo.png", "image/png", 9, "valid-id")
        val traversal = valid.copy(relativePath = "../secrets.txt")
        val absolute = valid.copy(relativePath = "/tmp/external.txt")
        val doubled = valid.copy(relativePath = ".dsh//attachment.png")
        val windows = valid.copy(relativePath = "C:/Users/private.txt")
        val legacySavedState = Json.encodeToString(listOf(valid, traversal, absolute, doubled, windows))
        val cache = LocalSessionDraftCache()
        cache.restoreAttachments(listOf("session-1", legacySavedState))
        assertEquals(listOf(valid), cache.attachments.value.getValue("session-1"))
        assertEquals(listOf("session-1", Json.encodeToString(listOf(valid))), cache.saveAttachments())
    }

    @Test fun attachmentDraftsSurvivePackSwitchAndSavedStateWithoutMixingSessions() {
        val owner = LocalSessionDraftCache()
        val attachment = com.labteto.dshmobile.local.attachment.LocalImportedAttachment("file", ".dsh/attachments/id.txt", "text/plain", 12, "id")
        owner.addAttachment("a", attachment)
        owner.addAttachment("a", attachment)
        owner.addAttachment("b", attachment.copy(name = "other"))
        val restored = LocalSessionDraftCache()
        restored.restoreAttachments(owner.saveAttachments())
        assertEquals(owner.attachments.value, restored.attachments.value)
        assertEquals(1, restored.attachments.value.getValue("a").size)
        restored.clearAttachments("a")
        assertFalse("a" in restored.attachments.value)
        assertTrue("b" in restored.attachments.value)
    }

    @Test
    fun keepsOnlyTwelveMostRecentlyTouchedSessionDrafts() {
        val drafts = LocalSessionDraftCache()
        repeat(13) { index ->
            drafts.putBoundedLocalDraft("session-$index", "draft-$index")
        }

        val saved = drafts.save()
        assertEquals(24, saved.size)
        assertFalse("session-0" in saved)
        assertEquals("draft-12", drafts["session-12"])
    }

    @Test
    fun touchingExistingDraftMovesItToRecentEndBeforeEviction() {
        val drafts = LocalSessionDraftCache()
        repeat(12) { index ->
            drafts.putBoundedLocalDraft("session-$index", "draft-$index")
        }
        drafts.putBoundedLocalDraft("session-0", "updated")
        drafts.putBoundedLocalDraft("session-12", "new")

        val saved = drafts.save()
        assertTrue("session-0" in saved)
        assertFalse("session-1" in saved)
        assertEquals("updated", drafts["session-0"])
    }

    @Test
    fun blankDraftReleasesCachedSessionEntry() {
        val drafts = LocalSessionDraftCache()
        drafts.putBoundedLocalDraft("session", "draft")

        drafts.putBoundedLocalDraft("session", "   ")

        assertTrue(drafts.save().isEmpty())
    }
    @Test
    fun renderersShareOneOwnerAndSessionDraftsStaySeparate() {
        val owner = LocalSessionDraftCache()
        val v6: com.labteto.dshmobile.uicontract.ConversationDraftUiPort = owner
        val next: com.labteto.dshmobile.uicontract.ConversationDraftUiPort = owner
        v6.updateDraft("chat", "unfinished question")
        next.updateDraft("work", "unfinished task")

        assertEquals("unfinished question", next.drafts.value["chat"])
        assertEquals("unfinished task", v6.drafts.value["work"])
        next.updateDraft("chat", "")
        assertFalse(v6.drafts.value.containsKey("chat"))
        assertEquals("unfinished task", v6.drafts.value["work"])
    }

    @Test
    fun savedStateRestoresRecencyAndOnlyPersistsSubsequentEdits() {
        val owner = LocalSessionDraftCache()
        repeat(12) { owner.updateDraft("session-$it", "draft-$it") }
        owner.updateDraft("session-0", "edited")
        val writes = mutableListOf<List<String>>()
        val recreated = LocalSessionDraftCache.restore(owner.save(), writes::add)
        assertTrue(writes.isEmpty())
        assertEquals(owner.drafts.value, recreated.drafts.value)

        recreated.updateDraft("new-session", "new draft")
        assertEquals("edited", recreated.drafts.value["session-0"])
        assertFalse(recreated.drafts.value.containsKey("session-1"))
        assertEquals(recreated.save(), writes.single())
    }

}
