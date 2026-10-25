package com.labteto.dshmobile.local.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Admission invariants for a bounded, read-only recursive research tree. */
class LocalAgentSubtreeAdmissionTest {
    private fun readonly(depth: Int, maxDepth: Int) = LocalSubagentLaunchSpec(
        task = "investigate", modelOverride = null, maxSteps = 12,
        capabilities = LocalSubagentCapabilities(depth = depth, maxDepth = maxDepth),
    )

    @Test fun acceptsTwoAndThreeLevelReadOnlyChildren() {
        assertEquals(3, validateLocalSubagentLaunchSpec(readonly(1, 3)).capabilities.maxDepth)
        assertEquals(2, validateLocalSubagentLaunchSpec(readonly(2, 3)).capabilities.depth)
        assertEquals(3, validateLocalSubagentLaunchSpec(readonly(3, 3)).capabilities.depth)
    }

    @Test fun blocksAuthorityEscalationAndPersistentRecursiveResume() {
        assertTrue(runCatching {
            validateLocalSubagentLaunchSpec(readonly(1, 3).copy(
                capabilities = LocalSubagentCapabilities(allowMutation = true, maxDepth = 3)))
        }.isFailure)
        assertTrue(runCatching {
            validateLocalSubagentLaunchSpec(readonly(1, 3).copy(
                capabilities = LocalSubagentCapabilities(continuable = true, maxDepth = 3)))
        }.isFailure)
        assertTrue(runCatching {
            validateLocalSubagentLaunchSpec(readonly(1, 3).copy(
                capabilities = LocalSubagentCapabilities(virtualScreen = true, maxDepth = 3)))
        }.isFailure)
        assertTrue(runCatching {
            validateLocalSubagentLaunchSpec(readonly(2, 3).copy(
                capabilities = LocalSubagentCapabilities(depth = 4, maxDepth = 3)))
        }.isFailure)
    }

    @Test fun rootLaunchPassesExactDepthGrantToChildren() {
        val root = buildLocalDelegatedLaunchSpec(
            kind = LocalAgentDelegationKind.FOREGROUND_READONLY,
            task = "review", instructions = "", modelOverride = null,
            maxSteps = 12, parentCallId = "tool-root", maxDepth = 3,
        )
        assertEquals(3, root.capabilities.maxDepth)
        assertFalse(root.capabilities.allowMutation)
        assertFalse(root.capabilities.continuable)
        assertEquals(1, root.capabilities.depth)
    }

    @Test fun leafCannotRetainDelegationAndInheritsNoMutationGrant() {
        val parent = LocalSubagentCapabilities(
            depth = 2, maxDepth = 3, allowMutation = false,
            toolAllowlist = setOf("read", "subagent"),
        )
        val leaf = compileNestedReadonlyCapabilities(parent)
        assertEquals(3, leaf.depth)
        assertEquals(setOf("read"), leaf.toolAllowlist)
        assertFalse(leaf.allowMutation)
        assertFalse(leaf.continuable)
        assertTrue(runCatching { compileNestedReadonlyCapabilities(leaf) }.isFailure)
    }

    @Test fun concurrentDescendantBudgetHasFixedHardCap() {
        val budget = LocalAgentSubtreeBudget(maxDescendants = 2)
        budget.claim()
        budget.claim()
        assertEquals(2, budget.used())
        assertTrue(runCatching { budget.claim() }.isFailure)
        assertEquals(2, budget.used())
    }
    @Test fun duplicatedToolReceiptCannotCreateAnotherDescendant() {
        val budget = LocalAgentSubtreeBudget(maxDescendants = 3, maxDescendantModelSteps = 10)
        budget.claim("session:slot:run-1:tool-1")
        assertEquals(1, budget.used())
        assertTrue(runCatching { budget.claim("session:slot:run-1:tool-1") }
            .exceptionOrNull()?.message.orEmpty().contains("AGENT_SUBTREE_DUPLICATE_DELEGATION"))
        assertEquals(1, budget.used())
        budget.claim("session:slot:run-2:tool-1")
        budget.claim("session:other-slot:run-2:tool-1")
        assertEquals(3, budget.used())
    }

    @Test fun exhaustedSubtreeCannotForgetEarlierAdmissionOrUseAnotherIdentity() {
        val budget = LocalAgentSubtreeBudget(maxDescendants = 1)
        budget.claim("root:one")
        assertTrue(runCatching { budget.claim("root:two") }
            .exceptionOrNull()?.message.orEmpty().contains("AGENT_SUBTREE_FANOUT_EXHAUSTED"))
        assertTrue(runCatching { budget.claim("root:one") }
            .exceptionOrNull()?.message.orEmpty().contains("AGENT_SUBTREE_DUPLICATE_DELEGATION"))
        assertEquals(1, budget.used())
    }

}
