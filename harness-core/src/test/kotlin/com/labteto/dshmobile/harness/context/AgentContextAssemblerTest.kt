package com.labteto.dshmobile.harness.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextAssemblerTest {
    @Test
    fun assemblesRulesMemoriesAndHandoffWithinBounds() {
        val assembler = AgentContextAssembler(
            maxRuleChars = 10,
            maxMemoryItems = 1,
            maxSingleMemoryChars = 8,
            maxHandoffChars = 10,
        )

        val text = assembler.compose(
            rules = "1234567890EXTRA",
            memories = listOf(
                AgentContextMemory("PROJECT", "RULE", "abcdefghijk"),
                AgentContextMemory("GLOBAL", "FACT", "should-not-appear"),
            ),
            handoffSummary = "ABCDEFGHIJK",
        )

        assertTrue(text.contains("1234567890"))
        assertFalse(text.contains("EXTRA"))
        assertTrue(text.contains("[project/rule] abcdefgh"))
        assertFalse(text.contains("should-not-appear"))
        assertTrue(text.contains("ABCDEFGHIJ"))
    }

    @Test
    fun emptySectionsDoNotCreateNoise() {
        val text = AgentContextAssembler().compose("", emptyList(), null)
        assertTrue(text.isEmpty())
    }
}
