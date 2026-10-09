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

private const val LOCAL_SKILL_CREATION_PROMPT = """请帮我创建一个可在本应用重复使用的自定义技能。先问清楚用途、触发时机、输入输出格式和关键限制，给出规则草案让我确认。得到我的确认后，在当前本地工作区使用文件工具写入 .dsh/skills/英文技能标识/SKILL.md。文件必须有以 --- 分隔的元数据，其中 name 为2至48位小写英文、数字或连字符（以字母开头），description 为技能用途，display-name 为中文名称；后面填写完整执行规则。先检查是否有同名技能，禁止覆盖已有文件。写入成功后重新读取文件进行校验，并提醒我回到「工具→技能」列表刷新查看。未实际写入成功时请直接说明，不得宣称已安装。现在请先询问我想创建什么技能。"""

private data class InventoryRow(
    val id: String,
    val name: String,
    val detail: String,
    val remote: Boolean,
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
    onCreateSkill: ((String, String, String, String) -> Unit)? = null,
    onImportSkill: (() -> Unit)? = null,
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
        val display = skill.displayName
        InventoryRow("skill:" + skill.name, display, skill.description,
            remote = false, icon = FeatherIcons.BookOpen,
            available = true, modelInvocable = skill.modelInvocable, installedSkill = true)
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
            if (skillsOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    if (onImportSkill != null) {
                        DsButton(
                            text = stringResource(R.string.skills_import_file),
                            onClick = onImportSkill,
                            enabled = !loading,
                            modifier = Modifier.weight(1f).testTag("skill-import-file"),
                        )
                    }
                    if (onUseCapability != null) {
                        DsButton(
                            text = stringResource(R.string.skills_create_in_chat),
                            onClick = { onUseCapability(LOCAL_SKILL_CREATION_PROMPT) },
                            enabled = !loading,
                            variant = DsButtonVariant.Info,
                            modifier = Modifier.weight(1f).testTag("skill-create-in-chat"),
                        )
                    }
                }
                Text(
                    stringResource(R.string.skills_import_hint),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(horizontal = DsSpacing.comfortable),
                )
                if (onCreateSkill != null) {
                    DsButton(
                        text = stringResource(R.string.skills_create_manual),
                        variant = DsButtonVariant.Info,
                        onClick = { showCreateSkill = true },
                        enabled = !loading,
                        modifier = Modifier.padding(horizontal = DsSpacing.comfortable),
                    )
                }
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
                                val usePrompt = if (item.installedSkill) "@skill:${item.id.substringAfter(":")}\n" else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
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
                val usePrompt = if (item.installedSkill) "@skill:${item.id.substringAfter(":")}\n" else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
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
            if (item.installedSkill && !item.modelInvocable) {
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
                        checked = item.modelInvocable,
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
        val validName = newSkillDisplayName.trim().length in 1..40 &&
            newSkillDisplayName.any { it in '\u4e00'..'\u9fff' }
        val canCreate = validId && validName && newSkillDescription.isNotBlank() &&
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
                        onCreateSkill(newSkillId, newSkillDisplayName.trim(), newSkillDescription, newSkillBody)
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
                value = newSkillDisplayName,
                onValueChange = { newSkillDisplayName = it },
                label = { Text(stringResource(R.string.skills_display_name_label)) },
                supportingText = { Text(stringResource(R.string.skills_display_name_hint)) },
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
                color = colors.labelSecondary,
            )
            DsTextField(
                value = editedSkillDisplayName,
                onValueChange = { editedSkillDisplayName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.skills_display_name_label)) },
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
