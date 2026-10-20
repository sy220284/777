package com.labteto.dshmobile.ui.screens.tools

import com.shenyan.designsystem.foundation.SyMetric



import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.vector.ImageVector
import com.labteto.dshmobile.ui.shenyan.SySearchField
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot as McpServerSnapshot
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyFilterChip
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.shenyan.designsystem.components.SySwitchControl
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Real connection-transport categories; unknown future transports remain under All. */
internal enum class PluginTransportFilter { ALL, HTTP, STDIO }

internal fun pluginTransportMatches(transport: String, filter: PluginTransportFilter): Boolean =
    when (filter) {
        PluginTransportFilter.ALL -> true
        PluginTransportFilter.HTTP -> transport.contains("http", ignoreCase = true) ||
            transport.contains("sse", ignoreCase = true)
        PluginTransportFilter.STDIO -> transport.contains("stdio", ignoreCase = true)
    }

private data class InventoryRow(
    val id: String,
    val name: String,
    val detail: String,
    val connectedService: Boolean,
    val icon: ImageVector = FeatherIcons.Globe,
    val available: Boolean = true,
    val modelInvocable: Boolean = true,
    val installedSkill: Boolean = false,
    val installablePreset: Boolean = false,
)

/** Browses real installed/connected plugin inventory; does not advertise unsupported storefront apps. */
@Composable
internal fun PluginInventoryBrowser(
    localIds: List<String>,
    connectedServices: List<McpServerSnapshot> = emptyList(),
    skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    presets: List<com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry> = emptyList(),
    createdSkillId: String? = null,
    skillEditor: com.labteto.dshmobile.local.presentation.LocalSkillEditorUiEntry? = null,
    savedSkillId: String? = null,
    skillsOnly: Boolean = false,
    embedded: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    onReturnToChat: () -> Unit,
    onUseCapability: ((String) -> Unit)? = null,
    onInstallPreset: ((String) -> Unit)? = null,
    onCreateSkill: ((String, String, String, String) -> Unit)? = null,
    onImportSkill: (() -> Unit)? = null,
    onRemoveSkill: ((String) -> Unit)? = null,
    onOpenSkillEditor: ((String) -> Unit)? = null,
    onCloseSkillEditor: (() -> Unit)? = null,
    onSaveSkillDocument: ((String, String) -> Unit)? = null,
    onSetSkillModelInvocable: ((String, Boolean) -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val skillCreationPrompt = stringResource(R.string.skills_create_in_chat_prompt)
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var transportFilter by rememberSaveable { mutableStateOf(PluginTransportFilter.ALL) }
    var selectedRow by remember { mutableStateOf<InventoryRow?>(null) }
    var showCreateSkill by remember { mutableStateOf(false) }
    var removeSkillName by remember { mutableStateOf<String?>(null) }
    var newSkillId by remember { mutableStateOf("") }
    var newSkillDisplayName by remember { mutableStateOf("") }
    var newSkillDescription by remember { mutableStateOf("") }
    var newSkillBody by remember { mutableStateOf("") }
    var editedSkillBody by remember { mutableStateOf("") }
    var editedSkillDisplayName by remember { mutableStateOf("") }
    LaunchedEffect(createdSkillId) {
        if (showCreateSkill && createdSkillId != null && createdSkillId == newSkillId) {
            showCreateSkill = false
            newSkillId = ""
            newSkillDisplayName = ""
            newSkillDescription = ""
            newSkillBody = ""
        }
    }
    LaunchedEffect(skillEditor?.id, skillEditor?.document) {
        if (skillEditor != null) {
            editedSkillBody = skillEditor.document
            editedSkillDisplayName = skills.firstOrNull { it.name == skillEditor.id }?.displayName.orEmpty()
        }
    }
    val installedStatus = stringResource(R.string.tools_catalog_installed)
    val connectionStatus = stringResource(R.string.tools_catalog_connections)
    // Built-in skill runtime is exposed by the 技能 tab, not a duplicate plugin card.
    val local = localIds.filterNot { it == "android-local-builtins" }.sorted().map { id ->
        val label = when (id) {
            "android-local-builtins" -> stringResource(R.string.skills_title)
            "android-runtime" -> stringResource(R.string.tools_capability_terminal)
            "local-language-server" -> stringResource(R.string.tools_capability_code)
            "android-device" -> stringResource(R.string.tools_capability_device)
            "local-vision" -> stringResource(R.string.tools_capability_vision)
            "android-automation", "android-webhook" -> stringResource(R.string.tools_capability_automation)
            else -> id
        }
        val (hint, icon) = when (id) {
            "android-local-builtins" -> R.string.plugin_skills_hint to FeatherIcons.BookOpen
            "android-runtime" -> R.string.plugin_terminal_hint to FeatherIcons.Terminal
            "local-language-server" -> R.string.plugin_code_hint to FeatherIcons.Code
            "android-device" -> R.string.plugin_device_hint to FeatherIcons.Device
            "local-vision" -> R.string.plugin_vision_hint to FeatherIcons.Image
            "android-automation", "android-webhook" -> R.string.plugin_automation_hint to FeatherIcons.Clock
            else -> R.string.plugin_external_hint to FeatherIcons.Tool
        }
        InventoryRow("local:$id", label, stringResource(hint), connectedService = false, icon = icon)
    }
    val connected = connectedServices.sortedBy { it.id }.map { server ->
        InventoryRow(
            id = "service:${server.id}",
            name = server.id,
            detail = stringResource(R.string.tools_server_summary, server.transport, server.tools.size),
            connectedService = true,
            available = false,
            icon = FeatherIcons.Globe,
        )
    }
    val filteredConnections = connected.filter { row ->
        val server = connectedServices.firstOrNull { "service:${it.id}" == row.id }
        server != null && pluginTransportMatches(server.transport.toString(), transportFilter)
    }
    val installedSkills = skills.map { skill ->
        val display = skill.displayName
        InventoryRow("skill:" + skill.name, display, skill.description,
            connectedService = false, icon = FeatherIcons.BookOpen,
            available = true, modelInvocable = skill.modelInvocable, installedSkill = true)
    }
    val availablePresets = presets.filterNot { it.installed }.map { preset ->
        InventoryRow("skill:" + preset.id, preset.title, preset.description,
            connectedService = false, icon = FeatherIcons.BookOpen,
            available = false, installablePreset = true)
    }
    val list = (if (skillsOnly) installedSkills + availablePresets else when (selectedCategory) {
        1 -> filteredConnections
        2 -> local.filter { it.id in setOf("local:android-runtime", "local:local-language-server", "local:android-local-builtins") }
        3 -> local.filter { it.id in setOf("local:android-device", "local:android-automation", "local:android-webhook") }
        4 -> local.filter { it.id == "local:local-vision" }
        else -> local
    }).filter { item ->
            query.isBlank() || item.name.contains(query.trim(), ignoreCase = true) ||
                item.id.contains(query.trim(), ignoreCase = true) ||
                item.detail.contains(query.trim(), ignoreCase = true)
        }

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!embedded) SyTopBar(
                title = stringResource(if (skillsOnly) R.string.skills_title else R.string.tools_catalog_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
            SySearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapMd)
                    .testTag("plugin-inventory-search"),
                placeholder = stringResource(if (skillsOnly) R.string.skills_catalog_search else R.string.tools_catalog_search),
            )
            if (!skillsOnly) LazyRow(
                modifier = Modifier.testTag("plugin-category-list"),
                contentPadding = PaddingValues(horizontal = SyMetric.gapSection),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
            ) {
                items(5) { category ->
                    SyFilterChip(
                        text = when (category) {
                            1 -> "${connectionStatus} · ${connectedServices.size}"
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
            if (!skillsOnly && selectedCategory == 1) {
                // Design screen 18: connection transport is a real property from
                // the service handshake, never an invented plugin-store category.
                val options = PluginTransportFilter.entries
                val counts = options.map { filter ->
                    connectedServices.count { pluginTransportMatches(it.transport.toString(), filter) }
                }
                SySegmented(
                    segments = options.mapIndexed { index, filter ->
                        SySegment(
                            filter.name,
                            "${stringResource(
                                when (filter) {
                                    PluginTransportFilter.ALL -> R.string.plugin_transport_all
                                    PluginTransportFilter.HTTP -> R.string.plugin_transport_http
                                    PluginTransportFilter.STDIO -> R.string.plugin_transport_stdio
                                },
                            )} · ${counts[index]}",
                        )
                    },
                    selectedKey = transportFilter.name,
                    onSelect = { key ->
                        transportFilter = options.firstOrNull { it.name == key } ?: PluginTransportFilter.ALL
                    },
                    role = Role.Tab,
                    hapticOnChange = true,
                    stretch = true,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapMd)
                        .testTag("plugin-transport-tabs"),
                )
                SyButton(
                    text = stringResource(R.string.tools_external_services),
                    variant = SyButtonVariant.Ghost,
                    onClick = onManageConnections,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapSection),
                )
            }
            if (loading && list.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.common_loading),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textTertiary,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapSm),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = SyMetric.gapSection,
                    vertical = SyMetric.gapMd,
                ),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (skillsOnly && query.isBlank()) {
                    item(key = "skill-creation-actions") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            ) {
                                if (onImportSkill != null) {
                                    SyButton(
                                        text = stringResource(R.string.skills_import_file),
                                        onClick = onImportSkill,
                                        enabled = !loading,
                                        modifier = Modifier.weight(1f).testTag("skill-import-file"),
                                    )
                                }
                                if (onUseCapability != null) {
                                    SyButton(
                                        text = stringResource(R.string.skills_create_in_chat),
                                        onClick = { onUseCapability(skillCreationPrompt) },
                                        enabled = !loading,
                                        variant = SyButtonVariant.Info,
                                        modifier = Modifier.weight(1f).testTag("skill-create-in-chat"),
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.skills_import_hint),
                                modifier = Modifier.fillMaxWidth(),
                                style = DsType.small13.withReadingWeight(),
                                color = colors.textSecondary,
                                maxLines = 2,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(R.string.skills_catalog_overview, skills.size, availablePresets.size),
                                    style = DsType.small13Strong.withReadingWeight(),
                                    color = colors.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (onCreateSkill != null) {
                                    SyButton(
                                        text = stringResource(R.string.skills_create_manual),
                                        variant = SyButtonVariant.Ghost,
                                        onClick = { showCreateSkill = true },
                                        enabled = !loading,
                                        size = SyButtonSize.Small,
                                        modifier = Modifier.testTag("skill-create-manual"),
                                    )
                                }
                            }
                        }
                    }
                }
                if (error != null) {
                    item(key = "inventory-error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(SyMetric.gapPage),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(error, style = DsType.std14.withReadingWeight(), color = colors.dangerInk)
                            if (onRetry != null) {
                                SyButton(text = stringResource(R.string.common_retry), onClick = onRetry)
                            }
                        }
                    }
                }
                if (list.isEmpty() && error == null) {
                    item(key = "inventory-empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(SyMetric.gapPage),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(if (loading) R.string.common_loading else if (query.isNotBlank()) R.string.tools_catalog_no_matches else if (skillsOnly) R.string.skills_catalog_empty else R.string.tools_catalog_empty),
                                style = DsType.std14.withReadingWeight(),
                                color = colors.textSecondary,
                            )
                            // The existing V5 service-nav action above remains available here.
                            // Do not render a second visually identical external-service button.
                        }
                    }
                } else {
                    items(list, key = { it.id }) { item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("plugin-inventory-card-${item.id}"),
                            onClick = { selectedRow = item },
                            color = colors.materialL2,
                            shape = DsShapes.row,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 72.dp)
                                    .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            ) {
                                Box(
                                    modifier = Modifier.size(32.dp).testTag("plugin-inventory-icon-${item.id}"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                                ) {
                                    Text(
                                        item.name,
                                        modifier = Modifier.fillMaxWidth()
                                            .testTag("plugin-inventory-title-${item.id}"),
                                        color = colors.text,
                                        style = DsType.std14Strong.withReadingWeight(),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        item.detail,
                                        modifier = Modifier.fillMaxWidth()
                                            .testTag("plugin-inventory-detail-${item.id}"),
                                        color = colors.textSecondary,
                                        style = DsType.small13.withReadingWeight(),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val usePrompt = if (item.installedSkill) "@skill:${item.id.substringAfter(":")}\n"
                                    else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                                SyButton(
                                    enabled = !loading,
                                    text = stringResource(
                                        if (item.installablePreset && onInstallPreset != null) R.string.skills_install
                                        else if (onUseCapability != null && item.available) R.string.plugin_use
                                        else R.string.tools_catalog_details
                                    ),
                                    variant = SyButtonVariant.Info,
                                    size = SyButtonSize.Small,
                                    modifier = Modifier.testTag("plugin-inventory-action-${item.id}"),
                                    onClick = {
                                        if (item.installablePreset && onInstallPreset != null) onInstallPreset(item.id.substringAfter(":"))
                                        else if (onUseCapability != null && item.available) onUseCapability(usePrompt)
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
        SyResponsiveSheet(
            title = item.name,
            onDismiss = { selectedRow = null },
            scrollable = true,
            footer = {
                val usePrompt = if (item.installedSkill) "@skill:${item.id.substringAfter(":")}\n" else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                SyButton(
                    text = stringResource(
                        if (item.installablePreset && onInstallPreset != null) R.string.skills_install
                        else if (onUseCapability != null && item.available) R.string.plugin_use
                        else if (item.connectedService) R.string.tools_external_services
                        else R.string.tools_catalog_return_chat,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("plugin-detail-primary-action"),
                    enabled = !loading,
                    size = SyButtonSize.Large,
                    onClick = {
                        selectedRow = null
                        if (item.installablePreset && onInstallPreset != null) onInstallPreset(item.id.substringAfter(":"))
                        else if (onUseCapability != null && item.available) onUseCapability(usePrompt)
                        else if (item.connectedService) onManageConnections() else onReturnToChat()
                    },
                )
            },
        ) {
            Text(
                item.id.removePrefix("local:").removePrefix("service:"),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            Text(
                item.detail,
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            if (item.installedSkill && !item.modelInvocable) {
                Text(stringResource(R.string.skills_manual_only), style = DsType.small13.withReadingWeight(), color = colors.textTertiary)
            }



            if (item.installedSkill && onOpenSkillEditor != null) {
                SyButton(
                    text = stringResource(R.string.skills_edit),
                    variant = SyButtonVariant.Info,
                    enabled = !loading,
                    onClick = {
                        onOpenSkillEditor(item.id.substringAfter(":"))
                        selectedRow = null
                    },
                )
            }
            if (item.installedSkill && onSetSkillModelInvocable != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.skills_auto_invocation),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                    SySwitchControl(
                        enabled = !loading,
                        checked = item.modelInvocable,
                        onCheckedChange = {
                            onSetSkillModelInvocable(item.id.substringAfter(":"), it)
                            selectedRow = null
                        },
                    )
                }
            }
            if (item.installedSkill && onRemoveSkill != null) {
                SyButton(
                    text = stringResource(R.string.common_remove),
                    variant = SyButtonVariant.Info,
                    enabled = !loading,
                    onClick = {
                        removeSkillName = item.id.substringAfter(":")
                        selectedRow = null
                    },
                )
            }
        }
    }
    if (showCreateSkill && onCreateSkill != null) {
        val validId = newSkillId.matches(Regex("[a-z][a-z0-9-]{1,47}"))
        val validName = newSkillDisplayName.trim().length in 1..40 &&
            newSkillDisplayName.any { it in '\u4e00'..'\u9fff' }
        val canCreate = validId && validName && newSkillDescription.isNotBlank() &&
            newSkillDescription.length <= 240 &&
            newSkillBody.isNotBlank() && newSkillBody.length <= 12_000
        SyResponsiveSheet(
            title = stringResource(R.string.skills_create),
            onDismiss = { showCreateSkill = false },
            scrollable = true,
            footer = {
                SyButton(
                    text = stringResource(R.string.skills_install),
                    onClick = {
                        onCreateSkill(newSkillId, newSkillDisplayName.trim(), newSkillDescription, newSkillBody)
                    },
                    enabled = canCreate && !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            SyTextField(
                value = newSkillId,
                onValueChange = { newSkillId = it },
                label = { Text(stringResource(R.string.skills_id_label)) },
                supportingText = { Text(stringResource(R.string.skills_id_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SyTextField(
                value = newSkillDisplayName,
                onValueChange = { newSkillDisplayName = it },
                label = { Text(stringResource(R.string.skills_display_name_label)) },
                supportingText = { Text(stringResource(R.string.skills_display_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SyTextField(
                value = newSkillDescription,
                onValueChange = { newSkillDescription = it },
                label = { Text(stringResource(R.string.skills_description_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            SyTextField(
                value = newSkillBody,
                onValueChange = { newSkillBody = it },
                label = { Text(stringResource(R.string.skills_instruction_label)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    removeSkillName?.let { id ->
        SyResponsiveSheet(
            title = stringResource(R.string.skills_remove_title),
            onDismiss = { removeSkillName = null },
            seamlessBackground = false,
            footer = {
                SyButton(
                    text = stringResource(R.string.common_remove),
                    onClick = {
                        onRemoveSkill?.invoke(id)
                        removeSkillName = null
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            Text(stringResource(R.string.skills_remove_message, id),
                style = DsType.std14.withReadingWeight(), color = colors.textSecondary)
        }
    }

    if (skillEditor != null && onSaveSkillDocument != null) {
        SyResponsiveSheet(
            title = stringResource(R.string.skills_edit),
            onDismiss = { onCloseSkillEditor?.invoke() },
            scrollable = true,
            footer = {
                SyButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        onSaveSkillDocument(skillEditor.id, withLocalSkillDisplayName(editedSkillBody, editedSkillDisplayName))
                    },
                    enabled = !loading && editedSkillBody.isNotBlank() && editedSkillBody.length <= 20_000 &&
                        editedSkillDisplayName.trim().length in 1..40 &&
                        editedSkillDisplayName.any { it in '\u4e00'..'\u9fff' },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            Text(
                stringResource(R.string.skills_edit_hint),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
            SyTextField(
                value = editedSkillDisplayName,
                onValueChange = { editedSkillDisplayName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.skills_display_name_label)) },
            )
            SyTextField(
                value = editedSkillBody,
                onValueChange = { editedSkillBody = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 10,
                label = { Text(stringResource(R.string.skills_instruction_label)) },
            )
        }
    }

}

/** Edit the existing SKILL.md rather than keeping a second copy of the display name. */
internal fun withLocalSkillDisplayName(document: String, displayName: String): String {
    val name = displayName.trim()
    require(name.length in 1..40 && name.any { it in '\u4e00'..'\u9fff' } && '\n' !in name && '\r' !in name)
    val field = "display-name: \"" + name.replace('"', '\'') + "\""
    val lines = document.lines().toMutableList()
    if (lines.firstOrNull()?.trim() == "---") {
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        if (end > 0) {
            val index = (1 until end).firstOrNull { lines[it].trimStart().startsWith("display-name:") }
            if (index != null) lines[index] = field else lines.add(end, field)
            return lines.joinToString("\n")
        }
    }
    return "---\n$field\n---\n$document"
}
