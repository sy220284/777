package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.*
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalSessionUiPortTest {
    private class Owner : LocalSessionLifecyclePort {
        var creates = 0
        var deleted = emptySet<String>()
        override fun createSession(mode: LocalConversationMode, usageMode: LocalUsageMode, domainSpec: LocalSessionDomainCreateSpec?, handoffSummaryOverride: String?): Boolean { creates++; return true }
        override fun currentHandoffSummary() = "owner summary"
        override fun switchDomainMode(command: LocalSessionDomainModeCommand) = Unit
        override fun switchUsageMode(mode: LocalUsageMode) = Unit
        override fun switchSession(sessionId: String) = sessionId == "known"
        override suspend fun deleteSessionIds(ids: Set<String>): Set<String> { deleted = ids; return ids.intersect(setOf("known")) }
        override suspend fun deleteSessions(ids: Set<String>) = deleteSessionIds(ids).size
    }
    @Test fun staleCreateAndRetiredProjectCannotInvokeLifecycleOwner() {
        val state = LocalRuntimeStateStore()
        state.initialize(LocalHarnessState(sessionId = "s", loading = false))
        val owner = Owner()
        val port = LocalSessionUiPortComposition(state, owner)
        val identity = requireNotNull(port.snapshot().identity)
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY), port.submit(SessionUiCommand.Create(
            identity.copy(activationId = "old"), LocalConversationMode.CONTINUATION, LocalUsageMode.WORK)))
        assertEquals(UiCommandReceipt.Rejected(UiCommandRejection.UNSUPPORTED), port.submit(SessionUiCommand.Create(
            identity, LocalConversationMode.PROJECT, LocalUsageMode.WORK)))
        assertEquals(0, owner.creates)
        assertNull(port.handoffSummary(identity.copy(activationId = "old")))
        assertEquals("owner summary", port.handoffSummary(identity))
    }
    @Test fun deletingOneSessionFailsWithoutClearingItsEventLogOrSwitchingVisibleSession() {
        val cleared = mutableListOf<String>()
        val durable = mutableListOf<String>()
        val diskError = IllegalStateException("disk is read-only")
        val error = assertThrows(IllegalStateException::class.java) {
            commitOrderedSessionDeletions(
                ids = linkedSetOf("current", "background"),
                currentSessionId = "current",
                deleteDurably = { id ->
                    if (id == "current") throw diskError
                    durable += id
                },
                cleanupCommitted = { cleared += it },
            )
        }
        assertSame(diskError, error)
        assertEquals(listOf("background"), durable)
        assertEquals(listOf("background"), cleared)
        assertFalse("failed current deletion must retain its event log", "current" in cleared)
    }

    @Test fun partialBatchDeletionAlwaysKeepsCurrentSessionForLast() {
        val targets = linkedSetOf("current", "background-1", "background-2")
        assertEquals(
            listOf("background-1", "background-2", "current"),
            localSessionDeletionOrder(targets, "current"),
        )
        assertEquals(
            listOf("background-1", "background-2"),
            localSessionDeletionOrder(setOf("background-1", "background-2"), "current"),
        )
        assertEquals(listOf("current"), localSessionDeletionOrder(setOf("current"), "current"))
    }

    @Test fun navigationReportsAdmissionAndDeletionReportsActualIds() = runTest {
        val state = LocalRuntimeStateStore()
        val owner = Owner()
        val port = LocalSessionUiPortComposition(state, owner)
        assertEquals(UiCommandReceipt.Queued, port.submit(SessionUiCommand.Open("known")))
        assertEquals(UiCommandReceipt.NotAdmitted, port.submit(SessionUiCommand.Open("missing")))
        assertEquals(UiSessionDeletionReceipt.Deleted(setOf("known")), port.delete(setOf("known", "missing")))
        assertEquals(setOf("known", "missing"), owner.deleted)
    }
}
