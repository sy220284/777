package com.labteto.dshmobile.harness.agent

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class AgentRunOwnershipTest {
    @Test
    fun failedDurableClaimPreservesPreviousOwnerAndTerminalCommitsOnlyOnce() {
        val owners = AgentRunOwnership()
        val first = AgentRunIdentity("session", "main", "first")
        val next = first.copy(runId = "next")
        owners.claim(first) { }
        assertThrows(IOException::class.java) { owners.claim(next) { throw IOException("disk full") } }
        assertTrue(owners.isCurrent(first))
        var commits = 0
        assertTrue(owners.commit(first, terminal = true) { commits++ })
        assertFalse(owners.commit(first, terminal = true) { commits++ })
        assertEquals(1, commits)
        owners.claim(next) { }
        assertFalse(owners.retire(first) { error("old recovery cannot retire a new run") })
        assertTrue(owners.isCurrent(next))
    }

    @Test
    fun sameSlotHandoffWaitsForCommitWhileOtherSessionsProceed() {
        val owners = AgentRunOwnership()
        val first = AgentRunIdentity("session", "main", "first")
        val next = first.copy(runId = "next")
        owners.claim(first) { }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claiming = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val commit = executor.submit<Boolean> {
                owners.commit(first) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val claim = executor.submit {
                claiming.countDown()
                owners.claim(next) { }
            }
            assertTrue(claiming.await(5, TimeUnit.SECONDS))
            assertFalse(claim.isDone)
            val unrelated = first.copy(sessionId = "other")
            owners.claim(unrelated) { }
            assertTrue(owners.isCurrent(unrelated))
            release.countDown()
            assertTrue(commit.get(5, TimeUnit.SECONDS))
            claim.get(5, TimeUnit.SECONDS)
            assertFalse(owners.commit(first) { error("late result") })
            assertTrue(owners.isCurrent(next))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun supersededModelReplyCannotStartAToolOrBecomeAssistantOutput() = runTest {
        val owners = AgentRunOwnership()
        val first = AgentRunIdentity("session", "main", "first")
        owners.claim(first) { }
        val events = mutableListOf<AgentEvent>()
        var executed = false
        val engine = AgentTurnEngine(
            model = AgentModel {
                owners.claim(first.copy(runId = "new")) { }
                AgentModelReply("stale", listOf(AgentToolCall("call", "write",
                    kotlinx.serialization.json.buildJsonObject { })))
            },
            tools = AgentToolExecutor { executed = true; AgentToolResult("ok") },
            eventSink = AgentEventSink { events += it },
        )
        var cancelled = false
        try {
            withContext(owners.executionContext(first)) { engine.run("task") }
        } catch (expected: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(executed)
        assertFalse(events.any { it is AgentEvent.AssistantObserved || it is AgentEvent.ToolStarted })
        assertTrue(owners.isCurrent(first.copy(runId = "new")))
    }
}
