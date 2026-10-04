package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.presentation.toWorkSurfaceUiState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.work.LocalWorkState
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun visibleWorkReflectsDeviceLeaseActivationAndRevocation() {
        val visible = MutableStateFlow(LocalHarnessState(
            sessionId = "work",
            kernel = LocalKernelState(resources = LocalHarnessResourceState(activeAgents = 2)),
        ))
        val binding = binding(visible.value.copy(deviceApprovalLease = true))

        mirrorLocalWorkRunState("work", visible, binding)

        assertTrue(visible.value.toWorkSurfaceUiState().deviceApprovalLease)
        assertEquals(2, visible.value.kernel.resources.activeAgents)
        binding.state.value = binding.state.value.copy(deviceApprovalLease = false)
        mirrorLocalWorkRunState("work", visible, binding)
        assertFalse(visible.value.toWorkSurfaceUiState().deviceApprovalLease)
    }

    @Test fun backgroundWorkCannotPublishDeviceLeaseIntoAnotherSession() {
        val other = LocalHarnessState(sessionId = "other", usageMode = LocalUsageMode.CHAT)
        val visible = MutableStateFlow(other)
        val binding = binding(LocalHarnessState(sessionId = "work", deviceApprovalLease = true))

        mirrorLocalWorkRunState("other", visible, binding)
        assertEquals(other, visible.value)
        // The visible session can change after the caller reads its current session ID.
        mirrorLocalWorkRunState("work", visible, binding)
        assertEquals(other, visible.value)
    }

    @Test fun teardownClearsRunInteractionAndDeviceLeaseTogether() = runTest {
        val binding = binding(LocalHarnessState(
            sessionId = "work",
            deviceApprovalLease = true,
            kernel = LocalKernelState(running = true, queuedInputCount = 1),
            work = LocalWorkState(pendingQuestion = LocalQuestion("question", "继续吗？")),
        ))

        binding.cancelAndJoin()

        assertFalse(binding.state.value.deviceApprovalLease)
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
        mirrorLocalWorkRunState("chat", visible, binding)
        assertEquals("chat", visible.value.sessionId)
        assertFalse(visible.value.kernel.running)
        } finally {
            leases.forEach { it.close() }
        }
        assertEquals(0, binding.state.value.kernel.resources.activeAgents)
        assertEquals(initialBudget, binding.state.value.kernel.contextBudgetChars)
    }

    private fun binding(initial: LocalHarnessState) = LocalWorkRunBinding(
        sessionId = initial.sessionId,
        initialState = initial,
        initialHistory = emptyList(),
        eventLog = LocalSessionEventLog(File(temporary.newFolder(), "events.jsonl"), Json),
        initialTranscriptProjectionCursor = null,
        maxPendingInputs = 4,
        pruneToolResult = { it },
    )
}
