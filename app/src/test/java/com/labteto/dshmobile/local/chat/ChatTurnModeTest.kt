package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversation words remain user input; they do not select a response recipe. */
class ChatTurnModeTest {
    @Test
    fun conversationPhrasesDoNotInstallAReactionMode() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青", portrait = "剑客，重情，寡言")
        val scene = ChatContextState(scene = ChatSceneState(location = "院子"))
        val base = projector.project(persona, ChatCharacterState(), scene, "你好", null)
        listOf("继续", "为什么呢", "换个话题", "别出去", "你还记得上次吗").forEach { input ->
            val view = projector.project(persona, ChatCharacterState(), scene, input, null)
            assertEquals(base.stablePrompt, view.stablePrompt)
            assertTrue(view.dynamicPrompt.contains("地点=院子"))
            assertTrue(!view.dynamicPrompt.contains("只推进下一拍"))
            assertTrue(!view.dynamicPrompt.contains("【结束当前节拍】"))
        }
    }
    @Test
    fun unrelatedCurrentRequestsDoNotReactivateOldTasksButExplicitRecallDoes() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青", portrait = "温柔、活泼")
        val state = ChatCharacterState(
            activeGoal = "把宋制襦裙叠好",
            currentAgenda = "准备展示明制披衫",
            unresolvedThreads = listOf("继续展示宋制襦裙"),
        )
        val context = ChatContextState(
            scene = ChatSceneState(location = "房间"),
            continuity = ChatContinuityState(unfinished = listOf("之后继续展示宋制襦裙")),
        )
        val changed = projector.project(persona, state, context, "别提衣服了", null)
        assertTrue(changed.dynamicPrompt.contains("地点=房间"))
        assertTrue(!changed.dynamicPrompt.contains("宋制襦裙"))
        assertTrue(!changed.dynamicPrompt.contains("明制披衫"))
        assertEquals("把宋制襦裙叠好", state.activeGoal)

        val unrelated = projector.project(persona, state, context, "最近心情怎么样？", null)
        assertTrue(!unrelated.dynamicPrompt.contains("宋制襦裙"))

        val recalled = projector.project(persona, state, context, "刚才那套宋制襦裙怎么了？", null)
        assertTrue(recalled.dynamicPrompt.contains("宋制襦裙"))
    }

    @Test
    fun lastTopicPauseAlsoFiltersRelationshipFactsAndStorySummary() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青")
        val state = ChatCharacterState(dynamics = RelationshipDynamics(
            facts = listOf(RelationshipEvidence(
                text = "答应一起展示宋制襦裙", source = "dialogue", confidence = 90,
            )),
            sharedMoments = listOf("上次一起展示明制披衫"),
            unknowns = listOf("是否继续展示服饰"),
        ))
        val context = ChatContextState(attention = ChatAttentionState(
            pausedSubject = "衣服", lastUserSubject = "", updatedSequence = 99L,
        ))
        val projection = projector.project(
            persona, state, context, "嗯",
            "剧情提要：刚才在展示宋制襦裙",
        )
        assertTrue(!projection.dynamicPrompt.contains("宋制襦裙"))
        assertTrue(!projection.dynamicPrompt.contains("明制披衫"))
    }

}
