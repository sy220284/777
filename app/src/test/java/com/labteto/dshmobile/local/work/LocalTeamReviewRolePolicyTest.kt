package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentSpecialty
import com.labteto.dshmobile.local.agent.bindLocalAgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTeamReviewRolePolicyTest {
    @Test fun onlyIndependentReadOnlySpecialistsAreEligibleForReviewReceipts() {
        val qualified = setOf("verify", "review", "qa", "security")
        LocalAgentSpecialty.entries.forEach { role ->
            val name = role.wireName
            assertEquals(name, name in qualified, isQualifiedIndependentReviewRole(name))
            if (name in qualified) {
                assertFalse(bindLocalAgentRole(name, "", parentMutationAllowed = true).allowMutation)
            }
        }
    }

    @Test fun executorIntegratorAndGeneralCannotSelfDeclareIndependentAcceptance() {
        for (role in listOf("execute", "integrate", "general", "explore", "plan", "doc")) {
            assertFalse(isQualifiedIndependentReviewRole(role))
        }
        assertTrue(isQualifiedIndependentReviewRole("verifier"))
        assertTrue(isQualifiedIndependentReviewRole("tester"))
    }
}
