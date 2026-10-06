package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.runtime.LocalAgentRunCoordinator
import com.labteto.dshmobile.local.runtime.LocalAgentRunRecoveryDecision
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalForegroundRecoveryCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun blockedRecoveryIsArchivedOutsideExecutableInbox() = runTest {
        val log = LocalSessionEventLog(File(temporary.root, "events.jsonl"), Json)
        val queue = AgentInputQueue(4)
        queue.offer(QueuedAgentInput("old recovered input", id = "old"))
        try {
            val coordinator = LocalForegroundRecoveryCoordinator(LocalAgentRunCoordinator({ log })) { true }
            val result = coordinator.restore("s", LocalAgentRunRecoveryDecision("r", blockedReason = "unknown tool outcome"),
                emptyList(), queue, log)
            assertFalse(result.autoResumeAllowed)
            assertEquals(0, queue.size())
            val archived = log.latest(LOCAL_AGENT_INBOX_EVENT_TYPE)!!
            assertTrue(archived.data.toString().contains("old recovered input"))
            assertTrue(archived.data.toString().contains("recovery-blocked"))
        } finally { log.close() }
    }

    @Test fun recoveryCommitFailureKeepsOriginalInboxAndDoesNotAdmitNewInput() = runTest {
        val blocked = File(temporary.root, "blocked").apply { writeText("file") }
        val log = LocalSessionEventLog(File(blocked, "events.jsonl"), Json)
        val queue = AgentInputQueue(4)
        queue.offer(QueuedAgentInput("old", id = "old"))
        try {
            val coordinator = LocalForegroundRecoveryCoordinator(LocalAgentRunCoordinator({ log })) { true }
            assertTrue(runCatching {
                coordinator.restore("s", LocalAgentRunRecoveryDecision("r", queuedInput = QueuedAgentInput("new", id = "new")),
                    emptyList(), queue, log)
            }.isFailure)
            assertEquals(listOf("old"), queue.snapshot().map { it.id })
        } finally { log.close() }
    }
}
