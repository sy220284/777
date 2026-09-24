package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionKernelPhaseBcIntegrationTest {
    @Test
    fun interruptedPersistentJobResumesUnderResourceBudgetAndPersistsCompletion() = runTest {
        val root = createTempDir(prefix = "phase-bc-")
        try {
            val store = LocalPersistentJobStore(
                file = File(root, "jobs.json"),
                json = Json { ignoreUnknownKeys = true },
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-resume",
                        label = "只读子代理",
                        status = "running",
                        output = "已完成前置检查",
                        resumeKind = "subagent_readonly",
                        resumePayload = "{\"task\":\"继续检查\"}",
                    ),
                ),
            )

            val manager = LocalJobManager(this, store) { }
            val scheduler = HarnessResourceScheduler(
                HarnessResourceBudget(
                    maxModelRequests = 1,
                    maxAgents = 1,
                    maxTerminals = 1,
                    maxVirtualDisplays = 1,
                    maxLanguageServers = 1,
                ),
            )

            assertTrue(manager.list().contains("job-resume [interrupted]"))
            assertEquals("subagent_readonly", manager.interruptedSnapshots().single().resumeKind)

            val lowPressureBudget = localHistoryBudgetFor(512, scheduler.snapshot().pressure)
            val screenLease = scheduler.acquire(
                HarnessResourceKind.VIRTUAL_DISPLAY,
                owner = "phase-bc-test",
            )
            val constrainedBudget = localHistoryBudgetFor(512, scheduler.snapshot().pressure)

            assertTrue(constrainedBudget.maxHistoryChars < lowPressureBudget.maxHistoryChars)
            assertEquals(1, scheduler.snapshot().activeVirtualDisplays)

            screenLease.close()
            assertEquals(0, scheduler.snapshot().activeVirtualDisplays)

            val resumed = manager.resumePersistent("job-resume") { _, report ->
                scheduler.withResource(HarnessResourceKind.AGENT, owner = "resumed-agent") {
                    report("正在恢复")
                    "恢复完成"
                }
            }
            assertTrue(resumed.contains("已恢复"))

            advanceUntilIdle()

            assertTrue(manager.output("job-resume").contains("[completed]"))
            assertTrue(manager.output("job-resume").contains("恢复完成"))

            val persisted = store.read().single()
            assertEquals("completed", persisted.status)
            assertEquals("subagent_readonly", persisted.resumeKind)
            assertEquals(0, scheduler.snapshot().activeAgents)
            assertTrue(scheduler.snapshot().leases.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resourceLeaseIsReleasedWhenRecoveredWorkFails() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(
                maxModelRequests = 1,
                maxAgents = 1,
                maxTerminals = 1,
                maxVirtualDisplays = 1,
                maxLanguageServers = 1,
            ),
        )

        runCatching {
            scheduler.withResource(HarnessResourceKind.AGENT, owner = "failing-agent") {
                scheduler.withResource(HarnessResourceKind.VIRTUAL_DISPLAY, owner = "failing-screen") {
                    error("boom")
                }
            }
        }

        val snapshot = scheduler.snapshot()
        assertEquals(0, snapshot.activeAgents)
        assertEquals(0, snapshot.activeVirtualDisplays)
        assertTrue(snapshot.leases.isEmpty())
    }
}
