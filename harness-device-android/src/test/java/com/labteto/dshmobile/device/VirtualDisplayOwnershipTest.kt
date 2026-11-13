package com.labteto.dshmobile.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayOwnershipTest {
    @Test
    fun agentDisplayIsNeverVisibleOrAccessibleFromToolScope() {
        val owner = VirtualDisplayOwnership()
        owner.registerTool("tool-display")
        owner.registerAgent("agent-display")
        assertTrue(owner.isTool("tool-display"))
        assertFalse(owner.isTool("agent-display"))
        owner.requireTool("tool-display")
        assertThrows(IllegalArgumentException::class.java) { owner.requireTool("agent-display") }
        assertThrows(IllegalArgumentException::class.java) { owner.requireTool("forged-id") }
        assertFalse(owner.releaseTool("agent-display"))
        assertTrue(owner.isAgent("agent-display"))
        assertTrue(owner.releaseTool("tool-display"))
        assertFalse(owner.isTool("tool-display"))
        assertTrue(owner.releaseAgent("agent-display"))
    }

    @Test
    fun agentReleaseCannotStealToolDisplayAndIdReuseRequiresRelease() {
        val owner = VirtualDisplayOwnership()
        owner.registerTool("same-id")
        assertFalse(owner.releaseAgent("same-id"))
        assertTrue(owner.isTool("same-id"))
        assertThrows(IllegalStateException::class.java) { owner.registerAgent("same-id") }
        assertTrue(owner.releaseTool("same-id"))
        owner.registerAgent("same-id")
        assertFalse(owner.isTool("same-id"))
        assertTrue(owner.isAgent("same-id"))
        owner.clear()
        assertFalse(owner.isAgent("same-id"))
    }
}
