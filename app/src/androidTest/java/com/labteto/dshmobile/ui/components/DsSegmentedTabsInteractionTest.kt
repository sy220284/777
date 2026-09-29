package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DsSegmentedTabsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rapidAlternatingSelectionKeepsOneStableTab() {
        var selected by mutableIntStateOf(0)
        var callbacks = 0
        compose.setContent {
            DshTheme {
                Box(Modifier.width(320.dp)) {
                    DsSegmentedTabs(
                        labels = listOf("Chat", "Work"),
                        selectedIndex = selected,
                        onSelect = {
                            selected = it
                            callbacks += 1
                        },
                    )
                }
            }
        }

        val chat = compose.onNodeWithText("Chat")
        val work = compose.onNodeWithText("Work")
        chat.assertIsSelected()

        repeat(6) {
            work.performClick()
            work.assertIsSelected()
            chat.performClick()
            chat.assertIsSelected()
        }

        compose.runOnIdle {
            assertEquals(0, selected)
            assertEquals(12, callbacks)
        }
    }
}
