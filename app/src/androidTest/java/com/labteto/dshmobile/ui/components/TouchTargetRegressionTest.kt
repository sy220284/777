package com.labteto.dshmobile.ui.components

import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TouchTargetRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val density: Float
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private fun assertOnlyClickTargetAtLeast48Dp() {
        val bounds = compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot
        val minPx = 48f * density
        assertTrue("click target width must be >= 48dp, was ${bounds.width / density}dp", bounds.width >= minPx - 0.5f)
        assertTrue("click target height must be >= 48dp, was ${bounds.height / density}dp", bounds.height >= minPx - 0.5f)
    }

    @Test
    fun thinkingDisclosureKeepsMinimumTouchTarget() {
        compose.setContent {
            DshTheme {
                ThinkingRow(
                    summary = "Thinking",
                    expanded = false,
                    onToggle = {},
                )
            }
        }

        assertOnlyClickTargetAtLeast48Dp()
    }

    @Test
    fun tappablePillKeepsCompactVisualWithMinimumTouchTarget() {
        compose.setContent {
            DshTheme {
                DsPill(
                    text = "Q",
                    onClick = {},
                )
            }
        }

        assertOnlyClickTargetAtLeast48Dp()
    }

    @Test
    fun markdownCopyActionKeepsMinimumTouchTarget() {
        compose.setContent {
            DshTheme {
                MarkdownText(
                    text = "```text\nhello\n```",
                )
            }
        }

        assertOnlyClickTargetAtLeast48Dp()
    }

    @Test
    fun conversationScrollShortcutKeepsMinimumTouchTarget() {
        compose.setContent {
            DshTheme {
                ConversationScrollShortcut(
                    target = ConversationScrollTarget.LATEST,
                    onClick = {},
                )
            }
        }

        assertOnlyClickTargetAtLeast48Dp()
    }
}
