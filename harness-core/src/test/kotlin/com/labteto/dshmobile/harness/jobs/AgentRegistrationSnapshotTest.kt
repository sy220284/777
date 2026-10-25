package com.labteto.dshmobile.harness.jobs

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Agent registry is a projection of one durable Job read, not a second runtime owner. */
class AgentRegistrationSnapshotTest {
    @Test fun registrationReadsStatusIdentityAndInboxFromSameSnapshotWithoutStartingWork() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val inbox = listOf(QueuedAgentInput(
                id = "message-1", content = "继续", memoryInput = "继续",
            ))
            val manager = HarnessJobManager(
                scope = scope,
                onChanged = {},
                initialSnapshots = listOf(
                    JobSnapshot(
                        id = "job-team-1", label = "子代理：审查者", status = "interrupted",
                        resumeKind = "subagent_readonly", resumePayload = "{}",
                        ownerId = "session-a", continuable = true, inbox = inbox,
                    ),
                    JobSnapshot(
                        id = "job-foreign", label = "子代理：其他会话", status = "interrupted",
                        resumeKind = "subagent_readonly", ownerId = "session-b", continuable = true,
                    ),
                    JobSnapshot(
                        id = "job-shell", label = "后台 shell", status = "completed",
                        ownerId = "session-a",
                    ),
                ),
            )
            val registration = manager.agentRegistrations("session-a").single()
            assertEquals("job-team-1", registration.info.id)
            assertEquals("session-a", registration.info.ownerId)
            assertTrue(registration.info.isAgent)
            assertEquals(registration.snapshot.status, registration.info.status)
            assertEquals(registration.snapshot.updatedAt, registration.info.updatedAt)
            assertEquals(registration.snapshot.inbox.size, registration.info.pendingMessageCount)
            assertEquals(listOf("message-1"), registration.snapshot.inbox.map { it.id })
            assertEquals("interrupted", manager.snapshots().single { it.id == "job-team-1" }.status)
            assertTrue(manager.agentRegistrations("session-missing").isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
