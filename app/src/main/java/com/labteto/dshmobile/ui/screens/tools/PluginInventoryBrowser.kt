package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.vector.ImageVector
import com.labteto.dshmobile.ui.components.DsSearchField
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
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsFilterChip
import com.labteto.dshmobile.ui.components.DsButtonSize
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
    val icon: ImageVector = FeatherIcons.Globe,
    val available: Boolean = true,
)

/** Browses real installed/connected plugin inventory; does not advertise unsupported storefront apps. */
@Composable
internal fun PluginInventoryBrowser(
    localIds: List<String>,
    skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    skillsOnly: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    remote: PluginInventorySnapshot?,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    onReturnToChat: () -> Unit,
    onUseCapability: ((String) -> Unit)? = null,
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
        val (hint, icon) = when (id) {
            "local-builtin" -> R.string.plugin_skills_hint to FeatherIcons.BookOpen
            "android-runtime" -> R.string.plugin_terminal_hint to FeatherIcons.Terminal
            "local-language-server" -> R.string.plugin_code_hint to FeatherIcons.Code
            "android-device" -> R.string.plugin_device_hint to FeatherIcons.Device
            "local-vision" -> R.string.plugin_vision_hint to FeatherIcons.Image
            "android-automation", "android-webhook" -> R.string.plugin_automation_hint to FeatherIcons.Clock
            else -> R.string.plugin_external_hint to FeatherIcons.Tool
        }
        InventoryRow("local:$id", label, stringResource(hint), remote = false, icon = icon)
    }
    val connected = remote?.entries.orEmpty().map { plugin ->
        val status = when {
            !plugin.enabled -> stringResource(R.string.tools_plugin_disabled)
            plugin.fiberPhase == PluginFiberPhase.ACTIVE -> stringResource(R.string.tools_plugin_active)
            plugin.fiberPhase == PluginFiberPhase.FAILED -> stringResource(R.string.tools_plugin_failed)
            else -> stringResource(R.string.tools_plugin_enabled)
        }
        InventoryRow(
            "remote:${plugin.moduleName}",
            plugin.moduleName,
            status,
            remote = true,
            available = plugin.enabled && plugin.fiberPhase != PluginFiberPhase.FAILED,
        )
    }
    val installedSkills = skills.map { skill ->
        InventoryRow("skill:${skill.name}", skill.name, skill.description, remote = false, icon = FeatherIcons.BookOpen, available = skill.modelInvocable)
    }
    val list = (if (skillsOnly) installedSkills else when (selectedCategory) {
        1 -> connected
        2 -> local.filter { it.id in setOf("local:android-runtime", "local:local-language-server", "local:local-builtin") }
        3 -> local.filter { it.id in setOf("local:android-device", "local:android-automation", "local:android-webhook") }
        4 -> local.filter { it.id == "local:local-vision" }
        else -> local
    }).filter { item ->
            query.isBlank() || item.name.contains(query.trim(), ignoreCase = true) ||
                item.id.contains(query.trim(), ignoreCase = true)
        }

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = DsSpacing.medium)) {
            DsTopBar(
                title = stringResource(if (skillsOnly) R.string.skills_title else R.string.tools_catalog_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
                modifier = Modifier.padding(horizontal = DsSpacing.comfortable),
            )
            DsSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                placeholder = stringResource(R.string.tools_catalog_search),
            )
            if (!skillsOnly) LazyRow(
                contentPadding = PaddingValues(horizontal = DsSpacing.comfortable),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                items(5) { category ->
                    DsFilterChip(
                        text = when (category) {
                            1 -> connectionStatus
                            2 -> stringResource(R.string.plugin_category_work)
                            3 -> stringResource(R.string.plugin_category_device)
                            4 -> stringResource(R.string.plugin_category_design)
                            else -> installedStatus
                        },
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }
            if (loading || error != null || list.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(DsSpacing.xlarge),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        error ?: stringResource(if (loading) R.string.common_loading else if (skillsOnly) R.string.skills_catalog_empty else R.string.tools_catalog_empty),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                    if (error != null && onRetry != null) {
                        DsButton(text = stringResource(R.string.common_retry), onClick = onRetry)
                    } else if (!loading && !skillsOnly && selectedCategory == 1) {
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
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = colors.labelSecondary,
                                        modifier = Modifier.padding(8.dp).size(24.dp),
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
                                val usePrompt = stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                                DsButton(
                                    text = stringResource(if (onUseCapability != null && item.available) R.string.plugin_use else R.string.tools_catalog_details),
                                    variant = DsButtonVariant.Info,
                                    size = DsButtonSize.Small,
                                    onClick = {
                                        if (onUseCapability != null && item.available) onUseCapability(usePrompt)
                                        else selectedRow = item
                                    },
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
            if (item.id.startsWith("skill:") && !item.available) {
                Text(stringResource(R.string.skills_manual_only), style = DsType.small13.withReadingWeight(), color = colors.labelTertiary)
            }
            val usePrompt = stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
            DsButton(
                text = stringResource(
                    if (onUseCapability != null && item.available) R.string.plugin_use
                    else if (item.remote) R.string.tools_external_services
                    else R.string.tools_catalog_return_chat,
                ),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    selectedRow = null
                    if (onUseCapability != null && item.available) onUseCapability(usePrompt)
                    else if (item.remote) onManageConnections() else onReturnToChat()
                },
            )
        }
    }
}
