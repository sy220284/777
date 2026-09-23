package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
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
}
