package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
    @Test
    fun useHandsTheSelectedCapabilityToTheComposer() {
        var prompt: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime"), remote = null,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onUseCapability = { prompt = it },
                )
            }
        }
        compose.onNodeWithText("使用").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(requireNotNull(prompt).contains("android-runtime"))
        }
    }

    @Test
    fun categoryFiltersTheRealInventory() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime", "local-vision"), remote = null,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-category-list").performScrollToIndex(4)
        compose.onNodeWithText("创意与图像").performClick()
        compose.onNodeWithText("视觉理解").assertIsDisplayed()
        compose.onNodeWithText("终端与运行环境").assertDoesNotExist()
    }

    @Test
    fun skillBrowserShowsInstalledSkillMetadataInsteadOfTheBuiltinPlugin() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("local-builtin"), remote = null,
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry("report", "Build a report", true)),
                    skillsOnly = true, onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithText("report").assertIsDisplayed()
        compose.onNodeWithText("Build a report").assertIsDisplayed()
        compose.onNodeWithText("已安装").assertDoesNotExist()
    }

}
