package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
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
                scene = ChatSceneState(
                    sceneTime = "夜晚",
                    location = "院子",
                    positions = listOf("两人坐在石桌旁"),
                    activeActions = listOf("喝茶"),
                    lastSceneChange = "从书房来到院子",
                ),
                continuity = ChatContinuityState(
                    recurringEvents = listOf("多次表示继续留在院中，目前仍未回屋"),
                    decisions = listOf("明早九点出发"),
                ),
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
        assertTrue(context.contains("地点=院子"))
        assertFalse(context.contains("两人坐在石桌旁"))
        assertFalse(context.contains("喝茶"))
        assertTrue(context.contains("不从旧场景快照继承"))
        assertFalse(context.contains("多次表示继续留在院中"))
        assertTrue(context.contains("明早九点出发"))
        assertFalse(context.contains("日常片段30"))
        assertTrue(context.contains("角色旧回复已归档"))
    }
}
