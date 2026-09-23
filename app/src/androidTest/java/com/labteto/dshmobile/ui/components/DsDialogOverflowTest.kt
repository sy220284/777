package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DsDialogOverflowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun longDialogContentCanScrollToFinalAction() {
        var confirmed = 0

        compose.setContent {
            DshTheme {
                DsDialog(title = "Overflow regression", onDismiss = {}) {
                    Text(
                        (1..120).joinToString("\n") { index ->
                            "Line $index — dialog content that must remain reachable on a phone."
                        },
                    )
                    DsButton(
                        text = "Finish",
                        onClick = { confirmed++ },
                    )
                }
            }
        }

        val scrollable = compose.onNode(hasScrollAction()).assertExists()
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, Float.MAX_VALUE)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Finish")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, confirmed)
    }
}
