package com.labteto.dshmobile.harness.agent

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInputQueueMutationTest {
    private fun user(id: String) = QueuedAgentInput(id = id, content = "input-" + id)
    private fun internal(id: String) = QueuedAgentInput(
        id = id, content = "system-" + id, kind = QueuedAgentInputKind.AGENT_MESSAGE,
        agentSource = buildJsonObject {},
        memoryInput = "",
    )

    @Test fun editIsCommittedAndRollsBackWhenPersistenceFails() {
        val queue = AgentInputQueue()
        queue.offer(user("a"))
        queue.offer(user("b"))
        var persisted = emptyList<QueuedAgentInput>()
        assertTrue(queue.editUserCommitted("a", "updated") { persisted = it })
        assertEquals("updated", persisted.first().content)
        assertEquals("updated", queue.snapshot().first().memoryInput)
        val failed = runCatching {
            queue.editUserCommitted("b", "should rollback") { error("disk is full") }
        }
        assertTrue(failed.isFailure)
        assertEquals("input-b", queue.snapshot()[1].content)
    }

    @Test fun deleteProtectsSystemMessageAndRollsBackOnFailedCommit() {
        val queue = AgentInputQueue()
        queue.offer(internal("system"))
        queue.offer(user("first"))
        assertFalse(queue.removeUserCommitted("system") { error("must not be called") })
        val before = queue.snapshot()
        assertTrue(runCatching {
            queue.removeUserCommitted("first") { error("I/O error") }
        }.isFailure)
        assertEquals(before, queue.snapshot())
        assertTrue(queue.removeUserCommitted("first") { })
        assertEquals(listOf("system"), queue.snapshot().map { it.id })
    }

    @Test fun reorderingPreservesInternalSlotsAndIsAtomic() {
        val queue = AgentInputQueue()
        queue.restore(listOf(user("a"), internal("system"), user("b"), user("c")))
        assertFalse(queue.reorderUsersCommitted(listOf("c", "a")) { error("incomplete IDs") })
        assertFalse(queue.reorderUsersCommitted(listOf("a", "a", "c")) { error("duplicate ID") })
        assertTrue(queue.reorderUsersCommitted(listOf("c", "a", "b")) { })
        assertEquals(listOf("c", "system", "a", "b"), queue.snapshot().map { it.id })
        val before = queue.snapshot()
        assertTrue(runCatching {
            queue.reorderUsersCommitted(listOf("b", "c", "a")) { error("write failed") }
        }.isFailure)
        assertEquals(before, queue.snapshot())
    }

    @Test fun richContentNeedsOwnerRebuildBeforeEditing() {
        val queue = AgentInputQueue()
        queue.offer(user("rich").copy(modelMessage = buildJsonObject {}))
        val failed = runCatching {
            queue.editUserCommitted("rich", "new text") {}
        }
        assertTrue(failed.isFailure)
        assertEquals("input-rich", queue.snapshot().single().content)
    }
}
