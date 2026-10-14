package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The bottom action row is not part of the reading card, even with long dialogue. */
class ChatBubbleLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun assistantActionsSitBelowTheReadingBubble() {
        val message = LocalHarnessMessage(
            id = "bubble-layout", role = "assistant",
            content = "（她抬起头，轻声问。）\n今天想去哪儿？\n\n慢慢走也好。",
            createdAt = 1L,
        )
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = true, groupMode = false,
                    canEdit = false, canRegenerate = true, canSelectVariant = false,
                    branchInfo = null, onEdit = {},
                    onSelectVariant = { _, _ -> false }, onRegenerate = { true },
                )
            }
        }
        val bubble = compose.onNodeWithTag("messageActionSurface:bubble-layout").getUnclippedBoundsInRoot()
        val toolbar = compose.onNodeWithTag("messageActionToolbar:bubble-layout").getUnclippedBoundsInRoot()
        assertTrue("The toolbar should start beneath the chat bubble", toolbar.top >= bubble.bottom)
        assertTrue("The bubble must contain readable text", (bubble.bottom - bubble.top).value > 0f)
    }
}
