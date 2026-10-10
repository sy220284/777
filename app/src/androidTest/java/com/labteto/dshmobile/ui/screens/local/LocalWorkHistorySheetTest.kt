package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.*
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LocalWorkHistorySheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun artifactClickPassesExactPathAndDismissesHistory() {
        var opened: String? = null
        var dismissed = 0
        compose.setContent {
            DshTheme {
                LocalWorkHistorySheet("session", { _, _ ->
                    LocalWorkHistoryPageUi(listOf(LocalWorkHistoryRecord(1, "tool/result", "write_file", "saved", "call",
                        listOf(LocalArtifactUiItem(reference = "results/report.md", category = "file", sourceCallId = "call", asOfSequence = 1, currentlyAvailable = true)))), null)
                }, { _, _, _ -> null }, { opened = it }, { dismissed++ })
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("results/report.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("results/report.md").performClick()
        compose.runOnIdle {
            assertEquals("results/report.md", opened)
            assertEquals(1, dismissed)
        }
    }

    @Test fun olderPageReplacesRecentRecordsAndShowsExactToolEvidence() {
        val reads = CopyOnWriteArrayList<Long?>()
        compose.setContent {
            DshTheme {
                LocalWorkHistorySheet("session", { _, cursor ->
                    reads.add(cursor?.beforeSequence)
                    if (cursor == null) LocalWorkHistoryPageUi(
                        listOf(LocalWorkHistoryRecord(200, "tool/result", "write_file", "recent preview", "recent", emptyList())),
                        LocalWorkHistoryCursor("log", 0, 200))
                    else LocalWorkHistoryPageUi(listOf(LocalWorkHistoryRecord(1, "tool/result", "write_file",
                        "early preview", "early", emptyList())), null)
                }, { session, id, sequence -> if (session == "session" && id == "early" && sequence == 1L) "full early result" else null }, {}, {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("recent preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("recent preview").assertExists()
        val older = context.getString(R.string.local_run_history_older)
        compose.onNodeWithText(older).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("early preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("early preview").assertExists()
        compose.onNodeWithText("recent preview").assertDoesNotExist()
        compose.onNodeWithText(older).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.local_tool_activity_details)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("full early result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("full early result").assertExists()
    }

    @Test fun sessionChangeResetsPaginationToLatestPage() {
        val session = mutableStateOf("one")
        val reads = CopyOnWriteArrayList<Pair<String, Long?>>()
        compose.setContent {
            DshTheme {
                LocalWorkHistorySheet(session.value, { id, cursor ->
                    reads.add(id to cursor?.beforeSequence)
                    LocalWorkHistoryPageUi(listOf(LocalWorkHistoryRecord(200, "tool/call", "read_file", id,
                        "call", emptyList())), LocalWorkHistoryCursor(id, 0, 200))
                }, { _, _, _ -> null }, {}, {})
            }
        }
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.local_run_history_older)).performClick()
        compose.waitUntil(5_000) { reads.contains("one" to 200L) }
        compose.runOnIdle { session.value = "two" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("two").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(reads.none { it.first == "two" && it.second != null })
        compose.onNodeWithText("two").assertExists()
        compose.onNodeWithText("one").assertDoesNotExist()
    }
}
