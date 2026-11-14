package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SySearchField
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.components.SyFilterChip
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

private data class ConnectorItem(
    val id: String,
    val title: String,
    val description: String,
    val visual: ExtensionPresentation,
    val configured: Boolean,
    val local: Boolean,
    val github: Boolean,
    val addNew: Boolean = false,
)

/**
 * The connector directory only lists GitHub (a built-in option), currently registered MCP
 * servers and the actual MCP add action. It does not promise unimplemented app integrations.
 */
@Composable
internal fun ConnectorInventoryBrowser(
    servers: List<UiMcpServerSnapshot>,
    githubConfigured: Boolean,
    loading: Boolean = false,
    onGitHub: () -> Unit,
    onMcp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalSyColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableIntStateOf(0) }
    val githubVisual = extensionPresentation("github", "GitHub", "", ExtensionKind.CONNECTOR)
    val github = ConnectorItem(
        id = "github",
        title = "GitHub",
        description = if (githubConfigured) "代码仓库与开发协作 · 已配置"
            else "连接代码仓库，浏览与管理项目",
        visual = githubVisual,
        configured = githubConfigured,
        local = false,
        github = true,
    )
    val connected = servers.sortedBy { it.id }.map { server ->
        val visual = extensionPresentation(server.id, server.id, "", ExtensionKind.CONNECTOR)
        val local = pluginTransportMatches(server.transport, PluginTransportFilter.STDIO)
        ConnectorItem(
            id = server.id,
            title = visual.title,
            description = "已添加 · " + server.tools.size + " 项工具 · " + server.transport.uppercase(),
            visual = visual,
            configured = true,
            local = local,
            github = false,
        )
    }
    val addVisual = extensionPresentation("new-mcp", "MCP", "", ExtensionKind.CONNECTOR)
    val add = ConnectorItem(
        id = "add-mcp", title = "添加 MCP 连接器",
        description = "连接受支持的外部工具服务",
        visual = addVisual.copy(icon = FeatherIcons.Plus),
        configured = false, local = false, github = false, addNew = true,
    )
    val items = (listOf(github) + connected + add).filter { item ->
        when (category) {
            1 -> item.configured
            2 -> item.local && !item.addNew
            3 -> !item.local && !item.addNew
            else -> true
        }
    }.filter { item ->
        inventorySearchScore(query, item.title, item.id, item.description) != null
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("connector-category-list"),
            contentPadding = PaddingValues(horizontal = SyMetric.gapSection),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            items(4) { option ->
                SyFilterChip(
                    text = when (option) {
                        1 -> "已配置"
                        2 -> "本地服务"
                        3 -> "网络服务"
                        else -> "全部"
                    },
                    selected = category == option,
                    onClick = { category = option },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = SyMetric.gapSection, vertical = SyMetric.gapMd,
            ),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        ) {
            if (query.isBlank()) item(key = "connector-settings-link") {
                SyButton(
                    text = "能力与权限设置",
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                    modifier = Modifier.fillMaxWidth().testTag("connector-settings-entry"),
                    onClick = onOpenSettings,
                )
            }
            if (items.isEmpty()) item(key = "connector-empty") {
                Text(
                    text = if (query.isBlank()) "此分类下暂无连接器" else "没有找到匹配的连接器",
                    color = colors.textSecondary,
                    style = DsType.std14.withReadingWeight(),
                    modifier = Modifier.fillMaxWidth().padding(SyMetric.gapMd),
                )
            }
            items(items, key = { it.id }) { item ->
                ExtensionCatalogCard(
                    id = item.id,
                    title = item.title,
                    description = item.description,
                    icon = item.visual.icon,
                    accent = item.visual.accent,
                    tagPrefix = "connector-inventory",
                    enabled = !loading,
                    actionText = when {
                        item.addNew -> "添加"
                        item.github && !item.configured -> "连接"
                        else -> "管理"
                    },
                    onOpen = { if (item.github) onGitHub() else onMcp() },
                    onAction = { if (item.github) onGitHub() else onMcp() },
                )
            }
        }
        SySearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapMd)
                .testTag("connector-inventory-search"),
            placeholder = "搜索连接器",
        )
    }
}
