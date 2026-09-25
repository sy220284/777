package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGalleryEntryTest {
    @Test
    fun carriesSavedEventsAcrossLongStoriesWithoutTreatingThemAsNewInstructions() {
        val story = PersonaGalleryStory(
            id = "story-1",
            history = (1..30).map { index ->
                LocalHarnessMessage("$index", "assistant", "日常片段$index", createdAt = index.toLong())
            },
            chatState = ChatCharacterState(
                relationshipState = "彼此信任",
                unresolvedThreads = listOf("答应过一起去海边"),
                dynamics = RelationshipDynamics(sharedMoments = listOf("曾经在雨里一起等车")),
                updatedAt = 1L,
            ),
        )
        val entry = PersonaGalleryEntry(
            id = "saved",
            persona = PersonaProfile(name = "小岚"),
            stories = listOf(story),
        )

        val context = entry.storyContext("story-1")
        assertTrue(context.contains("彼此信任"))
        assertTrue(context.contains("曾经在雨里一起等车"))
        assertTrue(context.contains("答应过一起去海边"))
        assertTrue(context.contains("日常片段30"))
        assertFalse(context.contains("日常片段1\n"))
    }
}
