package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectorInventoryBrowserTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualConnectorsShareIconCardsAndKeepWorkingActions() {
        var githubOpens = 0
        var mcpOpens = 0
        compose.setContent {
            DshTheme {
                ConnectorInventoryBrowser(
                    servers = listOf(UiMcpServerSnapshot(
                        id = "local-mcp", transport = "http",
                        target = "http://127.0.0.1:8765", tools = listOf("search"),
                    )),
                    githubConfigured = true,
                    onGitHub = { githubOpens++ },
                    onMcp = { mcpOpens++ },
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithTag("connector-inventory-icon-github").assertExists()
        compose.onNodeWithTag("connector-inventory-icon-local-mcp").assertExists()
        compose.onNodeWithText("MCP 本地服务").assertIsDisplayed()
        compose.onNodeWithTag("connector-inventory-action-github").performClick()
        compose.onNodeWithTag("connector-inventory-action-local-mcp").performClick()
        compose.runOnIdle {
            assertEquals(1, githubOpens)
            assertEquals(1, mcpOpens)
        }
    }

    @Test fun searchUsesStableIdsWhileDisplayingChineseNames() {
        compose.setContent {
            DshTheme {
                ConnectorInventoryBrowser(
                    servers = listOf(UiMcpServerSnapshot(
                        id = "local-mcp", transport = "stdio", target = "local",
                        tools = emptyList(),
                    )),
                    githubConfigured = false,
                    onGitHub = {}, onMcp = {}, onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithTag("connector-inventory-search").performTextInput("local-mcp")
        compose.onNodeWithTag("connector-inventory-card-local-mcp").assertIsDisplayed()
        compose.onNodeWithTag("connector-inventory-card-github").assertDoesNotExist()
    }

    @Test fun additionalConnectionsAndSettingsHaveFunctionalEntries() {
        var mcpOpens = 0
        var settingsOpens = 0
        compose.setContent {
            DshTheme {
                ConnectorInventoryBrowser(
                    servers = emptyList(),
                    githubConfigured = false,
                    onGitHub = {}, onMcp = { mcpOpens++ },
                    onOpenSettings = { settingsOpens++ },
                )
            }
        }
        compose.onNodeWithTag("connector-inventory-action-add-mcp").performClick()
        compose.onNodeWithTag("connector-settings-entry").performClick()
        compose.runOnIdle {
            assertEquals(1, mcpOpens)
            assertEquals(1, settingsOpens)
        }
    }
}
