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

    @Test
    fun missingPresetCanBeInstalledFromSkillsPage() {
        var selected: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), remote = null,
                    presets = listOf(com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry(
                        "research-check", "资料研究与核实", "查证材料", false,
                    )),
                    skillsOnly = true, onBack = {},
                    onManageConnections = {}, onReturnToChat = {},
                    onInstallPreset = { selected = it },
                )
            }
        }
        compose.onNodeWithText("资料研究与核实").assertIsDisplayed()
        compose.onNodeWithText("添加").performClick()
        compose.runOnIdle { assertEquals("research-check", selected) }
    }

    @Test
    fun installedSkillRequiresConfirmationBeforeRemoval() {
        var removed: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), remote = null,
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "my-skill", "My instructions", true,
                    )),
                    skillsOnly = true, onBack = {},
                    onManageConnections = {}, onReturnToChat = {},
                    onRemoveSkill = { removed = it },
                )
            }
        }
        compose.onNodeWithText("my-skill").performClick()
        compose.onNodeWithText("移除").performClick()
        compose.runOnIdle { assertEquals(null, removed) }
        compose.onNodeWithText("移除技能").assertIsDisplayed()
        compose.onNodeWithText("移除").performClick()
        compose.runOnIdle { assertEquals("my-skill", removed) }
    }

    @Test
    fun userOnlySkillCanStillBeInvokedExplicitly() {
        var prompt: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), remote = null, skillsOnly = true,
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "manual-review", "Only on user request", false,
                    )),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onUseCapability = { prompt = it },
                )
            }
        }
        compose.onNodeWithText("使用").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("@skill:manual-review\n", prompt)
        }
    }

}
