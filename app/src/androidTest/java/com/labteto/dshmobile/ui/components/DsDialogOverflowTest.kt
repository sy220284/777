package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val axis = scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, 100_000f)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            axis.maxValue() > 0f && axis.value() >= axis.maxValue() - 1f
        }

        val viewport = scrollable.fetchSemanticsNode().boundsInRoot
        val finish = compose.onNodeWithText("Finish").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Final dialog action must overlap the scroll viewport after scrolling to the end",
            finish.top < viewport.bottom && finish.bottom > viewport.top,
        )
        compose.onNodeWithText("Finish").performClick()

        assertEquals(1, confirmed)
    }
}
