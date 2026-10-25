package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentModelResourceOwnerTest {
    @Test fun actualRunAndSlotCarryModelLeaseIdentity() {
        assertEquals("agent-model:child-slot:agent-run-1",
            localAgentModelResourceOwner("agent-run-1", "child-slot"))
        assertTrue(localAgentModelResourceOwner("run2", "another-slot").contains("another-slot"))
    }

    @Test fun missingContextCannotInventAnotherRunsIdentity() {
        assertEquals("model-request:unbound", localAgentModelResourceOwner(null, null))
        assertEquals("model-request:unbound", localAgentModelResourceOwner("", "slot"))
        val owner = localAgentModelResourceOwner("r".repeat(200), "s".repeat(200))
        assertFalse(owner.contains("r".repeat(65)))
        assertTrue(owner.length <= 150)
    }
}
