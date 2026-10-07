package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

private data class InventoryRow(
    val id: String,
    val name: String,
    val detail: String,
    val remote: Boolean,
)

/** Browses real installed/connected plugin inventory; does not advertise unsupported storefront apps. */
@Composable
internal fun PluginInventoryBrowser(
    localIds: List<String>,
    remote: PluginInventorySnapshot?,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    onReturnToChat: () -> Unit,
) {
    val colors = DsTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var selectedRow by remember { mutableStateOf<InventoryRow?>(null) }
    val installedStatus = stringResource(R.string.tools_catalog_installed)
    val connectionStatus = stringResource(R.string.tools_catalog_connections)
    val local = localIds.sorted().map { id ->
        val label = when (id) {
            "local-builtin" -> stringResource(R.string.skills_title)
            "android-runtime" -> stringResource(R.string.tools_capability_terminal)
            "local-language-server" -> stringResource(R.string.tools_capability_code)
            "android-device" -> stringResource(R.string.tools_capability_device)
            "local-vision" -> stringResource(R.string.tools_capability_vision)
            "android-automation", "android-webhook" -> stringResource(R.string.tools_capability_automation)
            else -> id
        }
        InventoryRow("local:$id", label, installedStatus, remote = false)
    }
    val connected = remote?.entries.orEmpty().map { plugin ->
        InventoryRow(
            "remote:${plugin.moduleName}",
            plugin.moduleName,
            connectionStatus,
            remote = true,
        )
    }
    val list = (if (selectedCategory == 0) local else connected)
        .filter { item ->
            query.isBlank() || item.name.contains(query.trim(), ignoreCase = true) ||
                item.id.contains(query.trim(), ignoreCase = true)
        }

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(Modifier.fillMaxSize().padding(top = DsSpacing.medium)) {
            DsTopBar(
                title = stringResource(R.string.tools_catalog_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
                modifier = Modifier.padding(horizontal = DsSpacing.comfortable),
            )
            DsTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                singleLine = true,
                leadingIcon = {
                    Icon(FeatherIcons.Search, contentDescription = null)
                },
                placeholder = { Text(stringResource(R.string.tools_catalog_search)) },
            )
            Row(
                modifier = Modifier.padding(horizontal = DsSpacing.comfortable),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                DsPill(
                    text = stringResource(R.string.tools_catalog_installed),
                    selected = selectedCategory == 0,
                    onClick = { selectedCategory = 0 },
                )
                DsPill(
                    text = stringResource(R.string.tools_catalog_connections),
                    selected = selectedCategory == 1,
                    onClick = { selectedCategory = 1 },
                )
            }
            if (list.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(DsSpacing.xlarge),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.tools_catalog_empty),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                    if (selectedCategory == 1) {
                        DsButton(
                            text = stringResource(R.string.tools_external_services),
                            variant = DsButtonVariant.Info,
                            onClick = onManageConnections,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(DsSpacing.comfortable),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    items(list, key = { it.id }) { item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedRow = item },
                            color = colors.bgLayer1,
                            shape = DsShapes.block,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(DsSpacing.comfortable),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                            ) {
                                Surface(
                                    color = colors.bgModulePlatform,
                                    shape = DsShapes.row,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_ui_plugin),
                                        contentDescription = null,
                                        tint = colors.labelSecondary,
                                        modifier = Modifier.padding(DsSpacing.medium).size(24.dp),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        color = colors.labelPrimary,
                                        style = DsType.std14Strong.withReadingWeight(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        item.detail,
                                        color = colors.labelTertiary,
                                        style = DsType.small13.withReadingWeight(),
                                    )
                                }
                                DsButton(
                                    text = stringResource(R.string.tools_catalog_details),
                                    variant = DsButtonVariant.Info,
                                    onClick = { selectedRow = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    selectedRow?.let { item ->
        DsBottomSheet(
            title = item.name,
            onDismiss = { selectedRow = null },
        ) {
            Text(
                item.id.removePrefix("local:").removePrefix("remote:"),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Text(
                item.detail,
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
            DsButton(
                text = stringResource(
                    if (item.remote) R.string.tools_external_services
                    else R.string.tools_catalog_return_chat,
                ),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    selectedRow = null
                    if (item.remote) onManageConnections() else onReturnToChat()
                },
            )
        }
    }
}
