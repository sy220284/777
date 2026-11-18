package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot
import com.labteto.dshmobile.local.presentation.LocalSkillUiEntry
import com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class PluginInventoryBrowserRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultShowsInstalledPluginSkillAndConnectorTogetherWithoutCategoriesOrSearch() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime"),
                    skills = listOf(LocalSkillUiEntry("report", "Build a report", true)),
                    connectedServices = listOf(UiMcpServerSnapshot(
                        id = "local-mcp", transport = "http", target = "http://127.0.0.1:4312",
                        tools = listOf("search"),
                    )),
                    presets = listOf(LocalPresetSkillUiEntry("research-check", "资料研究与核实", "查证材料", false)),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-ownership-tabs").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-local:android-runtime").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-skill:report").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-service:local-mcp").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-search").assertDoesNotExist()
        compose.onNodeWithTag("plugin-category-list").assertDoesNotExist()
        compose.onNodeWithTag("capability-center-tabs").assertDoesNotExist()
    }

    @Test fun discoverIncludesAvailableSkillAndBothConnectorOptions() {
        var githubOpened = 0
        var mcpOpened = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    presets = listOf(LocalPresetSkillUiEntry("research-check", "资料研究与核实", "查证材料", false)),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onGitHub = { githubOpened++ }, onAddMcp = { mcpOpened++ },
                )
            }
        }
        compose.onNodeWithText("发现").performClick()
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-connector:github").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, githubOpened) }
        compose.onNodeWithTag("plugin-inventory-card-connector:add-mcp").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, mcpOpened) }
    }

    @Test fun configuredGitHubAppearsInAddedViewAndUsesRealAction() {
        var opened = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), githubConfigured = true,
                    onGitHub = { opened++ }, onBack = {},
                    onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-connector:github").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun discoveredPresetCanBeInstalled() {
        var selected: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    presets = listOf(LocalPresetSkillUiEntry("research-check", "资料研究与核实", "查证材料", false)),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onInstallPreset = { selected = it },
                )
            }
        }
        compose.onNodeWithText("发现").performClick()
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle { assertEquals("research-check", selected) }
    }

    @Test fun installedSkillAndConnectedServiceKeepTheirActions() {
        var prompt: String? = null
        var managed = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(LocalSkillUiEntry("manual-review", "Only on user request", false)),
                    connectedServices = listOf(UiMcpServerSnapshot(
                        id = "local-mcp", transport = "http", target = "http://127.0.0.1:4312",
                        tools = listOf("search"),
                    )),
                    onBack = {}, onManageConnections = { managed++ },
                    onReturnToChat = {}, onUseCapability = { prompt = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:manual-review").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle { assertNotNull(prompt) }
        compose.onNodeWithTag("plugin-inventory-card-service:local-mcp").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle { assertEquals(1, managed) }
    }
}
