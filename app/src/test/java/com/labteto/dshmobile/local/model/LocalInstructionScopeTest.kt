package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.agent.parentConversationForSubagent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LocalInstructionScopeTest {
    private fun message(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    @Test
    fun forkDropsParentRuntimeAuthorityAndKeepsStableRulesAndUserCorrections() {
        val stable = message("system", "stable").withInstructionScope(LocalInstructionScope.BASE)
        val correction = message("user", "撤销之前的写权限")
        val history = listOf(stable, correction) + listOf(
            LocalInstructionScope.AGENT, LocalInstructionScope.TURN, LocalInstructionScope.APPROVAL,
        ).map { message("developer", "parent-only-$it").withInstructionScope(it) }
        assertEquals(listOf(stable, correction), parentConversationForSubagent(history))
    }

    @Test
    fun provenanceSurvivesLocalHistoryButNeverBecomesAProviderField() {
        val original = message("developer", "grant").withInstructionScope(LocalInstructionScope.APPROVAL)
        val canonical = LocalCanonicalModelCodec.message(original)
        val saved = LocalCanonicalModelCodec.toHistoryMessage(canonical)
        assertEquals(original, saved)
        assertTrue(parentConversationForSubagent(listOf(saved)).isEmpty())
        val wire = LocalCanonicalModelCodec.toLegacyMessages(listOf(canonical), "test", "route").single()
        assertFalse(wire.containsKey(LOCAL_INSTRUCTION_SCOPE))
    }

    @Test
    fun oldUnannotatedRulesAreNotDeletedBasedOnTheirWording() {
        val legacy = message("developer", "过去曾有审批；这里是项目说明")
        assertEquals(listOf(legacy), parentConversationForSubagent(listOf(legacy)))
    }
}
