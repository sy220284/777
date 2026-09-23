package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExecutionKernelTest {
    @Test
    fun resourceBudgetScalesWithAndroidMemoryClass() {
        assertEquals(2, localResourceBudgetForMemoryClass(192).maxModelRequests)
        assertEquals(2, localResourceBudgetForMemoryClass(192).maxAgents)

        assertEquals(3, localResourceBudgetForMemoryClass(256).maxModelRequests)
        assertEquals(3, localResourceBudgetForMemoryClass(384).maxAgents)

        assertEquals(4, localResourceBudgetForMemoryClass(512).maxModelRequests)
        assertEquals(4, localResourceBudgetForMemoryClass(1024).maxAgents)
    }

    @Test
    fun adaptiveContextBudgetShrinksUnderPressure() {
        val low = localHistoryBudgetFor(512, HarnessResourcePressure.LOW)
        val medium = localHistoryBudgetFor(512, HarnessResourcePressure.MEDIUM)
        val high = localHistoryBudgetFor(512, HarnessResourcePressure.HIGH)

        assertTrue(low.maxHistoryChars > medium.maxHistoryChars)
        assertTrue(medium.maxHistoryChars > high.maxHistoryChars)
        assertTrue(low.maxToolResultChars > high.maxToolResultChars)
        assertTrue(high.tailChars >= 80_000)
    }

    @Test
    fun resourceBudgetIncludesLongLivedPools() {
        val lowMemory = localResourceBudgetForMemoryClass(192)
        val highMemory = localResourceBudgetForMemoryClass(512)

        assertEquals(1, lowMemory.maxVirtualDisplays)
        assertEquals(2, highMemory.maxVirtualDisplays)
        assertTrue(highMemory.maxTerminals > lowMemory.maxTerminals)
        assertTrue(highMemory.maxLanguageServers > lowMemory.maxLanguageServers)
    }
}
