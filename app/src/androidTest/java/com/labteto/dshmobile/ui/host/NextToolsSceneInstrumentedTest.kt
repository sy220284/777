package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.uicontract.ToolsUiPort
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot
import com.labteto.dshmobile.uicontract.UiSkillSnapshot
import com.labteto.dshmobile.uicontract.UiPresetSkillSnapshot
import com.labteto.dshmobile.uicontract.UiSkillEditorSnapshot
import com.labteto.dshmobile.uicontract.UiToolCapabilitySnapshot
import com.labteto.dshmobile.uicontract.UiToolConnection
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uipack.next.NextToolsScene
import com.labteto.dshmobile.uipack.next.NextToolsPage
import com.labteto.dshmobile.uipack.next.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Independent Next Tools UI -> real typed ToolsUiPort boundary. No network or provider calls. */
class NextToolsSceneInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Owner : ToolsUiPort {
        val serversState = mutableListOf(UiMcpServerSnapshot(
            "existing", "http", "https://existing.example/mcp", listOf("read_resource"),
            connection = UiToolConnection.HEALTH_UNCHECKED,
        ))
        val connects = mutableListOf<Pair<String,String>>()
        val disconnects = mutableListOf<String>()
        override suspend fun servers(): List<UiMcpServerSnapshot> = serversState.toList()
        override suspend fun githubConfigured(): Boolean = false
        override fun capabilityCatalogue(): List<UiToolCapabilitySnapshot> = emptyList()
        override fun installedPluginIds(): List<String> = listOf("local-builtin", "file-connector")
        override suspend fun installedSkills(): List<UiSkillSnapshot> =
            listOf(UiSkillSnapshot("my-skill", "sample purpose", true, "演示技能"))
        override suspend fun presetSkills(): List<UiPresetSkillSnapshot> = emptyList()
        override suspend fun curatedGitHubSkills(): List<String> = emptyList()
        override suspend fun installGitHubSkill(link: String): String =
            error("not used in this test")
        override suspend fun readSkillDocument(id: String): String = ""
        override suspend fun installPreset(id: String) = Unit
        override suspend fun createSkill(id: String, displayName: String, description: String, instructions: String) = Unit
        override suspend fun importSkill(filename: String, bytes: ByteArray): String = filename
        override suspend fun removeSkill(id: String) = Unit
        override suspend fun updateSkillDocument(id: String, document: String) = Unit
        override suspend fun setSkillModelInvocable(id: String, enabled: Boolean) = Unit
        override suspend fun connectHttp(serverId: String, endpoint: String): String {
            connects += serverId to endpoint
            serversState += UiMcpServerSnapshot(serverId, "http", endpoint, emptyList())
            return serverId
        }
        override suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?): String =
            error("not used in this test")
        override suspend fun disconnect(serverId: String): String {
            disconnects += serverId
            serversState.removeAll { it.id == serverId }
            return serverId
        }
        override suspend fun configureGitHub(token: String) = Unit
        override suspend fun clearGitHub() = Unit
    }
    private fun render(owner: Owner, page: NextToolsPage) {
        compose.setContent {
            MaterialTheme {
                NextToolsScene(
                    owner = owner, page = page, onBack = {},
                    onOpenMcp = { UiSceneNavigationResult.AtRoot },
                    onOpenPlugins = { UiSceneNavigationResult.AtRoot },
                )
            }
        }
    }

    @Test fun registeredServerIsNotFalselyCalledOnlineAndConnectUsesExactOwnerInput() {
        val owner = Owner()
        render(owner, NextToolsPage.MCP)
        compose.onNodeWithText("existing").assertExists()
        compose.onNodeWithText(context.getString(R.string.next_tools_health_unchecked)).assertExists()
        compose.onNodeWithText(context.getString(R.string.next_tools_server_id)).performTextInput("new-server")
        compose.onNodeWithText(context.getString(R.string.next_tools_server_endpoint)).performTextInput("https://server.example/mcp")
        compose.onNodeWithText(context.getString(R.string.next_tools_connect)).performClick()
        compose.onNodeWithText(context.getString(R.string.next_tools_connection_applied)).assertExists()
        compose.runOnIdle { assertEquals(listOf("new-server" to "https://server.example/mcp"), owner.connects) }
    }

    @Test fun disconnectNeedsExplicitConfirmationAndCallsOwnerOnce() {
        val owner = Owner()
        render(owner, NextToolsPage.MCP)
        compose.onNodeWithText("existing").assertExists()
        compose.onNodeWithText(context.getString(R.string.next_tools_disconnect)).performClick()
        compose.onNodeWithText(context.getString(R.string.next_tools_disconnect_confirm)).assertExists()
        compose.runOnIdle { assertEquals(0, owner.disconnects.size) }
        compose.onAllNodesWithText(context.getString(R.string.next_tools_disconnect)).onLast().performClick()
        compose.onNodeWithText(context.getString(R.string.next_tools_mcp_none)).assertExists()
        compose.runOnIdle { assertEquals(listOf("existing"), owner.disconnects) }
    }

    @Test fun pluginsAndSkillsComeFromOwnerInventoryAndIgnoreBundledPlaceholder() {
        val owner = Owner()
        render(owner, NextToolsPage.PLUGINS)
        compose.onNodeWithText(context.getString(R.string.next_tools_plugin, "file-connector")).assertExists()
        compose.onNodeWithText(context.getString(R.string.next_tools_skill, "演示技能")).assertExists()
        compose.onNodeWithText(context.getString(R.string.next_tools_plugin, "local-builtin")).assertDoesNotExist()
    }
}
