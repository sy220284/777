package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalWorkEvidenceControllerTest {
    private fun actions(page: (String, LocalWorkHistoryCursor?) -> LocalWorkHistoryPageUi,
        linksFail: Boolean = false) = LocalWorkFeatureUiActions(
        evidence = object : com.labteto.dshmobile.uicontract.WorkEvidenceUiPort {
            override suspend fun read(identity: com.labteto.dshmobile.uicontract.UiSessionIdentity) =
                com.labteto.dshmobile.uicontract.UiWorkEvidenceReadReceipt.Rejected(
                    com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY)
            override suspend fun submit(command: com.labteto.dshmobile.uicontract.LinkWorkEvidenceReferenceCommand) =
                com.labteto.dshmobile.uicontract.UiCommandReceipt.NotAdmitted
        },
        workState = MutableStateFlow(LocalWorkUiState(sessionId = "session")),
        workspaceFiles = { emptyList() }, conversationFiles = { error("not used") }, previewWorkspaceFile = { error("not used") },
        artifactsForUi = { emptyList() }, eventSequenceForUi = { 1L }, artifactRevision = { null },
        refreshArtifactFiles = { _, items -> items }, inspectArtifact = { _, item -> item },
        historyPage = page, toolActivities = { emptyList() }, toolEvidence = { _, _, _ -> null },
        requirementEvidence = { if (linksFail) throw IOException("link read failed") else emptyList() },
        linkRequirementEvidence = { _, _, _ -> false },
    )
    private fun record(n: Long) = LocalWorkHistoryRecord(n, "tool/result", "write", "result", "call-$n", emptyList())

    @Test fun emptyFilteredPageCanContinueAndResetInvalidatesPriorResults() = runTest {
        val first = LocalWorkHistoryCursor("log", 0L, 200)
        val second = first.copy(beforeSequence = 40)
        val seen = mutableListOf<LocalWorkHistoryCursor?>()
        val controller = LocalWorkEvidenceController(this, "session", actions({ session, cursor ->
            assertEquals("session", session)
            synchronized(seen) { seen += cursor }
            when (cursor) {
                null -> LocalWorkHistoryPageUi(listOf(record(201)), first)
                first -> LocalWorkHistoryPageUi(emptyList(), second)
                else -> LocalWorkHistoryPageUi(emptyList(), null, invalidated = true)
            }
        }))
        try {
            controller.refresh()
            controller.state.first { !it.loading && it.older == first }
            controller.older()
            val next = controller.state.first { !it.loading && it.older == second }
            assertEquals(listOf(201L), next.records.map { it.sequence })
            controller.older()
            val invalid = controller.state.first { it.invalidated }
            assertTrue(invalid.records.isEmpty())
            assertNull(invalid.older)
            assertEquals(listOf(null, first, second), synchronized(seen) { seen.toList() })
        } finally { controller.close() }
    }

    @Test fun pageFailurePreservesEvidenceAndRetryUsesSameCursor() = runTest {
        val cursor = LocalWorkHistoryCursor("log", 0L, 20)
        var attempts = 0
        val controller = LocalWorkEvidenceController(this, "session", actions({ _, before ->
            if (before == null) LocalWorkHistoryPageUi(listOf(record(30)), cursor)
            else {
                assertEquals(cursor, before)
                attempts++
                if (attempts == 1) throw IOException("temporary read failure")
                LocalWorkHistoryPageUi(listOf(record(10)), null)
            }
        }))
        try {
            controller.refresh()
            controller.state.first { !it.loading && it.older != null }
            controller.older()
            val failed = controller.state.first { it.failed }
            assertEquals(listOf(30L), failed.records.map { it.sequence })
            assertEquals(cursor, failed.older)
            controller.older()
            val recovered = controller.state.first { !it.loading && !it.failed && it.records.size == 2 }
            assertEquals(listOf(30L, 10L), recovered.records.map { it.sequence })
            assertEquals(2, attempts)
        } finally { controller.close() }
    }

    @Test fun unreadableLinksDoNotHideValidToolHistory() = runTest {
        val controller = LocalWorkEvidenceController(this, "session", actions({ _, _ ->
            LocalWorkHistoryPageUi(listOf(record(1)), null)
        }, linksFail = true))
        try {
            controller.refresh()
            val state = controller.state.first { it.linksFailed }
            assertEquals(1L, state.records.single().sequence)
            assertFalse(state.failed)
        } finally { controller.close() }
    }
}
