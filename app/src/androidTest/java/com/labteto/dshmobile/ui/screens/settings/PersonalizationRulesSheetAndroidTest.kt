package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the actual Settings rules sheet with late, failed and cancelled write receipts. */
class PersonalizationRulesSheetAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun diskFailureKeepsSheetAndDraftEditableUntilSuccessfulRetry() {
        val editor = PersonalizationRulesEditor()
        var pending: ((Boolean, String?) -> Unit)? = null
        var completed = 0
        editor.open("original")
        editor.edit("new draft")
        compose.setContent {
            val state by editor.state.collectAsState()
            DshTheme {
                PersonalizationRulesSheet(
                    rules = state,
                    onDismiss = editor::cancel,
                    onDraftChange = editor::edit,
                    onSave = {
                        editor.save(
                            submit = { _, receipt -> pending = receipt },
                            onSaved = { completed++ },
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag("personalizationRulesSave").performClick()
        compose.runOnIdle {
            assertEquals(0, completed)
            assertEquals("new draft", editor.state.value.text)
            assertEquals(true, editor.state.value.saving)
            pending!!(false, "disk unavailable")
        }
        compose.onNodeWithText("disk unavailable").assertExists()
        compose.onNodeWithTag("personalizationRulesSave").performClick()
        compose.runOnIdle { pending!!(true, null) }
        compose.onNodeWithTag("personalizationRulesSave").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, completed) }
    }

    @Test fun pageSwitchAndCancelCannotLetOldCompletionReopenOrCloseNewEditor() {
        val editor = PersonalizationRulesEditor()
        val onPage = mutableStateOf(true)
        var lateReceipt: ((Boolean, String?) -> Unit)? = null
        var reported = 0
        editor.open("saved")
        compose.setContent {
            val state by editor.state.collectAsState()
            DshTheme {
                if (onPage.value) {
                    PersonalizationRulesSheet(
                        rules = state,
                        onDismiss = editor::cancel,
                        onDraftChange = editor::edit,
                        onSave = {
                            editor.save(
                                submit = { _, receipt -> lateReceipt = receipt },
                                onSaved = { if (onPage.value) reported++ },
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("personalizationRulesSave").performClick()
        compose.runOnIdle {
            assertEquals(true, editor.state.value.saving)
            onPage.value = false
        }
        compose.onNodeWithTag("personalizationRulesSave").assertDoesNotExist()
        compose.runOnIdle {
            editor.cancel()
            editor.open("current disk value")
            lateReceipt!!(true, null)
            assertEquals("current disk value", editor.state.value.text)
            assertEquals(true, editor.state.value.open)
            onPage.value = true
        }
        compose.onNodeWithTag("personalizationRulesSave").assertExists()
        compose.runOnIdle { assertEquals(0, reported) }
    }

    @Test fun switchingAwayAndBackDuringPendingSavePreservesDraft() {
        val editor = PersonalizationRulesEditor()
        val onPage = mutableStateOf(true)
        var pending: ((Boolean, String?) -> Unit)? = null
        editor.open("old disk value")
        editor.edit("draft being saved")
        compose.setContent {
            val state by editor.state.collectAsState()
            DshTheme {
                if (onPage.value) PersonalizationRulesSheet(
                    rules = state,
                    onDismiss = editor::cancel,
                    onDraftChange = editor::edit,
                    onSave = {
                        editor.save(
                            submit = { _, receipt -> pending = receipt },
                            onSaved = {},
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag("personalizationRulesSave").performClick()
        compose.runOnIdle { onPage.value = false }
        compose.onNodeWithTag("personalizationRulesSave").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("draft being saved", editor.state.value.text)
            assertEquals(true, editor.state.value.saving)
            onPage.value = true
        }
        compose.onNodeWithTag("personalizationRulesSave").assertExists()
        compose.runOnIdle {
            assertEquals("draft being saved", editor.state.value.text)
            pending!!(false, "disk failed after page switch")
        }
        compose.onNodeWithText("disk failed after page switch").assertExists()
        compose.runOnIdle { assertEquals("draft being saved", editor.state.value.text) }
    }
}
