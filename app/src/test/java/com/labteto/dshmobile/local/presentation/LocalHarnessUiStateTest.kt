package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.work.LocalWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            resources = base.resources.copy(
                activeModelRequests = 1,
                activeAgents = 1,
            ),
            contextChars = 42_000,
        )

        assertEquals(base.toSettingsUiState(), hotPathUpdate.toSettingsUiState())
    }

    @Test
    fun taskProjectionIgnoresUnrelatedHotRuntimeChanges() {
        val base = LocalHarnessState()
        val hotPathUpdate = base.copy(
            running = true,
            resources = base.resources.copy(
                activeModelRequests = 1,
                activeTerminals = 1,
                resourcePressure = "medium",
            ),
        )

        assertEquals(base.toTaskUiState(), hotPathUpdate.toTaskUiState())
    }

    @Test
    fun shellProjectionIgnoresStreamingAndResourceChurn() {
        val base = LocalHarnessState()
        val hotPathUpdate = base.copy(
            resources = base.resources.copy(
                activeModelRequests = 1,
                activeAgents = 1,
                activeTerminals = 1,
                resourcePressure = "high",
            ),
            contextChars = 42_000,
            queuedInputCount = 3,
        )

        assertEquals(base.toShellUiState(), hotPathUpdate.toShellUiState())
    }

    @Test
    fun workProjectionIgnoresChatChurn() {
        val base = LocalHarnessState()
        val unrelatedUpdate = base.copy(
            chat = base.chat.copy(
                chatPersona = base.chat.chatPersona.copy(name = "另一人物"),
                galleryId = "gallery-2",
            ),
            modelState = base.modelState.copy(model = "another-model"),
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
    fun workProjectionReadsWorkDomainState() {
        val aggregate = LocalHarnessState(
            work = LocalWorkState(
                plan = listOf("第一步"),
                todos = listOf(com.labteto.dshmobile.local.LocalTodoItem("继续", "in_progress")),
                goal = com.labteto.dshmobile.local.LocalGoal("完成迁移"),
                planMode = true,
            ),
        )

        val work = aggregate.toWorkUiState()
        val surface = aggregate.toWorkSurfaceUiState()

        assertEquals(listOf("第一步"), work.plan)
        assertEquals("完成迁移", work.goal?.description)
        assertEquals(1, work.todos.size)
        assertTrue(surface.planMode)
    }

    @Test
    fun chatSurfaceProjectionIgnoresWorkOnlyChurn() {
        val base = LocalHarnessState(usageMode = LocalUsageMode.CHAT)
        val workOnlyUpdate = base.copy(modelState = base.modelState.copy(model = "another-model"), work = LocalWorkState(planMode = true), safeAutoApprovalEnabled = true, contextChars = 48_000, queuedInputCount = 4)
        assertEquals(base.toChatSurfaceUiState(), workOnlyUpdate.toChatSurfaceUiState())
    }

    @Test
    fun workSurfaceProjectionIgnoresChatOnlyChurn() {
        val base = LocalHarnessState(usageMode = LocalUsageMode.WORK)
        val chatOnlyUpdate = base.copy(
            chat = base.chat.copy(
                chatPersona = base.chat.chatPersona.copy(name = "另一人物"),
                galleryId = "gallery-2",
            ),
        )
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
            base.copy(modelState = base.modelState.copy(model = "another-model")).toSettingsUiState(),
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
    @Test
    fun sameRouteAccountSwitchUpdatesSettingsWithoutWakingUnrelatedShellState() {
        val first = com.labteto.dshmobile.local.LocalModelProfile("account-a", "same-model", "https://example.com")
        val second = first.copy(id = "account-b")
        val before = LocalHarnessState(modelState = com.labteto.dshmobile.local.model.LocalModelState(model = first.model, baseUrl = first.baseUrl,
            modelSelection = com.labteto.dshmobile.local.model.LocalModelSelectionState(listOf(first, second), first.id)))
        val after = before.copy(modelState = before.modelState.copy(modelSelection = before.modelState.modelSelection.copy(activeProfileId = second.id)))
        assertNotEquals(before.toSettingsUiState(), after.toSettingsUiState())
        assertEquals(before.toShellUiState(), after.toShellUiState())
        assertEquals(second.id, after.toSettingsUiState().modelSelection.activeProfileId)
        assertEquals(before.modelProfiles, after.modelProfiles)
    }

}
