package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasSetTextAction
import com.labteto.dshmobile.uicontract.UiMessagePage
import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSearchPage
import com.labteto.dshmobile.uicontract.UiMessageSearchReceipt
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real user entry points: opening and clicking must reach the intended action. */
class LocalSessionEntryRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renamePinAndDeleteConfirmationRemainUsable() {
        var renamed = 0
        var pinned = 0
        var deleted = 0
        compose.setContent {
            DshTheme {
                ConversationActionsMenu(
                    pinned = false,
                    onTogglePin = { pinned++ },
                    onRename = { renamed++ },
                    onDelete = { deleted++ },
                )
            }
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        fun choose(label: Int) {
            compose.onNodeWithContentDescription(ctx.getString(R.string.local_session_actions)).performClick()
            compose.onNodeWithTag("menu-item:" + ctx.getString(label)).performClick()
        }
        choose(R.string.common_rename)
        compose.runOnIdle { assertEquals(1, renamed) }

        choose(R.string.advanced_pin)
        compose.runOnIdle { assertEquals(1, pinned) }

        choose(R.string.local_delete_session)
        compose.onNodeWithText(ctx.getString(R.string.local_delete_session_confirm_message)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, deleted) }

        choose(R.string.local_delete_session)
        compose.onAllNodesWithText(ctx.getString(R.string.local_delete_session)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun sessionSampleMenuOpensTheRealFeatureAction() {
        var opened = 0
        compose.setContent {
            DshTheme {
                ConversationActionsMenu(
                    pinned = false,
                    onTogglePin = {},
                    onRename = {},
                    onDelete = {},
                    onOpenSample = { opened++ },
                )
            }
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(ctx.getString(R.string.local_session_actions)).performClick()
        compose.onNodeWithTag("menu-item:" + ctx.getString(R.string.local_session_sample_title)).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun overflowMenuUsesCompactShortcutGridAndKeepsActionsReachable() {
        var searched = 0
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            DshTheme {
                ConversationActionsMenu(
                    pinned = false,
                    onTogglePin = {},
                    onRename = {},
                    onDelete = {},
                    onOpenWorkEvidence = {},
                    onOpenDocuments = {},
                    onOpenSample = {},
                    onSearchMessages = { searched++ },
                )
            }
        }
        compose.onNodeWithContentDescription(ctx.getString(R.string.local_session_actions)).performClick()
        val popup = compose.onNodeWithTag("conversation-action-popup").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithTag(
            "menu-item:" + ctx.getString(R.string.sy_v6_search_messages),
        ).getUnclippedBoundsInRoot()
        val workEvidence = compose.onNodeWithTag(
            "menu-item:" + ctx.getString(R.string.work_evidence_title),
        ).getUnclippedBoundsInRoot()
        val documents = compose.onNodeWithTag(
            "menu-item:" + ctx.getString(R.string.local_session_documents),
        ).getUnclippedBoundsInRoot()
        assertEquals(search.top, workEvidence.top)
        org.junit.Assert.assertTrue(documents.top > search.top)
        org.junit.Assert.assertTrue(popup.right - popup.left <= 226.dp)
        compose.onNodeWithTag(
            "menu-item:" + ctx.getString(R.string.sy_v6_search_messages),
        ).performClick()
        compose.runOnIdle { assertEquals(1, searched) }
    }

    @Test fun modelConfigurationEntryClosesPickerThenOpensSettings() {
        var closed = 0
        var configured = 0
        compose.setContent {
            DshTheme {
                LocalModelPickerSheet(
                    profiles = emptyList(), activeProfileId = null,
                    onSelect = {}, onConfigure = { configured++ }, onDismiss = { closed++ },
                )
            }
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(ctx.getString(R.string.local_model_picker_configure)).performClick()
        compose.runOnIdle {
            assertEquals(1, configured)
            assertEquals(1, closed)
        }
    }

    @Test fun loadedConversationSearchEntryReachesFullSearch() {
        var searchedAll = 0
        compose.setContent {
            DshTheme {
                V6LoadedConversationSearchSheet(
                    transcript = emptyList(),
                    onSelect = {}, onDismiss = {},
                    onSearchAll = { searchedAll++ },
                )
            }
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(ctx.getString(R.string.sy_v6_search_all)).performClick()
        compose.runOnIdle { assertEquals(1, searchedAll) }
    }

    @Test fun conversationSearchFindsArchivedMessageOutsideLoadedRows() {
        var openedId: String? = null
        compose.setContent {
            DshTheme {
                V6LoadedConversationSearchSheet(
                    transcript = emptyList(),
                    onSelect = { openedId = it.messageId },
                    onDismiss = {},
                    search = { query, _ ->
                        val message = UiMessageSnapshot("archive-id", UiMessageRole.USER, "存档中的早期线索", 1L)
                        UiMessageSearchReceipt.Available(UiMessageSearchPage(
                            UiMessagePage(listOf(message), nextCursor = null),
                            hitIds = if (message.text.contains(query)) setOf(message.id) else emptySet(),
                            scannedMessages = 1L,
                            nextCursor = null,
                        ))
                    },
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("早期线索")
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithText("存档中的早期线索").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("存档中的早期线索").performClick()
        compose.runOnIdle { assertEquals("archive-id", openedId) }
    }

}
