package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalHarnessUiStateTest {
    @Test
    fun streamingPreviewIsIndependentFromAggregateState() {
        val aggregate = LocalHarnessState()
        val preview = LocalHarnessStreamingState(assistant = "partial reply")

        assertEquals("", LocalHarnessStreamingState().assistant)
        assertEquals("partial reply", preview.assistant)
        assertEquals(aggregate, aggregate.copy())
    }

    @Test
    fun settingsProjectionIgnoresUnrelatedHotRuntimeChanges() {
        val base = LocalHarnessState()
        val hotPathUpdate = base.copy(
            running = true,
            activeModelRequests = 1,
            activeAgents = 1,
            contextChars = 42_000,
        )

        assertEquals(base.toSettingsUiState(), hotPathUpdate.toSettingsUiState())
    }

    @Test
    fun taskProjectionIgnoresUnrelatedHotRuntimeChanges() {
        val base = LocalHarnessState()
        val hotPathUpdate = base.copy(
            running = true,
            activeModelRequests = 1,
            activeTerminals = 1,
            resourcePressure = "medium",
        )

        assertEquals(base.toTaskUiState(), hotPathUpdate.toTaskUiState())
    }

    @Test
    fun shellProjectionIgnoresStreamingAndResourceChurn() {
        val base = LocalHarnessState()
        val hotPathUpdate = base.copy(
            activeModelRequests = 1,
            activeAgents = 1,
            activeTerminals = 1,
            contextChars = 42_000,
            queuedInputCount = 3,
            resourcePressure = "high",
        )

        assertEquals(base.toShellUiState(), hotPathUpdate.toShellUiState())
    }

    @Test
    fun projectionsStillChangeForOwnedFields() {
        val base = LocalHarnessState()

        assertNotEquals(
            base.toSettingsUiState(),
            base.copy(model = "another-model").toSettingsUiState(),
        )
        assertNotEquals(
            base.toTaskUiState(),
            base.copy(usageMode = LocalUsageMode.CHAT).toTaskUiState(),
        )
        assertNotEquals(
            base.toTaskUiState(),
            base.copy(sessionId = "chat-session").toTaskUiState(),
        )
        assertNotEquals(
            base.toShellUiState(),
            base.copy(sessionId = "next-session").toShellUiState(),
        )
    }
}
