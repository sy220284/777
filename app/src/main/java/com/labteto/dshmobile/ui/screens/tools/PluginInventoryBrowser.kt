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
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.shenyan.SySearchField
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

/**
 * Load a small publisher-provided raster safely. A bad/oversized bitmap cannot
 * displace the built-in category glyph.
 */
private fun decodeCodexBrandLogo(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

private data class InventoryRow(
    val id: String,
    val name: String,
    val detail: String,
    val connectedService: Boolean,
    val icon: ImageVector = FeatherIcons.Globe,
    val accent: Color = Color(0xFF62758A),
    val group: ExtensionGroup = ExtensionGroup.GENERAL,
    val documentIcon: Int? = null,
    val available: Boolean = true,
    val modelInvocable: Boolean = true,
    val installedSkill: Boolean = false,
    val installedPlugin: Boolean = false,
    val installablePreset: Boolean = false,
    val installableCurated: Boolean = false,
    val sourceUrl: String? = null,
    val sourceIdentity: com.labteto.dshmobile.uicontract.UiSkillSourceIdentity? = null,
    val sourceWarning: String? = null,
    val rollbackAvailable: Boolean = false,
    val sourceLabel: String = "",
    val marketState: ExtensionMarketState = ExtensionMarketState(ExtensionMarketStatus.BUILTIN, "内置能力 · 按需授权"),
    val requirements: List<com.labteto.dshmobile.uicontract.UiSkillRequirement> = emptyList(),
)

/** Match visible skill names first, then identifiers, then explanatory descriptions. */
internal fun inventorySearchScore(query: String, name: String, id: String, detail: String): Int? {
    val term = query.trim().lowercase()
    if (term.isEmpty()) return 0
    val title = name.lowercase()
    val key = id.lowercase()
    val body = detail.lowercase()
    return when {
        title == term || key == term -> 600
        title.startsWith(term) -> 500
        key.startsWith(term) -> 450
        title.contains(term) -> 350
        key.contains(term) -> 300
        body.contains(term) -> 120
        else -> null
    }
}

/** Browses real installed/connected plugin inventory; does not advertise unsupported storefront apps. */
@Composable
internal fun PluginInventoryBrowser(
    localIds: List<String>,
    activeToolHookIds: List<String> = emptyList(),
    connectedServices: List<McpServerSnapshot> = emptyList(),
    skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    presets: List<com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry> = emptyList(),
    curatedSkills: List<String> = emptyList(),
    codexPlugins: List<com.labteto.dshmobile.uicontract.UiCodexPluginSnapshot> = emptyList(),
    codexLoading: Boolean = false,
    codexError: String? = null,
    onRefreshCodex: (() -> Unit)? = null,
    codexDetails: Map<String, com.labteto.dshmobile.uicontract.UiCodexPluginDetailSnapshot> = emptyMap(),
    codexLogos: Map<String, ByteArray> = emptyMap(),
    onRequestCodexLogo: ((String) -> Unit)? = null,
    codexDetailLoadingId: String? = null,
    codexDetailError: String? = null,
    onReadCodexPluginDetail: ((String) -> Unit)? = null,
    onInstallCodexPluginSkill: ((String, String) -> Unit)? = null,
    onInstallCodexPlugin: ((String) -> Unit)? = null,
    onConnectCodexPlugin: ((String) -> Unit)? = null,
    curatedLoading: Boolean = false,
    curatedError: String? = null,
    pendingSkillUpdates: Map<String, String> = emptyMap(),
    upToDateSkills: Set<String> = emptySet(),
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
    onInstallCurated: ((String) -> Unit)? = null,
    onRefreshCurated: (() -> Unit)? = null,
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
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showMarketActions by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var transportFilter by rememberSaveable { mutableStateOf(PluginTransportFilter.ALL) }
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
        val copy = extensionPresentation(id, label, stringResource(hint), ExtensionKind.PLUGIN)
        InventoryRow("local:$id", copy.title, copy.description, connectedService = false,
            icon = copy.icon, accent = copy.accent, group = copy.group, documentIcon = copy.documentIcon,
            sourceLabel = "777 · 随应用安装",
            marketState = ExtensionMarketState(ExtensionMarketStatus.BUILTIN, "已内置 · 按需授权"))
    }
    val connected = connectedServices.sortedBy { it.id }.map { server ->
        val copy = extensionPresentation(server.id, server.id, "", ExtensionKind.CONNECTOR)
        InventoryRow(
            id = "service:" + server.id,
            name = copy.title,
            detail = "已连接 · " + server.tools.size + " 项工具 · " + server.transport.uppercase(),
            connectedService = true,
            available = false,
            sourceLabel = "MCP · ${server.transport.uppercase()}",
            marketState = connectorMarketState(server.registration, server.connection),
            icon = copy.icon,
            accent = copy.accent,
            group = copy.group,
        )
    }
    val filteredConnections = connected.filter { row ->
        val server = connectedServices.firstOrNull { "service:${it.id}" == row.id }
        server != null && pluginTransportMatches(server.transport.toString(), transportFilter)
    }
    val installedSkills = skills.map { skill ->
        val copy = extensionPresentation(skill.name, skill.displayName, skill.description, ExtensionKind.SKILL)
        InventoryRow("skill:" + skill.name, copy.title, copy.description,
            connectedService = false, icon = copy.icon, accent = copy.accent,
            group = copy.group, documentIcon = copy.documentIcon,
            available = true, modelInvocable = skill.modelInvocable, installedSkill = true,
            sourceUrl = skill.sourceUrl, sourceIdentity = skill.sourceIdentity,
            sourceWarning = skill.sourceWarning,
            rollbackAvailable = skill.rollbackAvailable,
            requirements = skill.requirements,
            sourceLabel = if (skill.sourceUrl != null) "GitHub · 已固定来源" else "777 · 本地技能",
            marketState = installedSkillMarketState(skill.modelInvocable, skill.sourceWarning, skill.requirements))
    }
    val availablePresets = presets.filterNot { it.installed }.map { preset ->
        val copy = extensionPresentation(preset.id, preset.title, preset.description, ExtensionKind.SKILL)
        InventoryRow("skill:" + preset.id, copy.title, copy.description,
            connectedService = false, icon = copy.icon, accent = copy.accent,
            group = copy.group, documentIcon = copy.documentIcon,
            available = false, installablePreset = true,
            sourceLabel = "777 · 内置精选",
            marketState = ExtensionMarketState(ExtensionMarketStatus.INSTALLABLE, "待安装"))
    }
    val installedIds = skills.map { it.name }.toSet()
    val presetIds = presets.map { it.id }.toSet()
    val curated = curatedSkills.filterNot { it in installedIds || it in presetIds }.map { id ->
        val copy = extensionPresentation(id, id, "", ExtensionKind.SKILL)
        InventoryRow(
            id = "curated-skill:" + id, name = copy.title,
            detail = "官方精选技能 · 安装前校验完整内容",
            connectedService = false, icon = copy.icon, accent = copy.accent,
            group = copy.group, documentIcon = copy.documentIcon,
            available = false, installableCurated = true,
            sourceLabel = "OpenAI/skills · 官方精选",
            marketState = ExtensionMarketState(ExtensionMarketStatus.INSTALLABLE, "可安装 · 依赖待检查"),
        )
    }
    // Codex separates discovery from local installation and runtime authorization.
    val officialPlugins = if (skillsOnly) emptyList() else codexPlugins.map { plugin ->
        val copy = extensionPresentation(plugin.name, plugin.name, "", ExtensionKind.PLUGIN)
        val group = when (plugin.category.lowercase()) {
            "creativity" -> ExtensionGroup.CREATIVE
            "developer tools", "education & research" -> ExtensionGroup.DEVELOPMENT
            "productivity", "communication", "finance" -> ExtensionGroup.OFFICE
            else -> ExtensionGroup.GENERAL
        }
        val matchingService = connectedServices.firstOrNull {
            it.id == plugin.name || it.id == codexDetails[plugin.id]?.mcpServerId
        }
        val installedFromPlugin = skills.any {
            codexOfficialPluginSourceMatches(it.sourceIdentity, plugin.sourcePath)
        }
        val verifiedDetail = codexDetails[plugin.id]
        val support = when {
            plugin.codexOnly -> ExtensionMarketState(ExtensionMarketStatus.UNSUPPORTED,
                "Codex 专属", "该插件要求 Codex 产品环境；777 当前无法直接启用")
            plugin.installPolicy == "NOT_AVAILABLE" -> ExtensionMarketState(
                ExtensionMarketStatus.UNSUPPORTED, "官方暂不可安装")
            plugin.sourceKind != "local" -> ExtensionMarketState(
                ExtensionMarketStatus.UNSUPPORTED, "第三方 Git 来源",
                "Codex 使用 Git 插件安装器部署此插件；777 可以单独导入公开技能目录")
            matchingService != null -> connectorMarketState(matchingService.registration, matchingService.connection)
            installedFromPlugin -> ExtensionMarketState(
                ExtensionMarketStatus.INSTALLED, "已安装可用技能",
                "本机技能已就绪；插件其它能力在详情中说明支持范围")
            verifiedDetail?.skills?.isNotEmpty() == true -> ExtensionMarketState(
                ExtensionMarketStatus.INSTALLABLE, "可安装技能",
                "点击了解，选择需要的技能；安装后显示其运行依赖")
            verifiedDetail?.mcpUrl != null -> ExtensionMarketState(
                ExtensionMarketStatus.NEEDS_SETUP, "连接后可用",
                "官方授权策略：" +
                    if (plugin.authPolicy == "ON_USE") "首次使用时授权" else "连接时授权")
            verifiedDetail != null && verifiedDetail.hasApp -> ExtensionMarketState(
                ExtensionMarketStatus.UNSUPPORTED, "需要 App 运行环境",
                "该插件依赖独立应用宿主；777 当前没有对应的 Android 执行器")
            verifiedDetail != null && verifiedDetail.hasHooks -> ExtensionMarketState(
                ExtensionMarketStatus.UNSUPPORTED, "Codex 钩子能力",
                "此插件的钩子需要对应 Codex 执行环境")
            else -> ExtensionMarketState(ExtensionMarketStatus.NEEDS_SETUP,
                "查看能力", "点击了解，检查该插件在 Android 上可使用的部分")
        }
        InventoryRow("codex-plugin:" + plugin.id,
            if (copy.title == "本地插件") plugin.name else copy.title,
            if (copy.description == "已注册的本地扩展能力")
                "Codex 官方插件 · ${plugin.category.ifBlank { "未分类" }}" else copy.description,
            connectedService = false, icon = copy.icon, accent = copy.accent,
            group = group, documentIcon = copy.documentIcon,
            installedPlugin = installedFromPlugin || matchingService != null,
            available = false,
            sourceLabel = if (plugin.sourceKind == "local")
                "Codex · openai/plugins · ${plugin.category}"
            else "Codex · ${plugin.sourceKind} · 第三方仓库",
            marketState = support)
    }
    // The marketplace represents an official plugin bundle once. Its installed
    // member skills remain individually accessible on the Skills page.
    val pluginPageSkills = if (skillsOnly) installedSkills else installedSkills.filterNot { installed ->
        codexPlugins.any { plugin ->
            !plugin.codexOnly && plugin.installPolicy != "NOT_AVAILABLE" &&
                codexOfficialPluginSourceMatches(installed.sourceIdentity, plugin.sourcePath)
        }
    }
    val catalogue = local + connected + pluginPageSkills + availablePresets + curated + officialPlugins
    val list = catalogue.asSequence().filter { item ->
        marketplaceTabAllows(
            selectedTab = selectedCategory, skillsOnly = skillsOnly,
            installedSkill = item.installedSkill,
            installable = item.installablePreset || item.installableCurated ||
                item.id.startsWith("codex-plugin:"),
            connectedService = item.connectedService,
            localPlugin = item.id.startsWith("local:") || item.installedPlugin,
            group = item.group,
        ) && (!item.connectedService || selectedCategory != 1 ||
            connectedServices.firstOrNull { "service:${it.id}" == item.id }?.let {
                pluginTransportMatches(it.transport, transportFilter)
            } == true)
    }.mapNotNull { item ->
        inventorySearchScore(query, item.name, item.id, item.detail)?.let { item to it }
    }.sortedWith(compareByDescending<Pair<InventoryRow, Int>> { it.second }
        .thenBy { it.first.name }).map { it.first }.toList()

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!embedded) SyTopBar(
                title = stringResource(if (skillsOnly) R.string.skills_title else R.string.tools_catalog_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = SyMetric.gapSection, vertical = SyMetric.gapSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    },
                    modifier = Modifier.testTag("plugin-inventory-search-trigger"),
                ) {
                    Icon(FeatherIcons.Search, contentDescription = "查找技能或插件",
                        tint = colors.text)
                }
                IconButton(
                    onClick = { showMarketActions = true },
                    modifier = Modifier.testTag("plugin-inventory-more"),
                ) {
                    Icon(FeatherIcons.MoreVertical, contentDescription = "更多操作",
                        tint = colors.text)
                }
            }
            if (showSearch) {
                SySearchField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapSm)
                        .testTag("plugin-inventory-search"),
                    placeholder = stringResource(if (skillsOnly) R.string.skills_catalog_search
                        else R.string.tools_catalog_search),
                )
            }
            LazyRow(
                modifier = Modifier.testTag("plugin-category-list"),
                contentPadding = PaddingValues(horizontal = SyMetric.gapSection),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                items(5) { category ->
                    SyFilterChip(
                        text = if (skillsOnly) when (category) {
                            1 -> "已安装"
                            2 -> "办公效率"
                            3 -> "开发研究"
                            4 -> "创意设计"
                            else -> "发现"
                        } else when (category) {
                            1 -> "已连接"
                            2 -> "已安装"
                            3 -> "设备与自动化"
                            4 -> "创意设计"
                            else -> "发现"
                        },
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = SyMetric.gapSection,
                    vertical = SyMetric.gapMd,
                ),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (skillsOnly && curatedError != null) {
                    item(key = "curated-error") {
                        Text(curatedError, color = colors.dangerInk,
                            style = DsType.small13.withReadingWeight())
                    }
                }
                if (!skillsOnly && codexError != null) {
                    item(key = "codex-error") {
                        Text(codexError, color = colors.dangerInk,
                            style = DsType.small13.withReadingWeight())
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
                        val codexId = item.id.takeIf { it.startsWith("codex-plugin:") }
                            ?.removePrefix("codex-plugin:")
                        val logoBytes = codexId?.let(codexLogos::get)
                        if (codexId != null) {
                            LaunchedEffect(codexId, logoBytes) {
                                if (logoBytes == null) onRequestCodexLogo?.invoke(codexId)
                            }
                        }
                        val brandLogo = remember(logoBytes) { decodeCodexBrandLogo(logoBytes) }
                        ExtensionCatalogCard(
                            id = item.id,
                            title = item.name,
                            description = item.detail,
                            icon = item.icon,
                            accent = item.accent,
                            documentIcon = item.documentIcon,
                            brandLogo = brandLogo,
                            statusLabel = item.marketState.label,
                            onOpen = {
                                selectedRow = item
                                if (item.id.startsWith("codex-plugin:")) {
                                    onReadCodexPluginDetail?.invoke(
                                        item.id.removePrefix("codex-plugin:"))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    selectedRow?.let { selected ->
        // Reproject from authoritative inventory while install/OAuth updates arrive.
        // A selected preset becomes the installed skill with the same stable ID.
        val item = catalogue.firstOrNull { it.id == selected.id }
            ?: if (selected.installableCurated)
                installedSkills.firstOrNull { it.id == "skill:${selected.id.substringAfter(":")}" }
                    ?: selected
            else selected
        val detailLogoBytes = item.id.takeIf { it.startsWith("codex-plugin:") }
            ?.removePrefix("codex-plugin:")?.let(codexLogos::get)
        val detailBrandLogo = remember(detailLogoBytes) {
            decodeCodexBrandLogo(detailLogoBytes)
        }
        ExtensionDetailPage(
            title = item.name,
            onDismiss = { selectedRow = null },
            footer = {
                val pluginId = item.id.removePrefix("codex-plugin:")
                val pluginDetail = if (item.id.startsWith("codex-plugin:")) codexDetails[pluginId] else null
                val oneSkill = pluginDetail?.skills?.singleOrNull()
                val officialRoot = codexPlugins.firstOrNull { it.id == pluginId }?.sourcePath
                val missingPluginSkills = pluginDetail?.skills.orEmpty().filterNot { skillId ->
                    val path = pluginDetail?.skillDirectories?.get(skillId)
                    officialRoot != null && path != null &&
                        codexPluginSkillInstalled(skills, officialRoot, path)
                }
                val oneSkillInstalled = oneSkill == null || oneSkill !in missingPluginSkills
                val installedBundleRequirements = if (officialRoot == null) emptyList() else
                    skills.filter { skill ->
                        pluginDetail?.skillDirectories?.values.orEmpty().any { folder ->
                            codexOfficialPluginSourceMatches(
                                skill.sourceIdentity, officialRoot, folder)
                        }
                    }.flatMap { it.requirements }
                val missingBundleAccount = installedBundleRequirements.any { requirement ->
                    !requirement.registered && requirement.type.equals("mcp", ignoreCase = true) &&
                        connectedServices.none { it.id == requirement.value } &&
                        skillMcpHttpsEndpoint(requirement) != null
                }
                val knownUnreadySkillServer = item.requirements.any { requirement ->
                    !requirement.registered && requirement.type.equals("mcp", ignoreCase = true) &&
                        connectedServices.any { server -> server.id == requirement.value }
                }
                val endpoint = item.requirements.firstNotNullOfOrNull { requirement ->
                    if (requirement.registered || (requirement.type.equals("mcp", ignoreCase = true) &&
                            connectedServices.any { it.id == requirement.value })) null
                    else skillMcpHttpsEndpoint(requirement)?.let { url -> requirement.value to url }
                }
                val usePrompt = if (item.installedSkill)
                    "@skill:${item.id.substringAfter(":")}\n"
                    else stringResource(R.string.plugin_use_prompt, item.name, item.id.substringAfter(":"))
                val officialSupported = item.marketState.status != ExtensionMarketStatus.UNSUPPORTED
                val canInstallPreset = item.installablePreset && onInstallPreset != null
                val canInstallCurated = item.installableCurated && onInstallCurated != null
                val canInstallOfficial = officialSupported &&
                    missingPluginSkills.isNotEmpty() &&
                    (onInstallCodexPlugin != null ||
                        (oneSkill != null && !oneSkillInstalled &&
                            onInstallCodexPluginSkill != null))
                val officialService = pluginDetail?.mcpServerId?.let { serverId ->
                    connectedServices.firstOrNull { it.id == serverId }
                }
                val officialRegistered = officialService?.registration ==
                    com.labteto.dshmobile.uicontract.UiToolRegistration.REGISTERED
                val officialConnected = officialRegistered && officialService?.tools?.isNotEmpty() == true
                val canInspectOfficial = officialSupported && officialRegistered && !officialConnected
                val canConnectOfficial = officialSupported && !officialRegistered &&
                    pluginDetail?.mcpUrl != null && onConnectCodexPlugin != null
                val canConnectSkill = endpoint != null && onConnectSkillMcp != null
                val canResumeBundleSetup = officialSupported && pluginDetail != null &&
                    missingPluginSkills.isEmpty() && missingBundleAccount &&
                    onInstallCodexPlugin != null
                val canInspectSkill = knownUnreadySkillServer && item.installedSkill
                val canUseOfficial = officialSupported && item.installedPlugin &&
                    pluginDetail?.samplePrompts?.isNotEmpty() == true &&
                    (pluginDetail.mcpUrl == null || officialConnected) &&
                    (!pluginDetail.hasApp || officialConnected) &&
                    !pluginDetail.hasHooks && onUseCapability != null
                val hasDirectAction = canInstallPreset || canInstallCurated ||
                    canInstallOfficial || canConnectOfficial || canInspectOfficial ||
                    canResumeBundleSetup || canConnectSkill || canInspectSkill || canUseOfficial ||
                    (onUseCapability != null && item.available) || item.connectedService ||
                    (!item.id.startsWith("codex-plugin:") &&
                        !item.installablePreset && !item.installableCurated)
                if (hasDirectAction) SyButton(
                    text = when {
                        loading -> "正在安装或授权…"
                        canInstallPreset || canInstallCurated -> "安装"
                        canInstallOfficial -> if (missingPluginSkills.size > 1)
                            "安装包含的 ${missingPluginSkills.size} 项技能" else "安装"
                        canConnectOfficial || canConnectSkill -> "连接并授权"
                        canResumeBundleSetup -> "继续完成授权"
                        canInspectOfficial || canInspectSkill -> "检查连接"
                        canUseOfficial -> "使用"
                        item.installedSkill || (onUseCapability != null && item.available) -> "使用"
                        item.connectedService -> "管理连接"
                        else -> "返回对话"
                    },
                    modifier = Modifier.fillMaxWidth().testTag("plugin-detail-primary-action"),
                    enabled = !loading,
                    size = SyButtonSize.Large,
                    onClick = {
                        when {
                            canInstallPreset -> onInstallPreset?.invoke(item.id.substringAfter(":"))
                            canInstallCurated -> onInstallCurated?.invoke(item.id.substringAfter(":"))
                            canInstallOfficial -> {
                                if (onInstallCodexPlugin != null) onInstallCodexPlugin(pluginId)
                                else if (oneSkill != null)
                                    onInstallCodexPluginSkill?.invoke(pluginId, oneSkill)
                            }
                            canConnectOfficial -> onConnectCodexPlugin?.invoke(pluginId)
                            canResumeBundleSetup -> onInstallCodexPlugin?.invoke(pluginId)
                            canInspectOfficial || canInspectSkill -> {
                                selectedRow = null
                                onManageConnections()
                            }
                            canConnectSkill -> requireNotNull(endpoint).let { (serverId, url) ->
                                onConnectSkillMcp?.invoke(serverId, url)
                            }
                            canUseOfficial -> {
                                selectedRow = null
                                onUseCapability?.invoke(requireNotNull(pluginDetail).samplePrompts.first())
                            }
                            onUseCapability != null && item.available -> {
                                selectedRow = null
                                onUseCapability(usePrompt)
                            }
                            item.connectedService -> {
                                selectedRow = null
                                onManageConnections()
                            }
                            else -> {
                                selectedRow = null
                                onReturnToChat()
                            }
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
                            detailBrandLogo != null -> androidx.compose.foundation.Image(
                                bitmap = detailBrandLogo, contentDescription = null,
                                modifier = Modifier.size(68.dp),
                            )
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
                    Text(item.name, style = DsType.headline17.withReadingWeight(),
                        color = colors.text)
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
            if (item.id.startsWith("codex-plugin:")) {
                val pluginId = item.id.removePrefix("codex-plugin:")
                Text(
                    if (codexPlugins.firstOrNull { it.id == pluginId }?.sourceKind == "local")
                        "官方来源：github.com/openai/plugins"
                    else "官方目录收录的第三方仓库：${codexPlugins.firstOrNull { it.id == pluginId }?.sourceUrl.orEmpty()}",
                    style = DsType.caption11, color = colors.textTertiary,
                )
                val details = codexDetails[pluginId]
                when {
                    details != null -> {
                        Text("介绍", style = DsType.headline17.withReadingWeight(),
                            color = colors.text)
                        Text(details.description, style = DsType.std14.withReadingWeight(),
                            color = colors.textSecondary)
                        if (details.samplePrompts.isNotEmpty()) {
                            Surface(
                                color = item.accent.copy(alpha = 0.06f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("试试这样使用", style = DsType.small13Strong,
                                        color = colors.textSecondary)
                                    Text(details.samplePrompts.first(),
                                        style = DsType.std14.withReadingWeight(), color = colors.text)
                                    val pluginReady = item.installedPlugin && when {
                                        details.mcpUrl != null -> connectedServices.any {
                                            it.id == details.mcpServerId &&
                                                it.tools.isNotEmpty()
                                        }
                                        details.hasApp || details.hasHooks -> false
                                        else -> true
                                    }
                                    if (pluginReady && onUseCapability != null) {
                                        SyButton(
                                            text = "使用这个示例",
                                            variant = SyButtonVariant.Ghost,
                                            onClick = {
                                                selectedRow = null
                                                onUseCapability(details.samplePrompts.first())
                                            },
                                            modifier = Modifier.testTag("codex-plugin-example"),
                                        )
                                    }
                                }
                            }
                        }
                        Text("发布方：${details.developerName.ifBlank { "OpenAI / 合作开发者" }} · ${details.version}",
                            style = DsType.caption11, color = colors.textTertiary)
                        val abilities = buildList {
                            if (details.skills.isNotEmpty()) add("技能")
                            if (details.hasMcp) add("MCP")
                            if (details.hasApp) add("App")
                            if (details.hasHooks) add("Hooks")
                        }
                        Text("包含能力：${abilities.joinToString(" / ").ifBlank { "元数据" }}",
                            style = DsType.small13, color = colors.textSecondary)
                        if (details.skills.isNotEmpty() &&
                            item.marketState.status != ExtensionMarketStatus.UNSUPPORTED) {
                            var childSkillsExpanded by remember(pluginId) { mutableStateOf(false) }
                            Surface(
                                modifier = Modifier.fillMaxWidth().testTag("codex-plugin-skills-summary"),
                                color = colors.materialL4,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                onClick = { childSkillsExpanded = !childSkillsExpanded },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("包含技能 · ${details.skills.size} 项",
                                        style = DsType.std14Strong, color = colors.text)
                                    Icon(FeatherIcons.ChevronDown,
                                        contentDescription = "展开技能目录",
                                        tint = colors.textSecondary)
                                }
                            }
                            if (childSkillsExpanded) details.skills.forEach { skill ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(skill, modifier = Modifier.weight(1f),
                                        style = DsType.small13, color = colors.text)
                                    val officialRoot = codexPlugins
                                        .firstOrNull { it.id == pluginId }?.sourcePath
                                    val skillFolder = details.skillDirectories[skill]
                                    val installed = officialRoot != null &&
                                        skillFolder != null && codexPluginSkillInstalled(
                                            skills, officialRoot, skillFolder)
                                    SyButton(
                                        text = if (installed) "已安装" else "安装",
                                        variant = SyButtonVariant.Info,
                                        enabled = !loading && !installed && onInstallCodexPluginSkill != null,
                                        modifier = Modifier.testTag("codex-skill-install-$pluginId-$skill"),
                                        onClick = {
                                            selectedRow = null
                                            onInstallCodexPluginSkill?.invoke(pluginId, skill)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    codexDetailLoadingId == pluginId ->
                        Text("正在检查官方插件包及技能目录…", style = DsType.small13,
                            color = colors.textSecondary)
                    else -> {
                        Text(codexDetailError ?: "按需获取该插件的详细能力说明",
                            style = DsType.small13, color = colors.textSecondary)
                        if (onReadCodexPluginDetail != null) {
                            SyButton(text = "重新读取插件详情", onClick = {
                                onReadCodexPluginDetail(pluginId)
                            }, variant = SyButtonVariant.Ghost)
                        }
                    }
                }
                Text("安装技能不会静默启动外部程序；MCP 服务需要你明确同意连接。",
                    style = DsType.caption11, color = colors.textSecondary)
                if (item.marketState.status != ExtensionMarketStatus.UNSUPPORTED &&
                    details?.mcpUrl != null && onConnectCodexPlugin != null) {
                    Text("连接地址：" + details.mcpUrl,
                        style = DsType.caption11, color = colors.textSecondary)
                    SyButton(text = "授权并连接 ${details.mcpServerId ?: "MCP"}",
                        enabled = !loading, variant = SyButtonVariant.Info,
                        onClick = {
                            selectedRow = null
                            onConnectCodexPlugin(pluginId)
                        })
                } else if (details?.hasMcp == true && details.mcpUrl == null) {
                    Text("该插件暂未提供 Android 可直接连接的 HTTPS MCP 服务。",
                        style = DsType.small13, color = colors.textSecondary)
                }
                if (details?.hasApp == true) {
                    Text("所含 App 需要对应的应用宿主；777 当前只安装可独立运行的技能。",
                        style = DsType.small13, color = colors.textSecondary)
                }
                if (details?.hasHooks == true) {
                    Text("所含 Hooks 依赖 Codex 执行环境，本机暂不执行。",
                        style = DsType.small13, color = colors.textSecondary)
                }
            }
            if (item.requirements.isNotEmpty()) {
                var requirementsExpanded by remember(item.id) { mutableStateOf(false) }
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
                            "${if (requirement.registered) "已连接" else "需要授权"} · ${requirement.description.ifBlank { requirement.value }}",
                            style = DsType.small13.withReadingWeight(),
                            color = if (requirement.registered) colors.successInk else colors.warningInk,
                        )
                        val endpoint = if (!requirement.registered)
                            skillMcpHttpsEndpoint(requirement) else null
                        if (endpoint != null && onConnectSkillMcp != null) {
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
        SyResponsiveSheet(
            title = if (skillsOnly) "技能管理" else "插件管理",
            onDismiss = { showMarketActions = false },
        ) {
            if (skillsOnly) {
                if (onImportSkill != null) SyButton(
                    text = "导入技能文件",
                    onClick = { showMarketActions = false; onImportSkill() },
                    modifier = Modifier.fillMaxWidth().testTag("skill-import-file"),
                )
                if (onUseCapability != null) SyButton(
                    text = "对话创建技能",
                    variant = SyButtonVariant.Ghost,
                    onClick = { showMarketActions = false; onUseCapability(skillCreationPrompt) },
                    modifier = Modifier.fillMaxWidth().testTag("skill-create-in-chat"),
                )
                if (onInstallGitHubSkill != null) SyButton(
                    text = "从 GitHub 安装",
                    variant = SyButtonVariant.Ghost,
                    onClick = { showMarketActions = false; showGitHubImport = true },
                    modifier = Modifier.fillMaxWidth().testTag("skill-install-github"),
                )
                if (onCreateSkill != null) SyButton(
                    text = "手动创建技能",
                    variant = SyButtonVariant.Ghost,
                    onClick = { showMarketActions = false; showCreateSkill = true },
                    modifier = Modifier.fillMaxWidth().testTag("skill-create-manual"),
                )
                if (onRefreshCurated != null) SyButton(
                    text = "刷新精选目录", variant = SyButtonVariant.Ghost,
                    onClick = { showMarketActions = false; onRefreshCurated() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                if (onRefreshCodex != null) SyButton(
                    text = "刷新插件目录", variant = SyButtonVariant.Ghost,
                    onClick = { showMarketActions = false; onRefreshCodex() },
                    modifier = Modifier.fillMaxWidth(),
                )
                SyButton(text = "连接器管理", variant = SyButtonVariant.Ghost,
                    onClick = { showMarketActions = false; onManageConnections() },
                    modifier = Modifier.fillMaxWidth())
            }
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
                    enabled = !loading && githubImportUrl.trim().startsWith("https://github.com/"),
                    onClick = {
                        val source = githubImportUrl.trim()
                        showGitHubImport = false
                        githubImportUrl = ""
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
