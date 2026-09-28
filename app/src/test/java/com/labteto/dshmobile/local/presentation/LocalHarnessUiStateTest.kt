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
    fun workProjectionIgnoresChatChurn() {
        val base = LocalHarnessState()
        val unrelatedUpdate = base.copy(
            chatPersona = base.chatPersona.copy(name = "另一人物"),
            galleryId = "gallery-2",
            model = "another-model",
        )

        assertEquals(base.toWorkUiState(), unrelatedUpdate.toWorkUiState())
    }

    @Test
    fun workProjectionChangesForOwnedRunState() {
        val base = LocalHarnessState()

        assertNotEquals(
            base.toWorkUiState(),
            base.copy(running = true, contextChars = 12_000, queuedInputCount = 2).toWorkUiState(),
        )
    }

    @Test
    fun chatSurfaceProjectionIgnoresWorkOnlyChurn() {
        val base = LocalHarnessState(usageMode = LocalUsageMode.CHAT)
        val workOnlyUpdate = base.copy(model = "another-model", planMode = true, safeAutoApprovalEnabled = true, contextChars = 48_000, queuedInputCount = 4)
        assertEquals(base.toChatSurfaceUiState(), workOnlyUpdate.toChatSurfaceUiState())
    }

    @Test
    fun workSurfaceProjectionIgnoresChatOnlyChurn() {
        val base = LocalHarnessState(usageMode = LocalUsageMode.WORK)
        val chatOnlyUpdate = base.copy(chatPersona = base.chatPersona.copy(name = "另一人物"), galleryId = "gallery-2")
        assertEquals(base.toWorkSurfaceUiState(), chatOnlyUpdate.toWorkSurfaceUiState())
    }

    @Test
    fun conversationSurfaceProjectionsStillTrackSharedHotFields() {
        val base = LocalHarnessState()
        assertNotEquals(base.toChatSurfaceUiState(), base.copy(running = true).toChatSurfaceUiState())
        assertNotEquals(base.toWorkSurfaceUiState(), base.copy(running = true).toWorkSurfaceUiState())
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
