package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Open the production menu and evidence sheet with realistic event projections. */
class LocalWorkEvidenceSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun documentsListRemainsReachableFromSessionMenu() {
        var opened = 0
        compose.setContent {
            DshTheme {
                ConversationActionsMenu(
                    pinned = false, onTogglePin = {}, onRename = {}, onDelete = {},
                    onOpenDocuments = { opened++ },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.local_session_actions)).performClick()
        compose.onNodeWithTag("menu-item:" + context.getString(R.string.local_session_documents)).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun openingHistoryDoesNotStartHistoricalFilePreviews() {
        val previewReads = AtomicInteger(0)
        val artifact = LocalArtifactUiItem(
            reference = "reports/result.md", category = "file", sourceCallId = "write-1",
            asOfSequence = 101L, currentlyAvailable = true,
        )
        val actions = actions(
            records = listOf(LocalWorkHistoryRecord(
                sequence = 101L, type = "tool/result", name = "write",
                content = "完成", callId = "write-1", artifacts = listOf(artifact),
            )),
            previewReads = previewReads,
        )
        openFromMenu(actions)
        compose.onNodeWithTag("work_evidence_records").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("reports/result.md").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(0, previewReads.get()) }
    }

    @Test fun concurrentCallsWithSharedExecutionIdHaveDistinctRows() {
        val activities = listOf(
            LocalToolActivityUiItem("call-a", "read", LocalToolUiPhase.RUNNING, executionId = "run-1"),
            LocalToolActivityUiItem("call-b", "write", LocalToolUiPhase.RUNNING, executionId = "run-1"),
        )
        // The execution may be shared across calls, but the call ID must remain
        // the identity of the Compose lazy-list row.
        assertEquals("active:call-a", workEvidenceActivityKey(activities[0]))
        assertEquals("active:call-b", workEvidenceActivityKey(activities[1]))
        openFromMenu(actions(activities = activities))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("work_evidence_active_call-a").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("work_evidence_active_call-b").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun olderPageIsReachableAndUnknownOutcomeKeepsItsMeaning() {
        val older = LocalWorkHistoryCursor("archive-identity", 1L, 90L)
        val current = LocalWorkHistoryRecord(
            sequence = 90L, type = "tool/result", name = "read",
            content = "副作用结果未知", callId = "unknown-90", artifacts = emptyList(),
            phase = LocalToolUiPhase.OUTCOME_UNKNOWN,
        )
        val historical = LocalWorkHistoryRecord(
            sequence = 20L, type = "tool/result", name = "write",
            content = "更早的结果未知", callId = "unknown-20", artifacts = emptyList(),
            phase = LocalToolUiPhase.OUTCOME_UNKNOWN,
        )
        val pages = AtomicInteger(0)
        openFromMenu(actions(historyPages = { cursor ->
            pages.incrementAndGet()
            if (cursor == null) LocalWorkHistoryPageUi(listOf(current), older)
            else {
                assertEquals(older, cursor)
                LocalWorkHistoryPageUi(listOf(historical), null)
            }
        }))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("work_evidence_older").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("work_evidence_older").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("#20 · write").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(2, pages.get()) }
        val unknown = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.work_tool_unknown)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(unknown).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFromMenu(actions: LocalWorkFeatureUiActions) {
        val opened = mutableStateOf(false)
        compose.setContent {
            DshTheme {
                ConversationActionsMenu(
                    pinned = false, onTogglePin = {}, onRename = {}, onDelete = {},
                    onOpenWorkEvidence = { opened.value = true },
                )
                if (opened.value) {
                    LocalWorkEvidenceSheet(
                        sessionId = "work-1", workspacePath = "/tmp/work-1",
                        actions = actions, onOpenFile = {},
                        onDismiss = { opened.value = false },
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.local_session_actions)).performClick()
        compose.onNodeWithTag("menu-item:" + context.getString(R.string.work_evidence_title)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("work_evidence_records").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun actions(
        records: List<LocalWorkHistoryRecord> = emptyList(),
        activities: List<LocalToolActivityUiItem> = emptyList(),
        previewReads: AtomicInteger = AtomicInteger(),
        historyPages: (LocalWorkHistoryCursor?) -> LocalWorkHistoryPageUi = {
            LocalWorkHistoryPageUi(records, null)
        },
    ) = LocalWorkFeatureUiActions(
        workState = MutableStateFlow(LocalWorkUiState(sessionId = "work-1")),
        workspaceFiles = { emptyList() },
        conversationFiles = { error("not used by evidence sheet") },
        previewWorkspaceFile = { previewReads.incrementAndGet(); error("history opened file preview") },
        artifactsForUi = { emptyList() },
        eventSequenceForUi = { 0L },
        artifactRevision = { null },
        refreshArtifactFiles = { _, files -> files },
        inspectArtifact = { _, artifact -> artifact },
        historyPage = { _, cursor -> historyPages(cursor) },
        toolActivities = { activities },
        toolEvidence = { _, _, _ -> null },
        requirementEvidence = { emptyList() },
        linkRequirementEvidence = { _, _, _ -> true },
        eventChanges = { MutableStateFlow(0L) },
        artifactFileChanges = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
    )
}
