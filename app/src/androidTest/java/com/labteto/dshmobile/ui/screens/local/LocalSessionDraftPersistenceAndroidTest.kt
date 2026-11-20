package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionDraftPersistenceAndroidTest {
    @Test fun textAndFileDraftsSurviveCacheRecreationAcrossTwentySessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(
            "chat-next-draft-test", Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().clear().commit())
            val text = LocalSessionDraftCache()
            val files = LocalSessionAttachmentDraftCache()
            text.attach(prefs)
            files.attach(prefs)
            repeat(20) { i ->
                val id = "session-$i"
                text.putLocalDraft(id, "消息草稿 $i")
                files.forSession(id).add(
                    LocalImportedAttachment("file-$i.txt", "inputs/$i.txt", "text/plain", 42L))
                files.persistSession(id)
            }

            val recreatedText = LocalSessionDraftCache()
            val recreatedFiles = LocalSessionAttachmentDraftCache()
            recreatedText.attach(prefs)
            recreatedFiles.attach(prefs)
            repeat(20) { i ->
                val id = "session-$i"
                assertEquals("消息草稿 $i", recreatedText[id])
                assertEquals("inputs/$i.txt", recreatedFiles.forSession(id).single().relativePath)
            }

            recreatedText.putLocalDraft("session-0", "")
            recreatedFiles.forSession("session-0").clear()
            recreatedFiles.persistSession("session-0")
            val finalText = LocalSessionDraftCache().apply { attach(prefs) }
            val finalFiles = LocalSessionAttachmentDraftCache().apply { attach(prefs) }
            assertEquals(null, finalText["session-0"])
            assertTrue(finalFiles.forSession("session-0").isEmpty())
            assertEquals("消息草稿 19", finalText["session-19"])
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
