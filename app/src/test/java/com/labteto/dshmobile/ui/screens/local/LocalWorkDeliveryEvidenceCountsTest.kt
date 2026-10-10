package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.work.LocalTodoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorkDeliveryEvidenceCountsTest {
    @Test
    fun toolCompletionDoesNotHideUnfinishedTasksOrUnavailableFiles() {
        val files = listOf(
            LocalArtifactUiItem("reports/final.md", "file", "write-1", 10, true),
            LocalArtifactUiItem("reports/deleted.md", "file", "write-2", 11, false),
            LocalArtifactUiItem("reports/unread.md", "file", "write-3", 12, null),
            LocalArtifactUiItem("https://example.org/result", "link", "web-1", 13),
        )
        val calls = listOf(
            LocalToolActivityUiItem("write-1", "write_file", LocalToolUiPhase.COMPLETED),
            LocalToolActivityUiItem("write-2", "write_file", LocalToolUiPhase.FAILED),
            LocalToolActivityUiItem("write-3", "write_file", LocalToolUiPhase.OUTCOME_UNKNOWN),
        )
        val state = LocalWorkUiState(
            todos = listOf(LocalTodoItem("写报告", "completed"),
                LocalTodoItem("跑验收", "pending"), LocalTodoItem("复查引用", "in_progress")),
        )
        assertEquals(
            LocalWorkDeliveryEvidenceCounts(
                availableFiles = 1, missingFiles = 1, uncheckedFiles = 1,
                completedTools = 1, failedTools = 1, unknownTools = 1, openTasks = 2,
            ),
            localWorkDeliveryEvidenceCounts(state, files, calls),
        )
    }

    @Test
    fun emptyEventProjectionDoesNotInventPassedChecks() {
        assertEquals(
            LocalWorkDeliveryEvidenceCounts(0, 0, 0, 0, 0, 0, 0),
            localWorkDeliveryEvidenceCounts(LocalWorkUiState(), emptyList(), emptyList()),
        )
    }

    @Test
    fun waitingAndCancelledToolsAreNotCountedAsSuccesses() {
        val result = localWorkDeliveryEvidenceCounts(
            LocalWorkUiState(), emptyList(),
            listOf(
                LocalToolActivityUiItem("queued", "tool", LocalToolUiPhase.DECLARED),
                LocalToolActivityUiItem("running", "tool", LocalToolUiPhase.RUNNING),
                LocalToolActivityUiItem("cancel", "tool", LocalToolUiPhase.CANCELLED),
            ),
        )
        assertEquals(0, result.completedTools)
        assertEquals(1, result.unknownTools)
    }
}
