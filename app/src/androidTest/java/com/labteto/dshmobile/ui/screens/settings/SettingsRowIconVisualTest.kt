package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsRowIconVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsIconKeepsAlignmentSlotAndWholeRowRemainsClickable() {
        var opened = 0
        compose.setContent {
            DshTheme {
                AppSettingsRow(
                    icon = SyEntryIcons.Appearance,
                    title = "外观与阅读",
                    compact = true,
                    onClick = { opened += 1 },
                )
            }
        }
        compose.onNodeWithTag("settingsRowIcon", useUnmergedTree = true)
            .assertWidthIsEqualTo(32.dp)
            .assertHeightIsEqualTo(32.dp)
        compose.onNodeWithText("外观与阅读").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
