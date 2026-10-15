package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPostTurnRulesTest {
    @Test fun identicalRulesAreSharedWithoutDroppingCharacterEvidence() {
        val (shared, compact) = compactGroupPostTurnRules(listOf(
            "人物甲状态\n规则：\n必须证据真实",
            "人物乙状态\n规则：\n必须证据真实",
        ))
        assertEquals("规则：\n必须证据真实", shared)
        assertEquals(listOf("人物甲状态", "人物乙状态"), compact)
    }

    @Test fun nonmatchingRulesArePreservedPerCharacter() {
        val source = listOf("甲\n规则：\nA", "乙\n规则：\nB")
        val (shared, compact) = compactGroupPostTurnRules(source)
        assertTrue(shared.isBlank())
        assertEquals(source, compact)
    }
    @Test fun actualPersonaPromptsShareTailRulesWithoutLeakingCharacterState() {
        val builder = ChatInteractionPromptBuilder()
        val first = builder.prompt(PersonaProfile(name = "甲"), ChatCharacterState(mood = "开心"), "问候", "你好")
        val second = builder.prompt(PersonaProfile(name = "乙"), ChatCharacterState(mood = "疲倦"), "问候", "晚上好")
        val (shared, contexts) = compactGroupPostTurnRules(listOf(first, second))
        assertTrue(shared.contains("stateEvidence"))
        assertTrue(contexts[0].contains("开心"))
        assertTrue(contexts[1].contains("疲倦"))
        assertFalse(contexts[0].contains("疲倦"))
        assertFalse(contexts[1].contains("开心"))
    }

}
