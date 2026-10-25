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

    @Test fun everyRoleCompilesToBoundedToolIntentAndVersionedResultContract() {
        LocalAgentSpecialty.entries.forEach { role ->
            val bound = bindLocalAgentRole(role.wireName, "task", parentMutationAllowed = true)
            assertEquals(1, bound.profile.version)
            assertEquals(role.wireName, bound.profile.roleId)
            assertEquals(role.permitsMutation, bound.allowMutation)
            assertTrue(bound.profile.resultContract.isNotBlank())
            if (!role.permitsMutation) {
                assertEquals("readonly", bound.profile.toolIntent)
                assertFalse(bound.allowMutation)
            }
        }
        assertEquals("general", LocalAgentSpecialty.fromWire(null).wireName)
        assertEquals(LocalAgentSpecialty.EXPLORER, LocalAgentSpecialty.fromWire("research"))
    }

    @Test fun securityQaAndDocumentationCannotElevateReadonlyOrWritableParent() {
        listOf("security", "qa", "doc").forEach { role ->
            assertFalse(bindLocalAgentRole(role, "", parentMutationAllowed = false).allowMutation)
            assertFalse(bindLocalAgentRole(role, "", parentMutationAllowed = true).allowMutation)
            assertTrue(renderLocalAgentRoleCatalog().contains(role))
        }
        assertTrue(renderLocalAgentRoleCatalog().contains("version=1"))
        assertTrue(renderLocalAgentRoleCatalog().contains("bounded-write-if-parent-grants"))
    }

    @Test fun unknownRoleFailsBeforeModelOrToolExecution() {
        assertTrue(runCatching {
            bindLocalAgentRole("superuser", "", parentMutationAllowed = true)
        }.isFailure)
    }
}
