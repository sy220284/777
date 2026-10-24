package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNestedDelegationContractTest {
    @Test fun acceptsBoundedReadonlyResearchAndPreservesInstructions() {
        val args = buildJsonObject {
            put("task", "Review history")
            put("role", "verify")
            put("instructions", "Show evidence")
            put("max_steps", 6)
        }
        val parsed = parseLocalNestedDelegation(args, 10)
        assertEquals("Review history", parsed.task)
        assertEquals(6, parsed.maxSteps)
        assertTrue(parsed.instructions.contains("verify"))
        assertTrue(parsed.instructions.contains("Show evidence"))
    }

    @Test fun rejectsModelProvidedAuthorityEscalationOrBackgroundFields() {
        for (illegal in listOf("model", "run_in_background", "virtual_screen",
            "context_mode", "allowed_tools", "max_depth")) {
            val args = buildJsonObject {
                put("task", "review")
                put(illegal, true)
            }
            assertTrue(illegal, runCatching { parseLocalNestedDelegation(args, 10) }.isFailure)
        }
        assertTrue(runCatching {
            parseLocalNestedDelegation(buildJsonObject {
                put("task", "review")
                put("role", "execute")
            }, 10)
        }.isFailure)
    }

    @Test fun rejectsWrongJsonTypesRatherThanSilentlyUsingDefaults() {
        for (field in listOf("task", "role", "instructions")) {
            val args = buildJsonObject {
                put("task", "review")
                put(field, true)
            }
            assertTrue(field, runCatching { parseLocalNestedDelegation(args, 10) }.isFailure)
        }
        assertTrue(runCatching {
            parseLocalNestedDelegation(buildJsonObject {
                put("task", "review")
                put("max_steps", "4")
            }, 10)
        }.isFailure)
        assertTrue(runCatching {
            parseLocalNestedDelegation(buildJsonObject {
                put("task", "review")
                put("max_steps", true)
            }, 10)
        }.isFailure)
    }

    @Test fun rejectsInvalidStepBudgetsAndOversizedTask() {
        for (steps in listOf(0, -1, 11, 25)) {
            assertTrue(runCatching {
                parseLocalNestedDelegation(buildJsonObject {
                    put("task", "read")
                    put("max_steps", steps)
                }, 10)
            }.isFailure)
        }
        assertTrue(runCatching {
            parseLocalNestedDelegation(buildJsonObject {
                put("task", "X".repeat(16_001))
            }, 10)
        }.isFailure)
    }

    @Test fun enforcesSharedTreeModelStepAndDescendantCap() {
        val budget = LocalAgentSubtreeBudget(maxDescendants = 2, maxDescendantModelSteps = 3)
        budget.claim()
        budget.claim()
        assertEquals(2, budget.used())
        assertTrue(runCatching { budget.claim() }.isFailure)
        repeat(3) { budget.claimModelStep() }
        assertEquals(3, budget.usedModelSteps())
        assertTrue(runCatching { budget.claimModelStep() }.isFailure)
        assertEquals(3, budget.usedModelSteps())
    }
}
