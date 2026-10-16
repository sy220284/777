package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalRequirementUiTarget
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalWorkEvidenceSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = MutableStateFlow(LocalWorkUiState(sessionId = "session",
        goal = LocalGoal("deliver", id = "goal"), todos = listOf(LocalTodoItem("交付报告", "pending"))))
    private fun actions(history: (String, LocalWorkHistoryCursor?) -> LocalWorkHistoryPageUi,
        onLink: (LocalRequirementUiTarget) -> Boolean = { false }) = LocalWorkFeatureUiActions(
        evidence = object : com.labteto.dshmobile.uicontract.WorkEvidenceUiPort {
            override fun changes(identity: com.labteto.dshmobile.uicontract.UiSessionIdentity):
                kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun read(identity: com.labteto.dshmobile.uicontract.UiSessionIdentity) =
                com.labteto.dshmobile.uicontract.UiWorkEvidenceReadReceipt.Rejected(
                    com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY)
            override suspend fun inspect(
                identity: com.labteto.dshmobile.uicontract.UiSessionIdentity,
                artifact: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem,
            ) = com.labteto.dshmobile.uicontract.UiWorkArtifactInspectionReceipt.Failed(null)
            override suspend fun submit(command: com.labteto.dshmobile.uicontract.LinkWorkEvidenceReferenceCommand) =
                com.labteto.dshmobile.uicontract.UiCommandReceipt.NotAdmitted
            override suspend fun review(command: com.labteto.dshmobile.uicontract.ReviewWorkEvidenceCommand) =
                com.labteto.dshmobile.uicontract.UiCommandReceipt.NotAdmitted
        },
        workState = work, workspaceFiles = { emptyList() }, conversationFiles = { error("unused") },
        previewWorkspaceFile = { error("no preview") }, artifactsForUi = { emptyList() }, eventSequenceForUi = { 1L },
        artifactRevision = { null }, refreshArtifactFiles = { _, items -> items },
        inspectArtifact = { _, item -> item.copy(versionNow = "a".repeat(64)) },
        historyPage = history, toolActivities = { emptyList() }, toolEvidence = { _, _, _ -> "exact evidence" },
        requirementEvidence = { emptyList() }, linkRequirementEvidence = { _, target, _ -> onLink(target) },
    )
    @Test fun olderPageIsReachableAndUnknownOutcomeKeepsItsMeaning() {
        val requests = AtomicInteger()
        val cursor = LocalWorkHistoryCursor("log", 0L, 20)
        val actions = actions({ _, before ->
            requests.incrementAndGet()
            if (before == null) LocalWorkHistoryPageUi(listOf(LocalWorkHistoryRecord(21, "tool/result", "shell", "unknown", "call", emptyList(), LocalToolUiPhase.OUTCOME_UNKNOWN)), cursor)
            else LocalWorkHistoryPageUi(listOf(LocalWorkHistoryRecord(1, "tool/result", "read_file", "old", "old-call", emptyList())), null)
        })
        compose.setContent { DshTheme { LocalWorkEvidenceSheet("session", "", actions, {}, {}) } }
        compose.waitUntil { requests.get() == 1 }
        compose.onNodeWithText(context.getString(R.string.work_tool_unknown)).assertExists()
        compose.onNodeWithTag("work_evidence_older").performClick()
        compose.waitUntil { requests.get() == 2 }
        compose.onNodeWithTag("work_evidence_records").performScrollToNode(hasText("#1 · read_file"))
        compose.onNodeWithText("#1 · read_file").assertExists()
    }
    @Test fun failedLinkRetainsSelectedVersionAndDoesNotClaimAcceptance() {
        val target = AtomicReference<LocalRequirementUiTarget?>()
        val item = LocalArtifactUiItem("report.md", "file", "write-call", 2, currentlyAvailable = true)
        val actions = actions({ _, _ -> LocalWorkHistoryPageUi(listOf(LocalWorkHistoryRecord(2, "tool/result", "write", "done", "write-call", listOf(item))), null) },
            onLink = { target.set(it); false })
        compose.setContent { DshTheme { LocalWorkEvidenceSheet("session", "", actions, {}, {}) } }
        compose.onNodeWithTag("work_evidence_records").performScrollToNode(hasText(context.getString(R.string.work_evidence_current_version)))
        compose.onNodeWithText(context.getString(R.string.work_evidence_current_version)).performClick()
        compose.waitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("work_evidence_link_0")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("work_evidence_link_0").performClick()
        compose.waitUntil { target.get() != null }
        compose.onNodeWithText(context.getString(R.string.work_evidence_link_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.work_evidence_linked)).assertDoesNotExist()
        assertEquals(LocalRequirementUiTarget(0, "交付报告", "goal"), target.get())
    }
}
