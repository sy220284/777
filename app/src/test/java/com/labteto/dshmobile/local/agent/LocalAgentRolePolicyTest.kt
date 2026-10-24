package com.labteto.dshmobile.local.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentRolePolicyTest {
    @Test fun legacyDefaultRolePreservesTheInputAndGrant() {
        val role = bindLocalAgentRole(null, "legacy", parentMutationAllowed = true)
        assertEquals("legacy", role.instructions)
        assertTrue(role.allowMutation)
    }

    @Test fun verifierReducesForkWriteGrantWithoutPretendingToExecuteChecks() {
        val role = bindLocalAgentRole("verify", "verify task", parentMutationAllowed = true)
        assertEquals(LocalAgentSpecialty.VERIFIER, role.role)
        assertFalse(role.allowMutation)
        assertTrue(role.instructions.contains("PARTIAL"))
    }

    @Test fun writableRoleCannotElevateReadonlyDelegation() {
        assertTrue(runCatching {
            bindLocalAgentRole("execute", "", parentMutationAllowed = false)
        }.isFailure)
        assertTrue(runCatching {
            bindLocalAgentRole("integrate", "", parentMutationAllowed = false)
        }.isFailure)
    }

    @Test fun unknownRoleFailsBeforeModelOrToolExecution() {
        assertTrue(runCatching {
            bindLocalAgentRole("superuser", "", parentMutationAllowed = true)
        }.isFailure)
    }
}
