package com.labteto.dshmobile.harness.jobs

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JobInboxAdmissionRegressionTest {
    @Test fun acceptedBoundaryIsLosslessThroughAdmissionAndColdRestore() = runTest {
        val text = "x".repeat(JobInboxContract.MAX_MESSAGE_CHARS - 2) + "尾部"
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(backgroundScope, {}, initialSnapshots = listOf(
            JobSnapshot("child", "子代理：worker", "interrupted", "", resumeKind = "readonly", ownerId = "lead"),
        ), onSnapshotsChanged = { durable = it })
        assertTrue(manager.sendInput("child", QueuedAgentInput(text, id = "message"), "lead").accepted)
        assertEquals(text, manager.peekMessages("child").single().content)
        val restored = HarnessJobManager(backgroundScope, {}, initialSnapshots = durable)
        assertEquals(text, restored.peekMessages("child").single().memoryInput)
        assertThrows(IllegalArgumentException::class.java) { manager.send("child", text + "!", "lead") }
        assertThrows(IllegalArgumentException::class.java) {
            manager.sendInput("child", QueuedAgentInput("short", text + "!", id = "oversize-memory"), "lead")
        }
        assertEquals(listOf("message"), manager.peekMessages("child").map { it.id })
    }
}
