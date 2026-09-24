package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPersonaGalleryTest {
    @Test fun archivesFullHistoryButOnlyRecentDialogueEntersNewChat() {
        val entry = PersonaGalleryEntry(
            id = "gallery-1",
            persona = PersonaProfile(name = "阿青"),
            storyNotes = "雪夜在桥边重逢",
            history = (1..30).map { index ->
                LocalHarnessMessage("$index", if (index % 2 == 0) "assistant" else "user",
                    "对白$index", createdAt = index.toLong())
            },
        )

        val context = entry.storyContext()
        assertTrue(context.contains("雪夜在桥边重逢"))
        assertTrue(context.contains("对白30"))
        assertFalse(context.contains("对白1\n"))
        assertTrue(entry.history.size == 30)
    }
}
