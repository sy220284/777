package com.labteto.dshmobile.ui.screens.tools

import com.shenyan.designsystem.foundation.SyMetric



import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot as McpServerSnapshot
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.shenyan.designsystem.components.SySwitchControl
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

private data class InventorySection(
    val id: String,
    val title: String,
    val entries: List<InventoryRow>,
)

private data class InventoryRow(
    val id: String,
    val name: String,
    val detail: String,
    val connectedService: Boolean,
    val icon: ImageVector = FeatherIcons.Globe,
    val accent: Color = Color(0xFF62758A),
    val documentIcon: Int? = null,
    val available: Boolean = true,
    val modelInvocable: Boolean = true,
    val installedSkill: Boolean = false,
    val installablePreset: Boolean = false,
    val sourceUrl: String? = null,
    val sourceWarning: String? = null,
    val rollbackAvailable: Boolean = false,
    val sourceLabel: String = "",
    val marketState: ExtensionMarketState = ExtensionMarketState(ExtensionMarketStatus.BUILTIN, "内置能力 · 按需授权"),
    val requirements: List<com.labteto.dshmobile.uicontract.UiSkillRequirement> = emptyList(),
)

/** Browses real installed/connected plugin inventory; does not advertise unsupported storefront apps. */
@Composable
internal fun PluginInventoryBrowser(
    localIds: List<String>,
    connectedServices: List<McpServerSnapshot> = emptyList(),
    skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    presets: List<com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry> = emptyList(),
    pendingSkillUpdates: Map<String, String> = emptyMap(),
    upToDateSkills: Set<String> = emptySet(),
    createdSkillId: String? = null,
    skillEditor: com.labteto.dshmobile.local.presentation.LocalSkillEditorUiEntry? = null,
    savedSkillId: String? = null,
    embedded: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    onOpenSettings: () -> Unit = {},
    githubConfigured: Boolean = false,
    onGitHub: () -> Unit = {},
    onAddMcp: () -> Unit = {},
    onReturnToChat: () -> Unit,
    onUseCapability: ((String) -> Unit)? = null,
    onInstallPreset: ((String) -> Unit)? = null,
    onCreateSkill: ((String, String, String, String) -> Unit)? = null,
    onImportSkill: (() -> Unit)? = null,
    onInstallGitHubSkill: ((String) -> Unit)? = null,
    onConnectSkillMcp: ((String, String) -> Unit)? = null,
    onRemoveSkill: ((String) -> Unit)? = null,
    onShareSkillPackage: ((String) -> Unit)? = null,
    onCheckSkillUpdate: ((String) -> Unit)? = null,
    onUpgradeSkill: ((String, String) -> Unit)? = null,
    onRollbackSkill: ((String) -> Unit)? = null,
    onOpenSkillEditor: ((String) -> Unit)? = null,
    onCloseSkillEditor: (() -> Unit)? = null,
    onSaveSkillDocument: ((String, String) -> Unit)? = null,
    onSetSkillModelInvocable: ((String, Boolean) -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val context = LocalContext.current
    val skillCreationPrompt = stringResource(R.string.skills_create_in_chat_prompt)
    var showMarketActions by remember { mutableStateOf(false) }
    var addedOnly by rememberSaveable { mutableStateOf(true) }
    var catalogueQuery by rememberSaveable { mutableStateOf("") }
    var showOwnedInDiscover by rememberSaveable { mutableStateOf(false) }
    var selectedRow by remember { mutableStateOf<InventoryRow?>(null) }
    var showCreateSkill by remember { mutableStateOf(false) }
    var removeSkillName by remember { mutableStateOf<String?>(null) }
    var sharePackageSkillName by remember { mutableStateOf<String?>(null) }
    var upgradeSkillChoice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rollbackSkillChoice by remember { mutableStateOf<String?>(null) }
    var newSkillId by remember { mutableStateOf("") }
    var showGitHubImport by remember { mutableStateOf(false) }
    var githubImportUrl by remember { mutableStateOf("") }
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
    // The built-in skill runtime is represented by individual installed skills.
    // GitHub's built-in service is managed from the single canonical connector card.
    val local = localIds.filterNot { it == "android-local-builtins" || it == "github-connector" }
        .distinct().sorted().map { id ->
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
        val copy = extensionPresentation(id, label, stringResource(hint), ExtensionKind.PLUGIN)
        InventoryRow("local:$id", copy.title, copy.description, connectedService = false,
            icon = copy.icon, accent = copy.accent, documentIcon = copy.documentIcon,
            sourceLabel = "777 · 随应用安装",
            marketState = ExtensionMarketState(
                ExtensionMarketStatus.BUILTIN, "已内置", "调用时核验实际授权",
            ))
    }
    val connected = connectedServices.sortedBy { it.id }.map { server ->
        val copy = extensionPresentation(server.id, server.id, "", ExtensionKind.CONNECTOR)
        InventoryRow(
            id = "service:" + server.id,
            name = if (server.id.equals("github", ignoreCase = true)) "GitHub · MCP" else copy.title,
            detail = "已连接 · " + server.tools.size + " 项工具 · " + server.transport.uppercase(),
            connectedService = true,
            available = false,
            sourceLabel = "MCP · ${server.transport.uppercase()}",
            marketState = connectorMarketState(server.registration, server.connection),
            icon = copy.icon,
            accent = copy.accent,
        )
    }
    val installedSkills = skills.map { skill ->
        val copy = extensionPresentation(skill.name, skill.displayName, skill.description, ExtensionKind.SKILL)
        InventoryRow("skill:" + skill.name, copy.title, copy.description,
            connectedService = false, icon = copy.icon, accent = copy.accent,
            documentIcon = copy.documentIcon,
            available = true, modelInvocable = skill.modelInvocable, installedSkill = true,
            sourceUrl = skill.sourceUrl,
            sourceWarning = skill.sourceWarning,
            rollbackAvailable = skill.rollbackAvailable,
            requirements = skill.requirements,
            sourceLabel = if (skill.sourceUrl != null) "GitHub · 已固定来源" else "777 · 本地技能",
            marketState = installedSkillMarketState(skill.modelInvocable, skill.sourceWarning, skill.requirements))
    }
    val installedSkillIds = skills.mapTo(hashSetOf()) { it.name }
    val availablePresets = presets.filterNot { it.installed || it.id in installedSkillIds }.map { preset ->
        val copy = extensionPresentation(preset.id, preset.title, preset.description, ExtensionKind.SKILL)
        InventoryRow("skill:" + preset.id, copy.title, copy.description,
            connectedService = false, icon = copy.icon, accent = copy.accent,
            documentIcon = copy.documentIcon,
            available = false, installablePreset = true,
            sourceLabel = "777 · 内置精选",
            marketState = ExtensionMarketState(ExtensionMarketStatus.INSTALLABLE, "待安装"))
    }
    val githubVisual = extensionPresentation("github", "GitHub", "", ExtensionKind.CONNECTOR)
    val github = InventoryRow(
        id = "connector:github",
        name = "GitHub",
        detail = if (githubConfigured) "代码仓库与开发协作 · 已配置" else "连接代码仓库，浏览与管理项目",
        connectedService = githubConfigured,
        available = false,
        icon = githubVisual.icon,
        accent = githubVisual.accent,
        documentIcon = githubVisual.documentIcon,
        sourceLabel = "777 · GitHub 连接器",
        marketState = if (githubConfigured)
            ExtensionMarketState(ExtensionMarketStatus.INSTALLED, "已配置")
        else ExtensionMarketState(ExtensionMarketStatus.NEEDS_SETUP, "待连接"),
    )
    val addMcpVisual = extensionPresentation("new-mcp", "添加 MCP 连接器", "", ExtensionKind.CONNECTOR)
    val addMcp = InventoryRow(
        id = "connector:add-mcp",
        name = "添加 MCP 连接器",
        detail = "连接新的外部工具服务",
        connectedService = false,
        available = false,
        icon = FeatherIcons.Plus,
        accent = addMcpVisual.accent,
        sourceLabel = "777 · MCP",
        marketState = ExtensionMarketState(ExtensionMarketStatus.INSTALLABLE, "可添加"),
    )
    val catalogue = local + connected + installedSkills + availablePresets + listOf(github, addMcp)
    val isAdded: (InventoryRow) -> Boolean = { item ->
        marketplaceTabAllows(
            addedOnly = true,
            installedSkill = item.installedSkill,
            connectedService = item.connectedService,
            localPlugin = item.id.startsWith("local:"),
            configuredConnector = item.id == "connector:github" && githubConfigured,
        )
    }
    val query = catalogueQuery.trim()
    val originalSkillText = buildMap {
        skills.forEach { put("skill:${it.name}", listOf(it.displayName.orEmpty(), it.description)) }
        presets.filterNot { it.id in installedSkillIds }.forEach {
            put("skill:${it.id}", listOf(it.title, it.description))
        }
    }
    val matchesSearch: (InventoryRow) -> Boolean = { item ->
        (listOf(item.id, item.name, item.detail, item.sourceLabel) + originalSkillText[item.id].orEmpty())
            .any { it.contains(query, ignoreCase = true) }
    }
    val owned = catalogue.filter(isAdded).filter(matchesSearch).sortedBy { it.name }
    val discoverable = catalogue.filterNot(isAdded).filter(matchesSearch).sortedBy { it.name }
    val visible = if (addedOnly) owned else discoverable
    val isConnector: (InventoryRow) -> Boolean = {
        it.id.startsWith("service:") || it.id.startsWith("connector:")
    }
    val sections = if (addedOnly) {
        listOf(
            InventorySection("owned-extensions", "已安装的技能与插件", visible.filterNot(isConnector)),
            InventorySection("owned-connectors", "连接器", visible.filter(isConnector)),
        )
    } else {
        listOf(
            InventorySection("discover-extensions", "可添加的技能", visible.filterNot(isConnector)),
            InventorySection("discover-connectors", "可连接的服务", visible.filter(isConnector)),
        ) + if (showOwnedInDiscover) listOf(
            InventorySection("discover-owned-extensions", "已添加的技能与插件", owned.filterNot(isConnector)),
            InventorySection("discover-owned-connectors", "已连接或内置的服务", owned.filter(isConnector)),
        ) else emptyList()
    }

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!embedded) SyTopBar(
                title = stringResource(R.string.tools_catalog_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapSection),
                verticalAlignment = Alignment.CenterVertically) {
                SySegmented(
                    segments = listOf(SySegment("added", stringResource(R.string.extension_added)),
                        SySegment("discover", stringResource(R.string.extension_discover))),
                    selectedKey = if (addedOnly) "added" else "discover",
                    onSelect = { addedOnly = it == "added" },
                    modifier = Modifier.weight(1f).testTag("plugin-ownership-tabs"), stretch = true,
                )
                IconButton(onClick = { showMarketActions = true },
                    modifier = Modifier.testTag("plugin-inventory-more")) {
                    Icon(FeatherIcons.MoreVertical, contentDescription = "更多操作", tint = colors.text)
                }
            }
            Surface(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapXs)
                    .testTag("capability-settings-entry"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = colors.materialL2,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(FeatherIcons.Sliders, contentDescription = null,
                        tint = colors.textSecondary, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("能力设置", style = DsType.std14Strong.withReadingWeight(),
                            color = colors.text)
                        Text("网络搜索、执行授权与高级配置",
                            style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
                    }
                    Icon(FeatherIcons.ChevronRight, contentDescription = null,
                        tint = colors.textTertiary, modifier = Modifier.size(17.dp))
                }
            }
            SyTextField(
                value = catalogueQuery,
                onValueChange = { catalogueQuery = it.take(128) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapSection)
                    .testTag("plugin-inventory-search"),
                placeholder = { Text(stringResource(R.string.tools_catalog_search)) },
                singleLine = true,
            )
            if (loading && visible.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.common_loading),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textTertiary,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapSm),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .testTag("capability-inventory-list"),
                contentPadding = PaddingValues(
                    horizontal = SyMetric.gapSection,
                    vertical = SyMetric.gapMd,
                ),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
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
                if (sections.none { it.entries.isNotEmpty() } && error == null && !loading) {
                    item(key = "inventory-empty") {
                        Text(
                            when {
                                query.isNotEmpty() && !addedOnly && owned.isNotEmpty() &&
                                    !showOwnedInDiscover -> "可添加项目中没有匹配项；可展开下方已添加项目"
                                query.isNotEmpty() -> stringResource(R.string.tools_catalog_no_match)
                                addedOnly -> "尚未添加技能、插件或连接器"
                                else -> "暂无可添加的能力；可查看已添加的项目"
                            },
                            style = DsType.std14.withReadingWeight(),
                            color = colors.textSecondary,
                            modifier = Modifier.fillMaxWidth().padding(SyMetric.gapPage),
                        )
                    }
                }
                sections.filter { it.entries.isNotEmpty() }.forEach { section ->
                    item(key = "heading:" + section.id) {
                        Text(section.title, style = DsType.small13Strong.withReadingWeight(),
                            color = colors.textSecondary,
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 6.dp, top = SyMetric.gapSm, bottom = SyMetric.gapXs))
                    }
                    items(section.entries, key = { it.id }) { item ->
                        ExtensionCatalogCard(
                            id = item.id,
                            title = item.name,
                            description = item.detail,
                            icon = item.icon,
                            accent = item.accent,
                            documentIcon = item.documentIcon,
                            marketState = item.marketState,
                            onOpen = {
                                when (item.id) {
                                    "connector:github" -> onGitHub()
                                    "connector:add-mcp" -> onAddMcp()
                                    else -> selectedRow = item
                                }
                            },
                        )
                    }
                }
                if (!addedOnly && owned.isNotEmpty()) {
                    item(key = "discover-owned-toggle") {
                        SyButton(
                            text = if (showOwnedInDiscover) "收起已添加（${owned.size}）"
                                   else "查看已添加（${owned.size}）",
                            variant = SyButtonVariant.Ghost,
                            onClick = { showOwnedInDiscover = !showOwnedInDiscover },
                            modifier = Modifier.fillMaxWidth().testTag("discover-owned-toggle"),
                        )
                    }
                }
            }
        }
    }
    selectedRow?.let { selected ->
        // Reproject from the authoritative inventory after installs or connection changes.
        val item = catalogue.firstOrNull { it.id == selected.id } ?: selected
        var requirementsExpanded by remember(item.id) { mutableStateOf(false) }
        ExtensionDetailPage(
            title = item.name,
            onDismiss = { selectedRow = null },
            footer = {
                val knownUnreadySkillServer = item.requirements.any { requirement ->
                    !requirement.registered && requirement.type.equals("mcp", ignoreCase = true) &&
                        connectedServices.any { it.id == requirement.value }
                }
                val endpoint = item.requirements.firstNotNullOfOrNull { requirement ->
                    if (requirement.registered || (requirement.type.equals("mcp", ignoreCase = true) &&
                            connectedServices.any { it.id == requirement.value })) null
                    else skillMcpHttpsEndpoint(requirement)?.let { requirement.value to it }
                }
                val usePrompt = if (item.installedSkill)
                    "@skill:${item.id.substringAfter(":")}\n"
                else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                val canInstallPreset = item.installablePreset && onInstallPreset != null
                val canConnectSkill = endpoint != null && onConnectSkillMcp != null
                val canInspectSkill = knownUnreadySkillServer && item.installedSkill
                val needsOtherSkillSetup = item.installedSkill &&
                    item.requirements.any { !it.registered } && !canConnectSkill && !canInspectSkill
                SyButton(
                    text = when {
                        loading -> "正在安装或授权…"
                        canInstallPreset -> "安装"
                        canConnectSkill -> "连接并授权"
                        canInspectSkill -> "检查连接"
                        needsOtherSkillSetup -> "查看待处理依赖"
                        item.installedSkill && item.requirements.all { it.registered } -> "使用"
                        item.connectedService -> "管理连接"
                        onUseCapability != null && item.available -> "使用"
                        else -> "返回对话"
                    },
                    modifier = Modifier.fillMaxWidth().testTag("plugin-detail-primary-action"),
                    enabled = !loading,
                    size = SyButtonSize.Large,
                    onClick = {
                        when {
                            canInstallPreset -> onInstallPreset?.invoke(item.id.substringAfter(":"))
                            canInspectSkill -> { selectedRow = null; onManageConnections() }
                            needsOtherSkillSetup -> requirementsExpanded = true
                            canConnectSkill -> requireNotNull(endpoint).let { (serverId, url) ->
                                onConnectSkillMcp?.invoke(serverId, url)
                            }
                            item.connectedService -> { selectedRow = null; onManageConnections() }
                            onUseCapability != null && item.available -> {
                                selectedRow = null
                                onUseCapability(usePrompt)
                            }
                            else -> { selectedRow = null; onReturnToChat() }
                        }
                    },
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Surface(
                    modifier = Modifier.size(84.dp),
                    color = item.accent.copy(alpha = 0.10f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            item.documentIcon != null -> androidx.compose.foundation.Image(
                                painter = painterResource(item.documentIcon),
                                contentDescription = null, modifier = Modifier.size(64.dp),
                            )
                            else -> Icon(item.icon, contentDescription = null, tint = item.accent,
                                modifier = Modifier.size(44.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.detail, style = DsType.std14.withReadingWeight(),
                        color = colors.textSecondary)
                }
            }
            if (item.sourceLabel.isNotBlank()) {
                Text(item.sourceLabel, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
            }
            Text(item.marketState.label, style = DsType.small13Strong.withReadingWeight(),
                color = if (item.marketState.status in setOf(ExtensionMarketStatus.NEEDS_SETUP,
                    ExtensionMarketStatus.FAILED, ExtensionMarketStatus.UNSUPPORTED))
                    colors.warningInk else colors.successInk)
            if (item.marketState.advice.isNotBlank() && item.requirements.isEmpty()) {
                Text(item.marketState.advice, style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary)
            }
            if (item.requirements.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("skill-dependency-summary"),
                    color = colors.materialL4,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    onClick = { requirementsExpanded = !requirementsExpanded },
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("运行依赖 · ${item.requirements.size} 项",
                            style = DsType.std14Strong, color = colors.text)
                        Icon(FeatherIcons.ChevronDown, contentDescription = "展开运行依赖",
                            tint = colors.textSecondary)
                    }
                }
                if (requirementsExpanded) {
                    item.requirements.forEach { requirement ->
                        Text(
                            "${if (requirement.registered) "已就绪" else if (requirement.type.equals("mcp", true)) "待连接或检查" else "待配置"} · ${requirement.description.ifBlank { requirement.value }}",
                            style = DsType.small13.withReadingWeight(),
                            color = if (requirement.registered) colors.successInk else colors.warningInk,
                        )
                        val endpoint = if (!requirement.registered)
                            skillMcpHttpsEndpoint(requirement) else null
                        if (endpoint != null) {
                            val current = com.labteto.dshmobile.uicontract.uiMcpDeclaredConnection(
                                connectedServices, requirement.value, endpoint)
                            when (current) {
                                com.labteto.dshmobile.uicontract.UiMcpDeclaredConnection.ABSENT -> {
                                    if (onConnectSkillMcp != null)
                                        SyButton(
                                            text = "连接并授权",
                                            enabled = !loading,
                                            variant = SyButtonVariant.Info,
                                            onClick = {
                                                selectedRow = null
                                                onConnectSkillMcp(requirement.value, endpoint)
                                            },
                                        )
                                }
                                else -> SyButton(
                                    text = "检查连接",
                                    enabled = !loading,
                                    variant = SyButtonVariant.Info,
                                    onClick = { selectedRow = null; onManageConnections() },
                                )
                            }
                        }
                    }
                    if (item.requirements.any { !it.registered && skillMcpHttpsEndpoint(it) == null }) {
                        Text("部分依赖没有公开连接地址，可在连接器中查看本机可用的服务。",
                            style = DsType.small13, color = colors.textSecondary)
                        SyButton(text = "查看连接器", variant = SyButtonVariant.Ghost,
                            onClick = { selectedRow = null; onManageConnections() })
                    }
                }
            }
            if (item.installedSkill && !item.modelInvocable) {
                Text(stringResource(R.string.skills_manual_only), style = DsType.small13.withReadingWeight(), color = colors.textTertiary)
            }
            item.sourceWarning?.let { warning ->
                Text(
                    warning,
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary,
                    modifier = Modifier.testTag("skill-source-warning"),
                )
            }
            if (item.installedSkill && item.sourceUrl != null) {
                Text(
                    "已固定 GitHub 来源版本：${item.sourceUrl.substringAfter("/tree/").substringBefore('/').take(12)}",
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary,
                )
                if (onCheckSkillUpdate != null) {
                    SyButton(
                        text = "检查更新",
                        variant = SyButtonVariant.Info,
                        enabled = !loading,
                        modifier = Modifier.testTag("skill-check-update"),
                        onClick = { onCheckSkillUpdate(item.id.substringAfter(":")) },
                    )
                    val skillId = item.id.substringAfter(":")
                    val revision = pendingSkillUpdates[skillId]
                    if (revision != null && onUpgradeSkill != null) {
                        Text(
                            "发现新版本：${revision.take(12)}",
                            style = DsType.small13.withReadingWeight(),
                            color = colors.textSecondary,
                            modifier = Modifier.testTag("skill-update-available"),
                        )
                        SyButton(
                            text = "升级到此版本",
                            variant = SyButtonVariant.Info,
                            enabled = !loading,
                            modifier = Modifier.testTag("skill-upgrade"),
                            onClick = {
                                upgradeSkillChoice = skillId to revision
                                selectedRow = null
                            },
                        )
                    } else if (skillId in upToDateSkills) {
                        Text(
                            "当前已是最新版本",
                            style = DsType.small13.withReadingWeight(),
                            color = colors.textSecondary,
                            modifier = Modifier.testTag("skill-update-current"),
                        )
                    }
                }
                SyButton(
                    text = "分享原始来源",
                    variant = SyButtonVariant.Ghost,
                    enabled = !loading,
                    modifier = Modifier.testTag("skill-share-source"),
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, item.sourceUrl)
                        }
                        context.startActivity(Intent.createChooser(share, "分享技能来源"))
                    },
                )
            }

            if (item.installedSkill && item.rollbackAvailable && onRollbackSkill != null) {
                SyButton(
                    text = "恢复上一版本",
                    variant = SyButtonVariant.Ghost,
                    enabled = !loading,
                    modifier = Modifier.testTag("skill-rollback"),
                    onClick = {
                        rollbackSkillChoice = item.id.substringAfter(":")
                        selectedRow = null
                    },
                )
            }
            if (item.installedSkill && onShareSkillPackage != null) {
                SyButton(
                    text = "分享完整技能包",
                    variant = SyButtonVariant.Info,
                    enabled = !loading,
                    modifier = Modifier.testTag("skill-share-package"),
                    onClick = {
                        sharePackageSkillName = item.id.substringAfter(":")
                        selectedRow = null
                    },
                )
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
    if (showMarketActions) {
        SyResponsiveSheet(title = "能力管理", onDismiss = { showMarketActions = false }) {
            if (onImportSkill != null) SyButton(
                text = "导入技能文件",
                onClick = { showMarketActions = false; onImportSkill() },
                modifier = Modifier.fillMaxWidth().testTag("skill-import-file"),
            )
            if (onUseCapability != null) SyButton(
                text = "对话创建技能", variant = SyButtonVariant.Ghost,
                onClick = { showMarketActions = false; onUseCapability(skillCreationPrompt) },
                modifier = Modifier.fillMaxWidth().testTag("skill-create-in-chat"),
            )
            if (onInstallGitHubSkill != null) SyButton(
                text = "从 GitHub 安装技能", variant = SyButtonVariant.Ghost,
                onClick = { showMarketActions = false; showGitHubImport = true },
                modifier = Modifier.fillMaxWidth().testTag("skill-install-github"),
            )
            if (onCreateSkill != null) SyButton(
                text = "手动创建技能", variant = SyButtonVariant.Ghost,
                onClick = { showMarketActions = false; showCreateSkill = true },
                modifier = Modifier.fillMaxWidth().testTag("skill-create-manual"),
            )
            SyButton(text = "添加 MCP 连接器", variant = SyButtonVariant.Ghost,
                onClick = { showMarketActions = false; onAddMcp() },
                modifier = Modifier.fillMaxWidth().testTag("capability-menu-add-mcp"))
        }
    }
    if (showGitHubImport && onInstallGitHubSkill != null) {
        SyResponsiveSheet(
            title = "从 GitHub 安装技能",
            subtitle = "选择包含 SKILL.md 的公开仓库目录；安装后检查工具和授权",
            onDismiss = { showGitHubImport = false },
            footer = {
                SyButton(
                    text = "确认安装",
                    enabled = !loading && githubImportUrl.trim().let {
                        it.startsWith("https://github.com/", ignoreCase = true) ||
                            it.startsWith("github.com/", ignoreCase = true)
                    },
                    onClick = {
                        val source = githubImportUrl.trim()
                        showGitHubImport = false
                        onInstallGitHubSkill(source)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("skill-install-github-confirm"),
                )
            },
        ) {
            SyTextField(
                value = githubImportUrl,
                onValueChange = { githubImportUrl = it.take(2048) },
                label = { Text("GitHub 技能目录链接") },
                supportingText = { Text("例如 github.com/owner/repo/tree/main/skills/skill-name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showCreateSkill && onCreateSkill != null) {
        val validId = newSkillId.matches(Regex("[a-z][a-z0-9-]{1,47}"))
        val validName = newSkillDisplayName.trim().length in 1..40 &&
            newSkillDisplayName.any { it in '\u4e00'..'\u9fff' }
        val canCreate = validId && validName && newSkillDescription.isNotBlank() &&
            newSkillDescription.length <= 240 &&
            newSkillBody.isNotBlank()
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
    upgradeSkillChoice?.let { (id, revision) ->
        SyResponsiveSheet(
            title = "确认升级技能",
            onDismiss = { upgradeSkillChoice = null },
            footer = {
                SyButton(
                    text = "确认升级",
                    enabled = !loading && pendingSkillUpdates[id] == revision,
                    modifier = Modifier.fillMaxWidth().testTag("skill-upgrade-confirm"),
                    onClick = {
                        onUpgradeSkill?.invoke(id, revision)
                        upgradeSkillChoice = null
                    },
                )
            },
        ) {
            Text(
                "将升级 $id 到版本 ${revision.take(12)}。安装前会验证新包，并保存当前完整技能作为上一版本；如果修改过本地规则，可通过“恢复上一版本”找回。",
                style = DsType.std14.withReadingWeight(), color = colors.textSecondary,
            )
        }
    }
    rollbackSkillChoice?.let { id ->
        SyResponsiveSheet(
            title = "恢复上一版本",
            onDismiss = { rollbackSkillChoice = null },
            footer = {
                SyButton(
                    text = "确认恢复",
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().testTag("skill-rollback-confirm"),
                    onClick = {
                        onRollbackSkill?.invoke(id)
                        rollbackSkillChoice = null
                    },
                )
            },
        ) {
            Text(
                "将 $id 恢复为上次升级前的完整技能内容。当前版本会保留，可再次恢复回来。",
                style = DsType.std14.withReadingWeight(), color = colors.textSecondary,
            )
        }
    }
    sharePackageSkillName?.let { id ->
        SyResponsiveSheet(
            title = "分享完整技能包",
            onDismiss = { sharePackageSkillName = null },
            footer = {
                SyButton(
                    text = "生成并分享",
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().testTag("skill-share-package-confirm"),
                    onClick = {
                        onShareSkillPackage?.invoke(id)
                        sharePackageSkillName = null
                    },
                )
            },
        ) {
            Text(
                "将导出 SKILL.md 及技能目录下的全部附属资料，请先确认其中没有需要保密的内容。",
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
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
                    enabled = !loading && editedSkillBody.isNotBlank() &&
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
