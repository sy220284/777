package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V6's mode/session binding is independent of the legacy shell and never caches old identity. */
class LocalV6SessionBindingTest {
    private class FakeSessionOwner : SessionUiPort {
        val source = MutableStateFlow(
            UiSessionsSnapshot(null, LocalUsageMode.CHAT, false, emptyList(), null),
        )
        override val snapshots: Flow<UiSessionsSnapshot> = source
        override fun snapshot(): UiSessionsSnapshot = source.value
        override fun submit(command: SessionUiCommand): UiCommandReceipt =
            error("Session mutations are not part of this read-only assembly test")
        override fun handoffSummary(identity: UiSessionIdentity): String? = null
        override suspend fun delete(sessionIds: Set<String>): UiSessionDeletionReceipt =
            error("Deletes are not admitted through this read-only binding")
    }

    @Test fun workModeAndActiveSessionTrackTheOnlySessionOwner() = runBlocking {
        val owner = FakeSessionOwner()
        val binding = LocalV6SessionBinding(owner)
        assertFalse(binding.isWork())
        assertEquals("", binding.sessionId())
        assertFalse(binding.workModeUpdates.first())

        owner.source.value = UiSessionsSnapshot(
            UiSessionIdentity("work-a", "generation-a", 1, null, null),
            LocalUsageMode.WORK, false, emptyList(), null,
        )
        assertTrue(binding.isWork())
        assertEquals("work-a", binding.sessionId())
        assertTrue(binding.workModeUpdates.first())

        owner.source.value = UiSessionsSnapshot(
            UiSessionIdentity("chat-b", "generation-b", 2, null, null),
            LocalUsageMode.CHAT, false, emptyList(), null,
        )
        assertFalse(binding.isWork())
        assertEquals("chat-b", binding.sessionId())
        assertFalse(binding.workModeUpdates.first())
    }
}
