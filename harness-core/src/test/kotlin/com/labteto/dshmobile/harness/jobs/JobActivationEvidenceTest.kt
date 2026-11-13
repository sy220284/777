package com.labteto.dshmobile.harness.jobs

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class JobActivationEvidenceTest {
    @Test fun queuedRunningStatusDoesNotProveExecutionAndResumeGetsNewEvidence() = runTest {
        val jobs = HarnessJobManager(scope = backgroundScope, onChanged = {})
        val start = jobs.startPersistentResult(
            label = "子代理：activation", resumeKind = "subagent_readonly", resumePayload = "{}",
            ownerId = "session", continuable = true,
        ) { _, _ -> awaitCancellation() }
        val id = requireNotNull(start.id)
        fun registration() = jobs.agentRegistrations("session").single()
        assertEquals("running", registration().info.status)
        assertFalse(registration().executionActive)
        assertEquals(0L, registration().runtimeActivation)
        runCurrent()
        assertTrue(registration().executionActive)
        assertEquals(1L, registration().runtimeActivation)
        jobs.interruptContinuableAgent(id, "session")
        runCurrent()
        assertFalse(registration().executionActive)
        jobs.resumePersistent(id, "session") { _, _ -> awaitCancellation() }
        assertFalse(registration().executionActive)
        assertEquals(1L, registration().runtimeActivation)
        runCurrent()
        assertTrue(registration().executionActive)
        assertEquals(2L, registration().runtimeActivation)
        jobs.kill(id, "session")
    }

    @Test fun restoredRunningSnapshotNeverInventsRuntimeEvidence() = runTest {
        val jobs = HarnessJobManager(scope = backgroundScope, onChanged = {}, initialSnapshots = listOf(
            JobSnapshot(id = "old", label = "子代理：old", status = "running", ownerId = "session",
                resumeKind = "subagent_readonly", resumePayload = "{}", continuable = true),
        ))
        val restored = jobs.agentRegistrations("session").single()
        assertFalse(restored.executionActive)
        assertEquals(0L, restored.runtimeActivation)
        assertEquals("interrupted", restored.info.status)
    }
}
