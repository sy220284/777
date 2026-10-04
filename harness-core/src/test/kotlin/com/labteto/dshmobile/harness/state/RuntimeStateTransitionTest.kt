package com.labteto.dshmobile.harness.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateTransitionTest {
    @Test
    fun acceptedCandidateBecomesRuntimeState() {
        val policy = RuntimeStateTransitionPolicy<String> { _, candidate ->
            acceptRuntimeStateTransition(candidate.trim())
        }
        val result = policy.resolve("旧值", "  新值  ")
        assertTrue(result.accepted)
        assertEquals("新值", result.value)
    }

    @Test
    fun rejectedCandidateKeepsRuntimeOwnedState() {
        val policy = RuntimeStateTransitionPolicy<String> { current, _ ->
            rejectRuntimeStateTransition(current, "缺少运行时证据")
        }
        val result = policy.resolve("系统事实", "模型候选")
        assertFalse(result.accepted)
        assertEquals("系统事实", result.value)
        assertEquals("缺少运行时证据", result.reason)
    }
}
