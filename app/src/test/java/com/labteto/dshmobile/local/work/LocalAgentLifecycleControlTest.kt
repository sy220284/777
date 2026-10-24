package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentLifecycleControlTest {
    private fun info(
        session: String = "session-a",
        status: String = "interrupted",
        pending: Int = 1,
        continuable: Boolean = true,
        isAgent: Boolean = true,
    ) = LocalJobInfo(
        id = "job-agent-one",
        label = "子代理：tester",
        status = status,
        ownerSessionId = session,
        isAgent = isAgent,
        continuable = continuable,
        pendingMessageCount = pending,
    )

    private fun snapshot(kind: String? = "subagent_readonly") = JobSnapshot(
        id = "job-agent-one",
        label = "子代理：tester",
        status = "interrupted",
        resumeKind = kind,
        ownerId = "session-a",
        continuable = true,
    )

    @Test fun cannotInspectOrControlOtherSessionsOrNonAgents() {
        assertEquals("job-agent-one",
            requireOwnedAgentInfo(listOf(info()), "session-a", "job-agent-one").id)
        assertTrue(runCatching {
            requireOwnedAgentInfo(listOf(info()), "session-b", "job-agent-one")
        }.isFailure)
        assertTrue(runCatching {
            requireOwnedAgentInfo(listOf(info(isAgent = false)), "session-a", "job-agent-one")
        }.isFailure)
        assertTrue(runCatching {
            requireOwnedAgentInfo(listOf(info()), "session-a", "")
        }.isFailure)
    }

    @Test fun observationCursorTracksStateAndMailboxWithoutMakingCompletionClaims() {
        val running = info(status = "running", pending = 0).copy(updatedAt = 100L)
        val interrupted = running.copy(status = "interrupted")
        val queued = interrupted.copy(pendingMessageCount = 1)
        assertEquals(localAgentObservationCursor(running), localAgentObservationCursor(running))
        assertTrue(localAgentObservationCursor(running) != localAgentObservationCursor(interrupted))
        assertTrue(localAgentObservationCursor(interrupted) != localAgentObservationCursor(queued))
        assertTrue(localAgentObservationCursor(queued).contains("job-agent-one"))
    }

    @Test fun resumeRequiresCompatiblePersistedChildAndRealWorkOrWaitingSlice() {
        validateAgentResumeAdmission(info(), snapshot())
        validateAgentResumeAdmission(info(status = "waiting_slice", pending = 0), snapshot())
        for (invalid in listOf(
            info(status = "running"),
            info(status = "dormant", pending = 0),
            info(continuable = false),
        )) {
            assertTrue(runCatching {
                validateAgentResumeAdmission(invalid, snapshot())
            }.isFailure)
        }
        assertTrue(runCatching {
            validateAgentResumeAdmission(info(), snapshot("web_fetch"))
        }.isFailure)
    }

    @Test fun interruptionPreservesDurableIdentityButCloseClearsInbox() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = LocalJobManager(scope, onChanged = {})
        try {
            val started = jobs.startPersistentResult(
                label = "子代理：tester",
                resumeKind = "subagent_readonly",
                resumePayload = "{}",
                ownerSessionId = "session-a",
                continuable = true,
                requestedId = "job-agent-one",
            ) { _, _ -> awaitCancellation() }
            assertTrue(started.accepted)
            assertTrue(jobs.interruptContinuableAgent("job-agent-one", "session-a").contains("已中断"))
            assertEquals("interrupted", jobs.snapshotInfos().single().status)
            val input = QueuedAgentInput(
                id = "msg-lifecycle-1",
                content = "继续核对",
                memoryInput = "继续核对",
            )
            val accepted = jobs.sendInput("job-agent-one", input, "session-a")
            assertTrue(accepted.accepted)
            assertEquals(1, jobs.peekMessages("job-agent-one").size)
            assertFalse(jobs.sendInput("job-agent-one", input, "session-b").accepted)
            jobs.kill("job-agent-one", "session-a")
            assertEquals("cancelled", jobs.snapshotInfos().single().status)
            assertTrue(jobs.peekMessages("job-agent-one").isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
