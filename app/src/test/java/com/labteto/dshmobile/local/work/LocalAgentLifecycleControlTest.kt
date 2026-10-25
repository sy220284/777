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

    @Test fun registrationUsesOneDurableSnapshotAndRejectsCrossSessionOrFakeAgent() {
        assertEquals("job-agent-one",
            requireAgentRegistration(listOf(snapshot()), "session-a", "job-agent-one").info.id)
        assertTrue(runCatching {
            requireAgentRegistration(listOf(snapshot()), "session-b", "job-agent-one")
        }.isFailure)
        assertTrue(runCatching {
            requireAgentRegistration(listOf(snapshot().copy(label = "后台 shell")), "session-a", "job-agent-one")
        }.isFailure)
        assertTrue(runCatching {
            requireAgentRegistration(listOf(snapshot()), "session-a", "")
        }.isFailure)
        val persisted = snapshot().copy(status = "dormant",
            inbox = listOf(QueuedAgentInput(id = "queued-1", content = "继续", memoryInput = "继续")))
        val resolved = requireAgentRegistration(listOf(persisted), "session-a", "job-agent-one")
        assertEquals("dormant", resolved.info.status)
        assertEquals(1, resolved.info.pendingMessageCount)
        assertTrue(resolved.info.canMessage)
        assertTrue(resolved.snapshot === persisted)
    }

    @Test fun registrySeparatesTeamRoutingFromStandaloneAndNeverResolvesCrossSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = LocalJobManager(scope, onChanged = {})
        try {
            assertTrue(jobs.startPersistentResult("子代理：ordinary", "subagent_readonly", "{}",
                ownerSessionId = "session-a", continuable = true,
                requestedId = "job-ordinary-1") { _, _ -> awaitCancellation() }.accepted)
            assertTrue(jobs.startPersistentResult("子代理：team member", "subagent_readonly", "{}",
                ownerSessionId = "session-a", continuable = true,
                requestedId = "job-team-1") { _, _ -> awaitCancellation() }.accepted)
            val registry = LocalAgentRegistry(jobs)
            assertEquals("job-ordinary-1",
                registry.resolveStandaloneChild("session-a", "job-ordinary-1").id)
            assertEquals("job-team-1",
                registry.resolveOwnedChild("session-a", "job-team-1").id)
            assertEquals("job-ordinary-1",
                registry.resolveStandalone("session-a", "job-ordinary-1").snapshot.id)
            val listed = registry.list("session-a")
            assertTrue(listed.contains("job-ordinary-1"))
            assertTrue(listed.contains("job-team-1"))
            assertEquals("没有后台代理", registry.list("session-b"))
            assertEquals(2, jobs.snapshots().size)

            assertTrue(runCatching {
                registry.resolveStandaloneChild("session-a", "job-team-1")
            }.exceptionOrNull()?.message.orEmpty().contains("AGENT_TEAM_LIFECYCLE_VIA_TEAM_PORT"))
            assertTrue(runCatching {
                registry.resolveStandaloneChild("session-b", "job-ordinary-1")
            }.isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test fun triggerTurnWakesOnlyRecipientWhileQueueOnlyAndDuplicateStayPassive() {
        val child = "job-ordinary-1"
        val other = "job-ordinary-2"
        val target = addressedAgentWakeTarget(
            child, LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            accepted = true, requiresResume = true,
        )
        assertEquals(child, target)
        assertTrue(matchesRequestedAgentRecovery(child, target))
        assertFalse(matchesRequestedAgentRecovery(other, target))
        assertEquals(null, addressedAgentWakeTarget(
            child, LocalAgentMessageDeliveryMode.QUEUE_ONLY,
            accepted = true, requiresResume = true,
        ))
        assertEquals(null, addressedAgentWakeTarget(
            child, LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            accepted = true, requiresResume = true, duplicate = true,
        ))
        assertEquals(null, addressedAgentWakeTarget(
            child, LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            accepted = false, requiresResume = true,
        ))
        assertEquals(null, addressedAgentWakeTarget(
            child, LocalAgentMessageDeliveryMode.TRIGGER_TURN,
            accepted = true, requiresResume = false,
        ))
        assertTrue(runCatching {
            addressedAgentWakeTarget("", LocalAgentMessageDeliveryMode.TRIGGER_TURN,
                accepted = true, requiresResume = true)
        }.isFailure)
    }

    @Test fun explicitResumeNeverSelectsAnotherAgentInSameSession() {
        assertTrue(matchesRequestedAgentRecovery("job-first", null))
        assertTrue(matchesRequestedAgentRecovery("job-second", null))
        assertTrue(matchesRequestedAgentRecovery("job-first", "job-first"))
        assertFalse(matchesRequestedAgentRecovery("job-second", "job-first"))
        assertFalse(matchesRequestedAgentRecovery("job-first", "job-second"))
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
