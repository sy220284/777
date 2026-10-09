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
import com.labteto.dshmobile.ui.components.DsSwitch
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
    val installedSkill: Boolean = false,
    val installablePreset: Boolean = false,
)

/** Browses real installed/connected plugin inventory; does not advertise unsupported storefront apps. */
@Composable
internal fun PluginInventoryBrowser(
    localIds: List<String>,
    skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    presets: List<com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry> = emptyList(),
    createdSkillId: String? = null,
    skillEditor: com.labteto.dshmobile.local.presentation.LocalSkillEditorUiEntry? = null,
    savedSkillId: String? = null,
    skillsOnly: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    remote: PluginInventorySnapshot?,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    onReturnToChat: () -> Unit,
    onUseCapability: ((String) -> Unit)? = null,
    onInstallPreset: ((String) -> Unit)? = null,
    onCreateSkill: ((String, String, String) -> Unit)? = null,
    onRemoveSkill: ((String) -> Unit)? = null,
    onOpenSkillEditor: ((String) -> Unit)? = null,
    onCloseSkillEditor: (() -> Unit)? = null,
    onSaveSkillDocument: ((String, String) -> Unit)? = null,
    onSetSkillModelInvocable: ((String, Boolean) -> Unit)? = null,
) {
    val colors = DsTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var selectedRow by remember { mutableStateOf<InventoryRow?>(null) }
    var showCreateSkill by remember { mutableStateOf(false) }
    var removeSkillName by remember { mutableStateOf<String?>(null) }
    var newSkillId by remember { mutableStateOf("") }
    var newSkillDescription by remember { mutableStateOf("") }
    var newSkillBody by remember { mutableStateOf("") }
    var editedSkillBody by remember { mutableStateOf("") }
    LaunchedEffect(createdSkillId) {
        if (showCreateSkill && createdSkillId != null && createdSkillId == newSkillId) {
            showCreateSkill = false
            newSkillId = ""
            newSkillDescription = ""
            newSkillBody = ""
        }
    }
    LaunchedEffect(skillEditor?.id, skillEditor?.document) {
        if (skillEditor != null) editedSkillBody = skillEditor.document
    }
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
        val display = presets.firstOrNull { it.id == skill.name }?.title ?: skill.name
        InventoryRow("skill:" + skill.name, display, skill.description,
            remote = false, icon = FeatherIcons.BookOpen,
            available = skill.modelInvocable, installedSkill = true)
    }
    val availablePresets = presets.filterNot { it.installed }.map { preset ->
        InventoryRow("skill:" + preset.id, preset.title, preset.description,
            remote = false, icon = FeatherIcons.BookOpen,
            available = false, installablePreset = true)
    }
    val list = (if (skillsOnly) installedSkills + availablePresets else when (selectedCategory) {
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
                placeholder = stringResource(if (skillsOnly) R.string.skills_catalog_search else R.string.tools_catalog_search),
            )
            if (skillsOnly && onCreateSkill != null) {
                DsButton(
                    text = stringResource(R.string.skills_create),
                    onClick = { showCreateSkill = true },
                    modifier = Modifier.padding(horizontal = DsSpacing.comfortable),
                )
            }
            if (!skillsOnly) LazyRow(
                modifier = Modifier.testTag("plugin-category-list"),
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
                                val usePrompt = if (item.installedSkill) "【技能:${item.id.substringAfter(":")}】\n" else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                                DsButton(
                                    text = stringResource(
                                        if (item.installablePreset) R.string.skills_install
                                        else if (onUseCapability != null && item.available) R.string.plugin_use
                                        else R.string.tools_catalog_details
                                    ),
                                    variant = DsButtonVariant.Info,
                                    size = DsButtonSize.Small,
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
        DsBottomSheet(
            title = item.name,
            onDismiss = { selectedRow = null },
            scrollable = true,
            footer = {
                val usePrompt = if (item.installedSkill) "【技能:${item.id.substringAfter(":")}】\n" else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                DsButton(
                    text = stringResource(
                        if (item.installablePreset) R.string.skills_install
                        else if (onUseCapability != null && item.available) R.string.plugin_use
                        else if (item.remote) R.string.tools_external_services
                        else R.string.tools_catalog_return_chat,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    size = DsButtonSize.Large,
                    onClick = {
                        selectedRow = null
                        if (item.installablePreset && onInstallPreset != null) onInstallPreset(item.id.substringAfter(":"))
                        else if (onUseCapability != null && item.available) onUseCapability(usePrompt)
                        else if (item.remote) onManageConnections() else onReturnToChat()
                    },
                )
            },
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
            if (item.installedSkill && !item.available) {
                Text(stringResource(R.string.skills_manual_only), style = DsType.small13.withReadingWeight(), color = colors.labelTertiary)
            }



            if (item.installedSkill && onOpenSkillEditor != null) {
                DsButton(
                    text = stringResource(R.string.skills_edit),
                    variant = DsButtonVariant.Info,
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
                        color = colors.labelSecondary,
                    )
                    DsSwitch(
                        checked = item.available,
                        onCheckedChange = {
                            onSetSkillModelInvocable(item.id.substringAfter(":"), it)
                            selectedRow = null
                        },
                    )
                }
            }
            if (item.installedSkill && onRemoveSkill != null) {
                DsButton(
                    text = stringResource(R.string.common_remove),
                    variant = DsButtonVariant.Info,
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
        val canCreate = validId && newSkillDescription.isNotBlank() &&
            newSkillDescription.length <= 240 &&
            newSkillBody.isNotBlank() && newSkillBody.length <= 12_000
        DsBottomSheet(
            title = stringResource(R.string.skills_create),
            onDismiss = { showCreateSkill = false },
            scrollable = true,
            footer = {
                DsButton(
                    text = stringResource(R.string.skills_install),
                    onClick = {
                        onCreateSkill(newSkillId, newSkillDescription, newSkillBody)
                    },
                    enabled = canCreate && !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            DsTextField(
                value = newSkillId,
                onValueChange = { newSkillId = it },
                label = { Text(stringResource(R.string.skills_id_label)) },
                supportingText = { Text(stringResource(R.string.skills_id_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DsTextField(
                value = newSkillDescription,
                onValueChange = { newSkillDescription = it },
                label = { Text(stringResource(R.string.skills_description_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            DsTextField(
                value = newSkillBody,
                onValueChange = { newSkillBody = it },
                label = { Text(stringResource(R.string.skills_instruction_label)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    removeSkillName?.let { id ->
        DsBottomSheet(
            title = stringResource(R.string.skills_remove_title),
            onDismiss = { removeSkillName = null },
            footer = {
                DsButton(
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
                style = DsType.std14.withReadingWeight(), color = colors.labelSecondary)
        }
    }

    if (skillEditor != null && onSaveSkillDocument != null) {
        DsBottomSheet(
            title = stringResource(R.string.skills_edit),
            onDismiss = { onCloseSkillEditor?.invoke() },
            scrollable = true,
            footer = {
                DsButton(
                    text = stringResource(R.string.common_save),
                    onClick = { onSaveSkillDocument(skillEditor.id, editedSkillBody) },
                    enabled = !loading && editedSkillBody.isNotBlank() && editedSkillBody.length <= 20_000,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            Text(
                stringResource(R.string.skills_edit_hint),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
            DsTextField(
                value = editedSkillBody,
                onValueChange = { editedSkillBody = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 10,
                label = { Text(stringResource(R.string.skills_instruction_label)) },
            )
        }
    }

}
