package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginInventoryBrowserRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installedPluginDetailsOpenAndCanReturnToConversation() {
        var returned = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("local-builtin"),
                    remote = null,
                    onBack = {},
                    onManageConnections = {},
                    onReturnToChat = { returned++ },
                )
            }
        }
        compose.onNodeWithText("技能").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回对话").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, returned) }
    }
}
