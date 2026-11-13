package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The real first-run/Settings permission screen; no system permission is granted by tests. */
class PermissionSetupFlowAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstLaunchCanSkipWithoutOpeningSystemSettings() {
        var finished = 0
        compose.setContent {
            DshTheme { PermissionSetupFlow(onFinished = { finished++ }, onBack = {}) }
        }
        compose.onNodeWithTag("permissionSetupFlow").assertExists()
        compose.onNodeWithText("稍后再说").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, finished) }
    }

    @Test fun selectedPermissionHasExplicitSkipAndAnIncompleteSummary() {
        var finished = 0
        compose.setContent {
            DshTheme { PermissionSetupFlow(onFinished = { finished++ }, onBack = {}) }
        }
        compose.onNodeWithTag("permissionFeature_NOTIFICATION_READ").performScrollTo().performClick()
        compose.onNodeWithText("逐项开启").performScrollTo().performClick()
        compose.onNodeWithTag("permissionSetupStatus").assertExists()
        compose.onNodeWithText("跳过这一项").performScrollTo().performClick()
        compose.onNodeWithText("权限设置结果").assertExists()
        compose.onNodeWithText("完成").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, finished) }
    }
}
