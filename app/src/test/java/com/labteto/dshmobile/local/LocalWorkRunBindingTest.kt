package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.presentation.toWorkSurfaceUiState
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.runtime.LocalHarnessResourceState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import com.labteto.dshmobile.local.work.LocalWorkState
import com.labteto.dshmobile.local.work.toLocalWorkRunState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkRunBindingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun visibleWorkReflectsDeviceLeaseActivationAndRevocation() = runTest {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(
            sessionId = "work",
            usageMode = LocalUsageMode.WORK,
        ))
        val registry = LocalWorkRunRegistry(runtime)
        val resourceLeases = List(2) {
            runtime.resourceScheduler.acquire(HarnessResourceKind.AGENT)
        }
        try {
            val binding = binding(
                runtime.state.value.copy(
                    work = runtime.state.value.work.copy(deviceApprovalLease = true),
                ),
            )

            registry.mirrorVisible(binding)

            assertTrue(runtime.state.value.toWorkSurfaceUiState().deviceApprovalLease)
            assertEquals(2, runtime.state.value.kernel.resources.activeAgents)
            binding.state.value = binding.state.value.copy(
                work = binding.state.value.work.copy(deviceApprovalLease = false),
            )
            registry.mirrorVisible(binding)
            assertFalse(runtime.state.value.toWorkSurfaceUiState().deviceApprovalLease)
        } finally {
            resourceLeases.forEach { it.close() }
        }
    }

    @Test fun backgroundWorkCannotPublishDeviceLeaseIntoAnotherSession() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(sessionId = "other", usageMode = LocalUsageMode.CHAT))
        val visibleBaseline = runtime.state.value
        val registry = LocalWorkRunRegistry(runtime)
        val binding = binding(LocalHarnessState(
            sessionId = "work",
            usageMode = LocalUsageMode.WORK,
            work = LocalWorkState(deviceApprovalLease = true),
        ))

        registry.mirrorVisible(binding)
        assertEquals(visibleBaseline, runtime.state.value)
        // The active Session owner can change before its visible aggregate projection catches up.
        runtime.activateSession("work")
        registry.mirrorVisible(binding)
        assertEquals(visibleBaseline, runtime.state.value)
    }

    @Test fun teardownClearsRunInteractionAndDeviceLeaseTogether() = runTest {
        val binding = binding(LocalHarnessState(
            sessionId = "work",
            kernel = LocalKernelState(running = true, queuedInputCount = 1),
            work = LocalWorkState(deviceApprovalLease = true, pendingQuestion = LocalQuestion("question", "继续吗？")),
        ))

        binding.cancelAndJoin()

        assertFalse(binding.state.value.work.deviceApprovalLease)
        assertFalse(binding.state.value.kernel.running)
        assertEquals(0, binding.state.value.kernel.queuedInputCount)
        assertNull(binding.state.value.work.pendingQuestion)
    }

    @Test fun schedulerChangeUpdatesDetachedWorkWithoutCopyingItsExecutionIntoChat() = runTest {
        val runtime = LocalRuntimeStateStore()
        val visible = runtime.initialize(LocalHarnessState(sessionId = "chat", usageMode = LocalUsageMode.CHAT))
        val binding = binding(LocalHarnessState(
            sessionId = "work",
            kernel = LocalKernelState(running = true, queuedInputCount = 2, contextChars = 32_000),
        ))
        val registry = LocalWorkRunRegistry(runtime).apply { attach(binding) }
        val initialBudget = binding.state.value.kernel.contextBudgetChars
        val leases = List(3) { runtime.resourceScheduler.acquire(HarnessResourceKind.AGENT) } +
            runtime.resourceScheduler.acquire(HarnessResourceKind.MODEL_REQUEST)
        try {
        assertEquals(0, visible.value.kernel.resources.activeAgents)
        assertEquals(3, binding.state.value.kernel.resources.activeAgents)
        assertTrue(binding.state.value.kernel.contextBudgetChars < initialBudget)
        assertEquals(binding.state.value.kernel.contextBudgetChars, visible.value.kernel.contextBudgetChars)
        assertTrue(binding.state.value.kernel.running)
        assertFalse(visible.value.kernel.running)
        assertEquals(2, binding.state.value.kernel.queuedInputCount)
        assertEquals(32_000, binding.state.value.kernel.contextChars)
        registry.mirrorVisible(binding)
        assertEquals("chat", visible.value.sessionId)
        assertFalse(visible.value.kernel.running)
        } finally {
            leases.forEach { it.close() }
        }
        assertEquals(0, binding.state.value.kernel.resources.activeAgents)
        assertEquals(initialBudget, binding.state.value.kernel.contextBudgetChars)
    }

    @Test fun reentrantResourceChangeCannotReplayOldBudgetIntoWorkBinding() = runTest {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(sessionId = "chat", usageMode = LocalUsageMode.CHAT))
        var nestedLease: com.labteto.dshmobile.harness.resource.HarnessResourceLease? = null
        var changed = false
        runtime.observeResourceSnapshots { snapshot ->
            if (snapshot.activeAgents == 1 && !changed) {
                changed = true
                nestedLease = kotlinx.coroutines.runBlocking {
                    runtime.resourceScheduler.acquire(HarnessResourceKind.MODEL_REQUEST)
                }
            }
        }
        val binding = binding(LocalHarnessState(sessionId = "work"))
        LocalWorkRunRegistry(runtime).attach(binding)
        var observedModels = -1
        runtime.observeResourceSnapshots { observedModels = it.activeModelRequests }
        val agent = runtime.resourceScheduler.acquire(HarnessResourceKind.AGENT)
        try {
            assertEquals(1, runtime.resourceSnapshot().activeModelRequests)
            assertEquals(1, observedModels)
            assertEquals(1, binding.state.value.kernel.resources.activeModelRequests)
            assertEquals(
                runtime.contextBudgetCharsFor(binding.state.value.modelState, runtime.resourceSnapshot()),
                binding.state.value.kernel.contextBudgetChars,
            )
        } finally {
            nestedLease?.close()
            agent.close()
        }
        assertEquals(0, binding.state.value.kernel.resources.activeModelRequests)
        assertEquals(0, binding.state.value.kernel.resources.activeAgents)
    }

    @Test fun failedCancellationLogCannotPreventRealWorkStopOrTeardown() = runTest {
        for (join in listOf(false, true)) {
            val blocked = File(temporary.newFolder(), "blocked").apply { writeText("not a directory") }
            val log = LocalSessionEventLog(File(blocked, "events.jsonl"), Json)
            val binding = LocalWorkRunBinding(
                sessionId = "work",
                initialState = LocalHarnessState(
                    sessionId = "work",
                    usageMode = LocalUsageMode.WORK,
                    kernel = LocalKernelState(running = true, queuedInputCount = 1),
                    work = LocalWorkState(deviceApprovalLease = true),
                ).toLocalWorkRunState(),
                sessionBase = LocalHarnessSession(id = "work", usageMode = LocalUsageMode.WORK),
                runHandle = LocalAgentRunHandle(
                    initialSessionId = "work",
                    maxPendingInputs = 4,
                ),
                eventLog = log,
            )
            val job = kotlinx.coroutines.Job()
            val mirror = kotlinx.coroutines.Job()
            binding.runHandle.job = job
            binding.runHandle.projectionJob = mirror
            binding.runHandle.pendingInputs.offer(
                com.labteto.dshmobile.harness.agent.QueuedAgentInput("queued", id = "queued"),
            )
            try {
                assertTrue(runCatching {
                    if (join) binding.cancelAndJoin() else binding.requestCancel()
                }.isFailure)
                assertTrue(job.isCancelled)
                assertEquals(1, binding.state.value.kernel.queuedInputCount)
                assertEquals(1, binding.runHandle.pendingInputs.size())
                assertFalse(binding.state.value.work.deviceApprovalLease)
                if (join) {
                    assertTrue(mirror.isCancelled)
                    assertNull(binding.runHandle.job)
                    assertNull(binding.runHandle.projectionJob)
                    assertFalse(binding.state.value.kernel.running)
                } else {
                    assertFalse(mirror.isCancelled)
                    assertTrue(binding.state.value.kernel.running)
                }
            } finally {
                job.cancel()
                mirror.cancel()
                log.close()
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun teardownRetainsRunReferenceAndRunningUntilRealJobExits() = runTest {
        val binding = binding(LocalHarnessState(
            sessionId = "work", kernel = LocalKernelState(running = true),
        ))
        val cleanupGate = CompletableDeferred<Unit>()
        val job = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupGate.await() } }
        }
        binding.runHandle.job = job
        runCurrent()
        val teardown = async { binding.cancelAndJoin() }
        try {
            runCurrent()
            assertFalse(teardown.isCompleted)
            assertTrue(binding.state.value.kernel.running)
            org.junit.Assert.assertSame(job, binding.runHandle.job)
            cleanupGate.complete(Unit)
            teardown.await()
            assertFalse(binding.state.value.kernel.running)
            assertNull(binding.runHandle.job)
        } finally {
            cleanupGate.complete(Unit)
            teardown.cancel()
            binding.eventLog.close()
        }
    }

    private fun binding(initial: LocalHarnessState): LocalWorkRunBinding {
        val workState = initial.copy(usageMode = LocalUsageMode.WORK)
        return LocalWorkRunBinding(
            sessionId = workState.sessionId,
            initialState = workState.toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(
                id = workState.sessionId,
                usageMode = LocalUsageMode.WORK,
            ),
            runHandle = LocalAgentRunHandle(
                initialSessionId = workState.sessionId,
                maxPendingInputs = 4,
            ),
            eventLog = LocalSessionEventLog(File(temporary.newFolder(), "events.jsonl"), Json),
        )
    }
}
