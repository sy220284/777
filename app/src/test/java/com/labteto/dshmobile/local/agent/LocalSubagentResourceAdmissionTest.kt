package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentResourceAdmissionTest {
    private fun scheduler(slots: Int) = HarnessResourceScheduler(
        HarnessResourceBudget(maxModelRequests = 2, maxAgents = slots),
    )

    @Test fun saturatedAgentSlotsFailBoundedlyAndRetainTheExistingLease() = runBlocking {
        val resources = scheduler(slots = 1)
        val ancestor = resources.acquire(HarnessResourceKind.AGENT, "ancestor")
        try {
            val failure = runCatching {
                withTimeout(2_000L) {
                    withLocalSubagentAgentSlot(resources, "child", waitMillis = 40L) { "never-run" }
                }
            }
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("NO_AGENT_SLOT"))
            assertEquals(1, resources.snapshot().activeAgents)
        } finally {
            ancestor.close()
        }
        assertEquals(0, resources.snapshot().activeAgents)
        val response = withTimeout(2_000L) {
            withLocalSubagentAgentSlot(resources, "next-child", waitMillis = 1_000L) {
                assertEquals(1, resources.snapshot().activeAgents)
                "completed"
            }
        }
        assertEquals("completed", response)
        assertEquals(0, resources.snapshot().activeAgents)
    }

    @Test fun threeLevelResourcePressureFailsClosedWithoutDeadlock() = runBlocking {
        val resources = scheduler(slots = 2)
        withTimeout(3_000L) {
            withLocalSubagentAgentSlot(resources, "level-1") {
                withLocalSubagentAgentSlot(resources, "level-2", waitMillis = 300L) {
                    assertEquals(2, resources.snapshot().activeAgents)
                    val third = runCatching {
                        withLocalSubagentAgentSlot(resources, "level-3", waitMillis = 50L) {
                            error("must not admit a third slot")
                        }
                    }
                    assertTrue(third.isFailure)
                    assertEquals(2, resources.snapshot().activeAgents)
                }
            }
        }
        assertEquals(0, resources.snapshot().activeAgents)
    }

    @Test fun cancellationRemovesWaiterAndNeverLeaksAnAgentLease() = runBlocking {
        val resources = scheduler(slots = 1)
        val owner = resources.acquire(HarnessResourceKind.AGENT, "ancestor")
        try {
            val waiter = async {
                withLocalSubagentAgentSlot(resources, "queued-child", waitMillis = 5_000L) {
                    "unexpected"
                }
            }
            delay(30L)
            waiter.cancelAndJoin()
            assertEquals(1, resources.snapshot().activeAgents)
        } finally {
            owner.close()
        }
        assertEquals(0, resources.snapshot().activeAgents)
        withTimeout(1_500L) {
            withLocalSubagentAgentSlot(resources, "post-cancel", waitMillis = 500L) {
                assertEquals(1, resources.snapshot().activeAgents)
            }
        }
        assertEquals(0, resources.snapshot().activeAgents)
    }

    @Test fun exceptionsInsideChildAlwaysReleaseItsResourceLease() = runBlocking {
        val resources = scheduler(slots = 2)
        val outcome = runCatching {
            withLocalSubagentAgentSlot(resources, "faulty-child", waitMillis = 500L) {
                throw IllegalStateException("fake model failure")
            }
        }
        assertTrue(outcome.isFailure)
        assertEquals(0, resources.snapshot().activeAgents)
    }
}
