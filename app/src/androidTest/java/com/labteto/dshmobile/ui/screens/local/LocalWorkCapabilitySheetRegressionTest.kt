package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalWorkCapabilitySheetRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun enteringWorkRequiresAnExplicitUserAction() {
        var entered = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(false, false, true, { entered++ }, {})
            }
        }
        compose.runOnIdle { assertEquals(0, entered) }
        compose.onNodeWithText("进入工作模式").performClick()
        compose.runOnIdle { assertEquals(1, entered) }
    }

    @Test
    fun pendingModeTransitionCannotBeSubmittedAgainOrDismissed() {
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(true, false, true, {}, {})
            }
        }
        compose.onNodeWithText("进入工作模式").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }
}
