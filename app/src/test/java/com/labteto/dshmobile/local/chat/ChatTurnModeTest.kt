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
}
