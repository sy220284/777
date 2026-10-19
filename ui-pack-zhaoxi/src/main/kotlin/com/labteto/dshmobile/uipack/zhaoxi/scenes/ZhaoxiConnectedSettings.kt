package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.local.model.*
import com.labteto.dshmobile.local.memory.*
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Whole-app Model Performance Owner accepts only 1.0–2.0, no silent UI clamping. */
internal fun ModelPerformanceUiReceipt.zxPerformanceMessage(): String = when (this) {
    ModelPerformanceUiReceipt.Applied -> "设置已保存"
    ModelPerformanceUiReceipt.InvalidValue -> "数值超出支持范围"
    is ModelPerformanceUiReceipt.Failed -> message ?: "保存失败"
}

/** Render typed Settings receipts without exposing Kotlin data-class names. */
internal fun SettingsUiReceipt.zxUserMessage(action: String = "操作"): String = when (this) {
    SettingsUiReceipt.Applied -> "${action}成功"
    SettingsUiReceipt.Submitted -> "已提交，等待生效"
    SettingsUiReceipt.Rejected -> "${action}不可用"
    is SettingsUiReceipt.Failed -> message ?: "${action}失败"
}

/** Credentials exist only in the current form; all writes stay with the existing Model owner. */
@Composable
fun ZhaoxiModelsScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    val jobs = rememberCoroutineScope()
    val modelScroll = androidx.compose.foundation.rememberScrollState()
    // Plan-backed routes are managed by Account Owner, never edited as API-key profiles.
    val apiProfiles = snap.modelProfiles.filter { it.authKind == LocalModelAuthKind.API_KEY }
    val planProfiles = snap.modelProfiles.count { it.authKind == LocalModelAuthKind.CHATGPT_PLAN }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var credentialSourceProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var showModelForm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showModelForm) {
        if (showModelForm) modelScroll.animateScrollTo(0)
    }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var presetQuery by rememberSaveable { mutableStateOf("") }
    var showAdvancedConnection by rememberSaveable { mutableStateOf(false) }
    var expandedModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoveModel by remember { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("https://api.deepseek.com") }
    var protocol by rememberSaveable { mutableStateOf(LocalModelProtocol.CHAT_COMPLETIONS) }
    var contextWindowTokens by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>?>(null) }
    var catalogFilter by remember { mutableStateOf("") }
    var catalogFailure by remember { mutableStateOf<String?>(null) }
    val preset = settings.apiModelPresets.firstOrNull {
        it.model == model.trim() && it.baseUrl.trimEnd('/') == baseUrl.trim().trimEnd('/')
    }
    val isKimiK3 = preset?.provider == "Kimi Code" && preset.model == "k3"
    val supportsWindowOverride = preset == null || isKimiK3
    val window = contextWindowTokens.toIntOrNull()
    val validWindow = contextWindowTokens.isBlank() || (window != null && (
        if (isKimiK3) window == 262_144 || window == 1_048_576
        else window in 4_096..16_000_000
    ))
    fun resetCatalog() {
        discoveredModels = null
        catalogFailure = null
        catalogFilter = ""
    }
    fun execute(action: suspend () -> String) {
        if (busy) return
        busy = true
        jobs.launch {
            try { status = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    fun startNewModel() {
        profileId = null
        credentialSourceProfileId = null
        model = ""
        baseUrl = "https://api.deepseek.com"
        protocol = LocalModelProtocol.CHAT_COMPLETIONS
        contextWindowTokens = ""
        apiKey = ""
        status = null
        resetCatalog()
        showModelForm = true
        showPresets = false
        presetQuery = ""
        showAdvancedConnection = false
    }
    val activeProfile = snap.modelProfiles.firstOrNull { snap.modelSelection.isActive(it) }
    ZxPage("模型与账户", onBack = { scope.onBack() }, scrollState = modelScroll) {
        snap.error?.let { ZxBanner(it, ZxTone.Err) }
        status?.let { ZxBanner(it, ZxTone.Warn) }
        ZxSection("当前使用")
        ZxSettingsGroup {
            if (activeProfile == null) {
                ZxFooterNote("尚未选用模型。添加 API 模型，或连接 ChatGPT 套餐账户后即可使用。")
            } else {
                ZxRow(activeProfile.displayName ?: activeProfile.model,
                    (if (activeProfile.authKind == LocalModelAuthKind.CHATGPT_PLAN) "ChatGPT 套餐" else
                        activeProfile.provider.ifBlank { "API 模型" }) + " · ${activeProfile.model}",
                    showChevron = false)
            }
        }
        ZxSection("API 模型 · ${apiProfiles.size}")
        ZxFooterNote("已保存的模型可展开管理，切换后以“当前使用”标记为准。")
        ZxButton(if (showModelForm) "正在编辑模型" else "＋ 添加 API 模型", {
            startNewModel()
        }, Modifier.fillMaxWidth(), variant = if (showModelForm) ZxButtonVariant.Outline
            else ZxButtonVariant.Ink, enabled = !busy)
        if (showModelForm) {
            ZxSettingsGroup {
                ZxSection(if (profileId == null && credentialSourceProfileId == null) "添加模型" else "编辑模型")
                ZxButton(if (showPresets) "收起预设模型" else "选择预设模型", {
                    showPresets = !showPresets
                    if (!showPresets) presetQuery = ""
                }, Modifier.fillMaxWidth(), variant = ZxButtonVariant.Outline, enabled = !busy)
                if (showPresets) {
                    ZxField(presetQuery, { presetQuery = it.take(100) }, Modifier.fillMaxWidth(),
                        placeholder = "搜索服务商或模型", fieldLabel = "搜索预设模型")
                    val matches = settings.apiModelPresets.filter {
                        it.model.contains(presetQuery.trim(), ignoreCase = true) ||
                            it.provider.contains(presetQuery.trim(), ignoreCase = true)
                    }
                    if (matches.isEmpty()) ZxFooterNote("未找到匹配预设，可直接手动填写模型。")
                    matches.take(12).groupBy { it.provider }.forEach { (provider, models) ->
                        ZxSection(provider)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            models.forEach { option ->
                                ZxChip(option.model, model == option.model &&
                                    baseUrl.trimEnd('/') == option.baseUrl.trimEnd('/'),
                                    modifier = Modifier.testTag("zx-model-preset-${option.model}")) {
                                    profileId = null
                                    credentialSourceProfileId = null
                                    model = option.model
                                    baseUrl = option.baseUrl
                                    protocol = option.protocol
                                    contextWindowTokens = ""
                                    apiKey = ""
                                    resetCatalog()
                                    status = null
                                    showAdvancedConnection = false
                                    showPresets = false
                                }
                            }
                        }
                    }
                    if (matches.size > 12) ZxFooterNote("预设较多，输入名称可快速定位其他模型。")
                    ZxButton("手动配置其他模型", {
                        profileId = null
                        credentialSourceProfileId = null
                        model = ""
                        baseUrl = ""
                        protocol = LocalModelProtocol.CHAT_COMPLETIONS
                        contextWindowTokens = ""
                        apiKey = ""
                        resetCatalog()
                        showAdvancedConnection = true
                        showPresets = false
                    }, Modifier.fillMaxWidth(), variant = ZxButtonVariant.Ghost, enabled = !busy)
                }
                ZxSection("模型标识")
                ZxField(model, { value ->
                    if (value != model) {
                        if (profileId != null) credentialSourceProfileId = profileId
                        profileId = null
                        model = value.take(160)
                        resetCatalog()
                    }
                }, Modifier.fillMaxWidth(), placeholder = "模型标识（支持手动输入）", fieldLabel = "模型名称")
                if (preset != null && profileId == null && !showAdvancedConnection) {
                    ZxFooterNote("已应用 ${preset.provider} 预设地址和协议；需要代理或自定义接口时可展开修改。")
                }
                if (preset != null) ZxButton(
                    if (showAdvancedConnection) "收起连接参数" else "调整接口与高级参数",
                    { showAdvancedConnection = !showAdvancedConnection },
                    Modifier.fillMaxWidth(), variant = ZxButtonVariant.Ghost, enabled = !busy)
                if (preset == null || showAdvancedConnection || profileId != null) {
                ZxSection("接口地址")
                ZxField(baseUrl, { value ->
                    if (value != baseUrl) {
                        if (value.trimEnd('/') != baseUrl.trimEnd('/')) {
                            profileId = null
                            credentialSourceProfileId = null
                        }
                        baseUrl = value.take(1000)
                        resetCatalog()
                    }
                }, Modifier.fillMaxWidth(), placeholder = "https://…", fieldLabel = "接口地址")
                ZxSection("请求协议")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalModelProtocol.entries.forEach { p ->
                        val label = when (p) {
                            LocalModelProtocol.CHAT_COMPLETIONS -> "Chat Completions"
                            LocalModelProtocol.RESPONSES -> "Responses"
                            LocalModelProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
                        }
                        ZxChip(label, p == protocol) {
                            protocol = p
                            resetCatalog()
                        }
                    }
                }
                preset?.modelsEndpoint?.let { ZxFooterNote("官方模型目录：$it") }
                if (supportsWindowOverride) {
                    ZxSection("上下文窗口（可选）")
                    ZxField(contextWindowTokens, { value ->
                        contextWindowTokens = value.filter(Char::isDigit).take(8)
                    }, Modifier.fillMaxWidth(), placeholder = if (isKimiK3) "262144 或 1048576" else "留空使用默认值",
                        fieldLabel = "Token 上下文上限")
                    ZxFooterNote(if (isKimiK3)
                        "Kimi K3 默认 256K；仅具有相应套餐权限时选择 1048576，设置不会赋予套餐权限。"
                    else "自定义模型填写 4096–16000000；留空使用模型的默认上下文窗口。")
                    if (!validWindow) ZxBanner("上下文窗口数值不支持，请检查后保存", ZxTone.Err)
                }
                }
                ZxSection("访问凭据")
                ZxField(apiKey, { apiKey = it.take(8000); resetCatalog() },
                    Modifier.fillMaxWidth(), placeholder = "API Key（编辑时留空保留原凭据）",
                    visualTransformation = PasswordVisualTransformation())
                ZxFooterNote(if (profileId != null || credentialSourceProfileId != null)
                    "留空可沿用同一接口地址的已保存密钥；修改接口地址时必须重新提供凭据。"
                else "API Key 仅交由模型配置接口保存，不在此页面回显。")
                ZxSection("验证与保存")
                ZxFooterNote("“获取可选模型”读取供应商目录；“测试连接”才会尝试请求当前模型。目录中出现不代表密钥有调用权限。")
                ZxButton("获取可选模型", {
                    if (!busy) {
                        val oldAddress = baseUrl.trim()
                        val oldProtocol = protocol
                        val oldProfile = profileId
                        val oldSource = credentialSourceProfileId
                        val oldKey = apiKey
                        busy = true
                        catalogFailure = null
                        discoveredModels = null
                        jobs.launch {
                            try {
                                val names = settings.discoverApiModels(
                                    oldKey, oldAddress, oldProtocol, oldSource ?: oldProfile
                                ).map(String::trim).filter(String::isNotBlank).distinct().sorted()
                                if (oldAddress != baseUrl.trim() || oldProtocol != protocol ||
                                    oldProfile != profileId || oldSource != credentialSourceProfileId ||
                                    oldKey != apiKey) {
                                    catalogFailure = "连接配置发生变化，请重新查询模型列表"
                                } else {
                                    discoveredModels = names
                                    status = if (names.isEmpty()) "目录为空，可手动填写模型" else
                                        "已读取 ${names.size} 个模型，选择后仍需保存"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                catalogFailure = failure.message ?: "读取模型目录失败"
                                status = "目录不可用，仍可手动填写和测试"
                            } finally { busy = false }
                        }
                    }
                }, enabled = !busy && baseUrl.isNotBlank() &&
                    (apiKey.isNotBlank() || profileId != null || credentialSourceProfileId != null),
                    variant = ZxButtonVariant.Outline, loading = busy)
                catalogFailure?.let { ZxFooterNote("目录读取失败：$it；不会清除手动填写内容。") }
                discoveredModels?.let { names ->
                    if (names.isEmpty()) ZxFooterNote("目录没有返回模型，可以手动填写模型标识。")
                    else {
                        ZxFooterNote("已发现 ${names.size} 个目录模型，点选只填入标识，不会自动保存或切换。")
                        if (names.size > 8) ZxField(catalogFilter, { catalogFilter = it.take(128) },
                            Modifier.fillMaxWidth(), placeholder = "筛选目录模型", fieldLabel = "筛选模型")
                        val matched = names.filter { it.contains(catalogFilter.trim(), ignoreCase = true) }
                        matched.take(12).forEach { available ->
                            ZxRow(available, if (model == available) "已填入编辑框" else "点选后仍需保存",
                                onClick = {
                                    if (profileId != null) credentialSourceProfileId = profileId
                                    profileId = null
                                    model = available
                                    status = "已选择 $available，请测试连接并保存"
                                }, showChevron = model != available)
                        }
                        if (matched.size > 12) ZxFooterNote("显示前 12 项，请用上方搜索框定位其他模型。")
                        if (matched.isEmpty()) ZxFooterNote("没有匹配的模型；仍可手动输入标识。")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("测试连接", {
                        execute {
                            settings.testModel(apiKey, model, baseUrl, protocol, profileId,
                                credentialSourceProfileId)
                        }
                    }, enabled = !busy && model.isNotBlank() && baseUrl.isNotBlank() &&
                        (apiKey.isNotBlank() || profileId != null || credentialSourceProfileId != null),
                        variant = ZxButtonVariant.Outline)
                    ZxButton("保存模型", {
                        if (!validWindow && supportsWindowOverride) {
                            status = "请先填写有效的上下文窗口"
                        } else {
                            execute {
                                val request = SettingsUiCommand.SaveModel(apiKey, model.trim(),
                                    baseUrl.trim(), protocol, profileId,
                                    if (supportsWindowOverride) window ?: 0 else null,
                                    credentialSourceProfileId)
                                when (val receipt = settings.save(request)) {
                                    SettingsUiReceipt.Applied -> {
                                        apiKey = ""
                                        profileId = null
                                        credentialSourceProfileId = null
                                        showModelForm = false
                                        "模型已保存"
                                    }
                                    SettingsUiReceipt.Submitted -> "保存请求已提交"
                                    SettingsUiReceipt.Rejected -> "无法保存模型"
                                    is SettingsUiReceipt.Failed -> receipt.message ?: "保存失败"
                                }
                            }
                        }
                    }, enabled = !busy && model.isNotBlank() && baseUrl.isNotBlank() &&
                        (apiKey.isNotBlank() || profileId != null || credentialSourceProfileId != null))
                }
                ZxButton("取消编辑", { showModelForm = false; apiKey = ""; resetCatalog() },
                    Modifier.fillMaxWidth(), variant = ZxButtonVariant.Ghost, enabled = !busy)
            }
        }
        ZxSection("已配置模型")
        if (apiProfiles.isEmpty() && !showModelForm)
            ZxEmpty("还没有 API 模型", "点击上方“添加 API 模型”，选择预设或手动配置。")
        apiProfiles.sortedByDescending { snap.modelSelection.isActive(it) }.forEach { p ->
            val active = snap.modelSelection.isActive(p)
            val duplicate = apiProfiles.count { it.model == p.model && it.baseUrl == p.baseUrl } > 1
            ZxRow((p.displayName ?: p.model) + if (duplicate) " · ${p.id.takeLast(4)}" else "",
                (if (active) "当前使用" else "已保存") + " · " +
                    p.provider.ifBlank { "自定义来源" } + " · ${p.model}",
                onClick = { expandedModelId = if (expandedModelId == p.id) null else p.id })
            if (expandedModelId == p.id) {
                ZxValueRow("地址", value = p.baseUrl)
                ZxValueRow("协议", value = p.protocol.name)
                p.contextWindowTokensOverride?.let { ZxValueRow("上下文上限", value = "$it Token") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!active) ZxChip("使用此模型", false) {
                        status = settings.submit(SettingsUiCommand.SelectModel(p.id))
                            .zxUserMessage("切换模型")
                    }
                    ZxChip("编辑配置", false) {
                        profileId = p.id
                        credentialSourceProfileId = p.id
                        model = p.model
                        baseUrl = p.baseUrl
                        protocol = p.protocol
                        contextWindowTokens = p.contextWindowTokensOverride?.toString().orEmpty()
                        apiKey = ""
                        showModelForm = true
                        showPresets = false
                        showAdvancedConnection = true
                        status = null
                        resetCatalog()
                    }
                    ZxChip("连接测试", false) {
                        execute { settings.testModel("", p.model, p.baseUrl, p.protocol, p.id) }
                    }
                    ZxChip("移除", false) { pendingRemoveModel = p.id }
                }
            }
        }
        pendingRemoveModel?.let { id ->
            ZxDialog("移除 API 模型？", "会删除此配置的本地模型和密钥，不影响其他 API 模型或 ChatGPT 账户。",
                "移除", {
                    pendingRemoveModel = null
                    status = when (val receipt = settings.submit(SettingsUiCommand.RemoveModel(id))) {
                        SettingsUiReceipt.Applied -> "模型已移除"
                        SettingsUiReceipt.Submitted -> "移除已提交，列表更新后生效"
                        SettingsUiReceipt.Rejected -> "当前无法移除"
                        is SettingsUiReceipt.Failed -> receipt.message ?: "移除失败"
                    }
                }, { pendingRemoveModel = null })
        }
        ZxSection("图片输入方式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalImageInputMode.entries.forEach { mode ->
                val label = when (mode) {
                    LocalImageInputMode.AUTO -> "自动"
                    LocalImageInputMode.NATIVE -> "原生输入"
                    LocalImageInputMode.TOOL -> "工具识图"
                }
                ZxChip(label, snap.imageInputMode == mode) {
                    status = settings.submit(SettingsUiCommand.ImageInputMode(mode))
                        .zxUserMessage("切换图片输入方式")
                }
            }
        }
        ZxFooterNote("依据当前模型实际支持能力选择；自动模式优先使用可用的原生图片输入。")
        ZxSection("其他模型与用量")
        ZxNavRow("ChatGPT 套餐账户 · ${planProfiles} 个模型", sub = "查看套餐授权状态、切换账户和模型") {
            scope.onNavigate(UiSceneId("settings.account"))
        }
        ZxNavRow("查看模型用量", sub = "按模型对照 Token 和估算费用") {
            scope.onNavigate(UiSceneId("settings.model_usage"))
        }
        ZxNavRow("MiniMax 套餐额度") { scope.onNavigate(UiSceneId("settings.minimax_quota")) }
        ZxNavRow("模型表现", sub = "推理上限与温度上限") {
            scope.onNavigate(UiSceneId("settings.model_performance"))
        }
        ZxNavRow("官方价格") { scope.onNavigate(UiSceneId("settings.pricing")) }
    }
}

/** Dedicated account management, separate from API model configuration. */
internal fun zxChatGptAccountAuthLabel(account: com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary): String = when {
    !account.signedIn -> "登录凭据已失效"
    !account.sharingEnabled -> "已登录 · 套餐尚未授权"
    else -> "已登录 · 套餐授权已启用"
}

@Composable
fun ZhaoxiAccountScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.chatGptState.collectAsState()
    val modelState by settings.state.collectAsState(initial = settings.initialState)
    val uriHandler = LocalUriHandler.current
    val selectedPlanProfiles = modelState.modelProfiles.filter {
        it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == snap.selectedAccountId
    }
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showAccountDangerId by remember { mutableStateOf<String?>(null) }
    var accountModelSearch by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(snap.selectedAccountId) { expanded = snap.selectedAccountId }
    var removeConfirm by remember { mutableStateOf<String?>(null) }
    var disconnectConfirm by remember { mutableStateOf<String?>(null) }
    fun execute(action: suspend () -> String?) {
        if (busy) return
        busy = true
        jobs.launch {
            try { status = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "账户操作未完成，请核对状态后重试" }
            finally { busy = false }
        }
    }
    ZxPage("账户管理", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        val phaseLabel = when (snap.phase) {
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.DISCONNECTED -> "未连接"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.PREPARING -> "准备授权"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.WAITING_FOR_BROWSER -> "等待浏览器授权"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.EXCHANGING_CODE -> "正在验证登录"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.VALIDATING_IDENTITY -> "正在核实账户"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.LOADING_MODELS -> "正在读取可用模型"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.CONNECTED -> "已连接"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.UNVERIFIED -> "账户待验证"
            com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase.ERROR -> "连接失败"
        }
        ZxSection("账户状态")
        ZxSettingsGroup {
            ZxRow("ChatGPT 套餐", "$phaseLabel · 已保存 ${snap.accounts.size} 个账户",
                showChevron = false)
            snap.selectedAccount?.let {
                ZxRow(it.displayName ?: it.email ?: "当前账户",
                    zxChatGptAccountAuthLabel(it), showChevron = false)
            }
        }
        (status ?: snap.error)?.let { ZxBanner(it, ZxTone.Warn) }
        if (busy) ZxFooterNote("正在执行账户操作，请以返回后的账户状态为准。")
        ZxSection("连接与添加")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("添加账户", { execute {
                settings.connectChatGpt()
                "已发起浏览器授权，请完成登录"
            } }, Modifier.weight(1f), enabled = !busy)
            ZxButton("刷新状态", { execute {
                settings.refreshChatGpt()
                "账户状态已刷新"
            } }, Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
        }
        if (snap.pendingAccountId != null) ZxButton("取消进行中的授权", {
            execute { settings.cancelChatGptAuthorization(); "取消授权请求已发出" }
        }, variant = ZxButtonVariant.Outline, enabled = !busy)
        ZxSection("已授权账户 · ${snap.accounts.size}")
        if (snap.accounts.isEmpty()) ZxEmpty("尚未添加 ChatGPT 账户", "点击“添加账户”，通过浏览器完成登录和套餐授权。")
        snap.accounts.forEach { account ->
            val active = snap.selectedAccountId == account.id
            ZxSettingsGroup {
            ZxRow(account.displayName ?: account.email ?: account.id,
                (if (active) "当前使用" else "其他账户") + " · " +
                    zxChatGptAccountAuthLabel(account),
                onClick = { expanded = if (expanded == account.id) null else account.id })
            if (expanded == account.id) {
                ZxValueRow("登录", value = if (account.signedIn) "已登录" else "需要重新授权")
                ZxValueRow("ChatGPT 套餐访问", value = when {
                    !account.signedIn -> "账户需重新登录"
                    account.sharingEnabled -> "已取得套餐授权"
                    else -> "尚未取得套餐授权"
                })
                if (account.signedIn && !account.sharingEnabled) {
                    ZxFooterNote("此账户已登录，但尚未授权套餐模型访问。可单独进行套餐授权；不会自动转为 API Key 计费。")
                    ZxButton("启用套餐访问", { execute {
                        settings.connectChatGpt(account.id, requestPlanConsent = true)
                        "套餐授权已请求，请在浏览器明确确认；完成后刷新账户状态"
                    } }, Modifier.fillMaxWidth(), enabled = !busy)
                }
                if (!active) ZxButton("切换至此账户", { execute {
                    settings.selectChatGptAccount(account.id)
                    "账户切换已提交，请核对当前账户状态"
                } }, Modifier.fillMaxWidth(), enabled = !busy)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("测试连接", { execute { settings.testChatGptAccount(account.id) } },
                        Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
                    ZxButton("重新连接", { execute {
                        settings.restartChatGptAuthorization(account.id)
                        "已发起重新授权，请在浏览器完成操作"
                    } }, Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
                }
                ZxButton(if (showAccountDangerId == account.id) "收起账户管理" else "断开或移除账户", {
                    showAccountDangerId = if (showAccountDangerId == account.id) null else account.id
                }, Modifier.fillMaxWidth(), variant = ZxButtonVariant.Ghost, enabled = !busy)
                if (showAccountDangerId == account.id) {
                    ZxFooterNote("以下操作不会删除 ChatGPT 官方账号，但可能使已选套餐模型失效。")
                    FlowRow(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxButton("断开连接", { disconnectConfirm = account.id },
                            Modifier.weight(1f), variant = ZxButtonVariant.Danger, enabled = !busy)
                        ZxButton("移除本地账户", { removeConfirm = account.id },
                            Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
                    }
                }
            }
            }
        }
        ZxSection("当前账户可用模型 · ${snap.models.size}")
        if (!snap.connected) {
            ZxFooterNote("需要先完成登录与套餐授权，才可从当前账户使用套餐模型。")
        } else if (snap.models.isEmpty()) {
            ZxEmpty("模型目录暂无内容", "刷新账户状态以重新读取套餐可用模型")
        } else {
            if (snap.models.size > 12) ZxField(accountModelSearch,
                { accountModelSearch = it.take(128) }, Modifier.fillMaxWidth(),
                placeholder = "搜索套餐模型", fieldLabel = "搜索套餐模型")
            val visible = snap.models.filter {
                it.slug.contains(accountModelSearch.trim(), ignoreCase = true) ||
                    it.displayName.contains(accountModelSearch.trim(), ignoreCase = true)
            }
            visible.take(12).forEach { option ->
                val route = selectedPlanProfiles.firstOrNull { it.model == option.slug }
                val active = route != null && modelState.modelSelection.isActive(route)
                ZxRow(option.displayName,
                    (if (active) "当前使用" else "来自当前套餐") +
                        (if (option.slug == option.displayName) "" else " · ${option.slug}"),
                    showChevron = false,
                    trailing = {
                        if (active) Text("使用中", color = ZhaoxiTheme.colors.ink2)
                        else if (route != null) ZxChip("选用", false) {
                            status = settings.submit(SettingsUiCommand.SelectModel(route.id))
                                .zxUserMessage("切换套餐模型")
                        }
                    })
            }
            if (visible.size > 12) ZxFooterNote("仅展示前 12 项，可通过搜索快速定位其他模型。")
            if (visible.isEmpty()) ZxFooterNote("没有匹配的套餐模型。")
        }
        ZxSection("额度与用量")
        ZxNavRow("查看 ChatGPT 官方套餐额度", sub = "在 ChatGPT 官方页面查询，非本地估算") {
            uriHandler.openUri("https://chatgpt.com/#settings/Usage")
        }
        ZxNavRow("本地模型消耗", sub = "查看 Token、费用估算和请求记录") {
            scope.onNavigate(UiSceneId("settings.model_usage"))
        }
    }
    disconnectConfirm?.let { id ->
        ZxDialog("断开账户连接？",
            "此账户将失去当前的可用授权，其他本地账户仍然保留。",
            "断开", {
                disconnectConfirm = null
                execute { settings.disconnectChatGptAccount(id) ?: "已提交断开请求" }
            }, { disconnectConfirm = null })
    }
    removeConfirm?.let { id ->
        ZxDialog("移除本地账户？",
            "移除后需要再次添加和授权；不会删除 ChatGPT 平台上的账户。",
            "移除", {
                removeConfirm = null
                execute { settings.removeChatGptAccount(id) ?: "已提交移除请求" }
            }, { removeConfirm = null })
    }
}

@Composable
fun ZhaoxiQuotaScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    val profile = snap.miniMaxQuotaProfile
    var result by remember { mutableStateOf<MiniMaxProviderUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    ZxPage("MiniMax 套餐额度", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (profile == null) {
            ZxEmpty("当前没有可查询的额度", "请先选择支持套餐额度查询的 MiniMax 模型配置")
        } else {
            ZxValueRow("查询模型", value = profile.displayName ?: profile.model)
            ZxButton(if (busy) "查询中…" else "刷新额度", {
                jobs.launch {
                    busy = true
                    error = null
                    try {
                        result = settings.queryMiniMaxQuota(profile)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "查询失败"
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy)
        }
        error?.let { ZxBanner(it, ZxTone.Err) }
        when (val data = result) {
            is MiniMaxProviderUsage.AccountBalance -> {
                ZxSection("账户金额")
                Text(data.available, fontSize = 24.sp,
                    color = ZhaoxiTheme.colors.ink)
                ZxValueRow("现金余额", value = data.cash)
                ZxValueRow("代金券", value = data.voucher)
                ZxValueRow("授信额度", value = data.credit)
            }
            is MiniMaxProviderUsage.TokenPlan -> {
                ZxSection("模型套餐")
                if (data.models.isEmpty()) ZxEmpty("暂无套餐数据", "当前账户没有返回可显示的模型窗口")
                data.models.forEach { model ->
                    ZxSection(model.name)
                    ZxValueRow("本周期", value = quotaWindowLabel(model.interval))
                    ZxValueRow("本周", value = quotaWindowLabel(model.weekly))
                    model.intervalResetMillis?.let { ZxValueRow("周期重置", value = quotaResetLabel(it)) }
                    model.weeklyResetMillis?.let { ZxValueRow("每周重置", value = quotaResetLabel(it)) }
                }
            }
            null -> if (profile != null && !busy && error == null) ZxFooterNote("点击刷新额度")
        }
    }
}

private fun quotaWindowLabel(value: MiniMaxProviderUsage.Window): String = when {
    value.notInPlan -> "不在套餐内"
    value.unlimited -> "无限额"
    value.remaining != null && value.total != null -> "${value.remaining} / ${value.total}"
    value.remainingPercent != null -> "${"%.1f".format(value.remainingPercent)}%"
    else -> "暂无可用数据"
}

private fun quotaResetLabel(timestampMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(timestampMillis))

/** Separate runtime limits from personal instructions; both use the current Settings Owner. */
@Composable
fun ZhaoxiRuntimeSettingsScene(
    settings: SettingsUiPort,
    scope: UiSceneRenderScope,
    sessionOnly: Boolean = false,
) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    var main by rememberSaveable(snap.mainMaxSteps) { mutableStateOf(snap.mainMaxSteps.toString()) }
    var sub by rememberSaveable(snap.subagentMaxSteps) { mutableStateOf(snap.subagentMaxSteps.toString()) }
    var attempts by rememberSaveable(snap.modelAttempts) { mutableStateOf(snap.modelAttempts.toString()) }
    var rules by rememberUiPrivateText("zhaoxi.rules", snap.userRules) { snap.userRules }
    var status by remember { mutableStateOf<String?>(null) }
    var showValidation by remember { mutableStateOf(false) }
    val mainError = if (showValidation) zxRuntimeLimitError(ZxRuntimeLimitField.MAIN_STEPS, main) else null
    val subError = if (showValidation) zxRuntimeLimitError(ZxRuntimeLimitField.SUBAGENT_STEPS, sub) else null
    val attemptError = if (showValidation) zxRuntimeLimitError(ZxRuntimeLimitField.MODEL_ATTEMPTS, attempts) else null
    ZxPage(if (sessionOnly) "个人规则" else "高级设置", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (sessionOnly) {
            ZxSection("对话规则")
            ZxField(rules, { rules = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = "输入希望助手遵循的规则")
            ZxButton("保存规则", {
                status = settings.submit(SettingsUiCommand.Personalization(
                    rules, snap.autoRecall, snap.autoMemory)).zxUserMessage("保存规则")
            }, Modifier.fillMaxWidth())
        } else {
            ZxSection("图片理解方式")
            ZxFooterNote("自动按当前模型能力选择；原生将图片交给模型，工具模式通过已配置的视觉工具处理。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalImageInputMode.entries.forEach { mode ->
                    val label = when (mode) {
                        LocalImageInputMode.AUTO -> "自动选择"
                        LocalImageInputMode.NATIVE -> "模型原生"
                        LocalImageInputMode.TOOL -> "视觉工具"
                    }
                    ZxChip(label, snap.imageInputMode == mode) {
                        status = settings.submit(SettingsUiCommand.ImageInputMode(mode)).zxUserMessage("图片理解设置")
                    }
                }
            }
            ZxSection("执行限制")
            ZxFooterNote("限制单次运行的步骤与尝试次数，不修改历史工作记录。")
            ZxSection("主任务最大步数（4–512）")
            ZxField(main, { main = it }, Modifier.fillMaxWidth(), placeholder = "主任务步数",
                error = mainError != null, errorMessage = mainError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            mainError?.let { Text(it, color = ZhaoxiTheme.colors.err, fontSize = 12.sp) }
            ZxSection("子任务最大步数（1–512）")
            ZxField(sub, { sub = it }, Modifier.fillMaxWidth(), placeholder = "子任务步数",
                error = subError != null, errorMessage = subError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            subError?.let { Text(it, color = ZhaoxiTheme.colors.err, fontSize = 12.sp) }
            ZxSection("模型尝试次数（1–5）")
            ZxField(attempts, { attempts = it }, Modifier.fillMaxWidth(), placeholder = "模型尝试次数",
                error = attemptError != null, errorMessage = attemptError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            attemptError?.let { Text(it, color = ZhaoxiTheme.colors.err, fontSize = 12.sp) }
            ZxButton("保存运行设置", {
                showValidation = true
                val fault = zxRuntimeLimitError(ZxRuntimeLimitField.MAIN_STEPS, main)
                    ?: zxRuntimeLimitError(ZxRuntimeLimitField.SUBAGENT_STEPS, sub)
                    ?: zxRuntimeLimitError(ZxRuntimeLimitField.MODEL_ATTEMPTS, attempts)
                status = fault ?: settings.submit(SettingsUiCommand.RuntimeLimits(
                    requireNotNull(main.toIntOrNull()),
                    requireNotNull(sub.toIntOrNull()),
                    requireNotNull(attempts.toIntOrNull()),
                    snap.modelSelection.workerProfileId)).zxUserMessage("保存运行设置")
            }, Modifier.fillMaxWidth())
        }
        status?.let { ZxFooterNote(it) }
    }
}

@Composable
fun ZhaoxiPerformanceScene(port: ModelPerformanceUiPort, scope: UiSceneRenderScope) {
    val snap by port.snapshots.collectAsState(initial = port.snapshot())
    var temperature by rememberSaveable(snap.temperatureCeiling) { mutableStateOf(snap.temperatureCeiling.toFloat().toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    var showTemperatureError by remember { mutableStateOf(false) }
    val temperatureError = if (showTemperatureError) zxTemperatureCeilingError(temperature) else null
    ZxPage("模型性能", onBack = { scope.onBack() }) {
        ZxSection("推理强度上限")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UiModelReasoningCeiling.entries.forEach { ceiling ->
                val label = when (ceiling) {
                    UiModelReasoningCeiling.CUSTOM -> "跟随会话"
                    UiModelReasoningCeiling.DEFAULT -> "默认"
                    UiModelReasoningCeiling.FAST -> "快速"
                    UiModelReasoningCeiling.LOW -> "轻量推理"
                    UiModelReasoningCeiling.DEEP -> "深入推理"
                    UiModelReasoningCeiling.MAX -> "最大上限"
                }
                ZxChip(label, ceiling == snap.reasoning) {
                    status = port.submit(ModelPerformanceUiCommand.SetReasoning(ceiling)).zxPerformanceMessage()
                }
            }
        }
        ZxSection("温度上限")
        ZxField(temperature, { temperature = it }, Modifier.fillMaxWidth(),
            placeholder = "1.0–2.0", error = temperatureError != null,
            errorMessage = temperatureError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        temperatureError?.let { Text(it, color = ZhaoxiTheme.colors.err, fontSize = 12.sp) }
        ZxButton("保存温度", {
            showTemperatureError = true
            status = zxTemperatureCeilingError(temperature)
                ?: port.submit(ModelPerformanceUiCommand.SetTemperature(
                    requireNotNull(temperature.toDoubleOrNull()))).zxPerformanceMessage()
        })
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
    }
}

@Composable
fun ZhaoxiPricingScene(port: PricingUiPort, scope: UiSceneRenderScope) {
    val snap by port.snapshots.collectAsState(initial = port.snapshot())
    val jobs = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    ZxPage("官方价格", onBack = { scope.onBack() }) {
        Text(snap.sourceUrl, color = ZhaoxiTheme.colors.ink2)
        snap.models.forEach { model ->
            ZxRow(model.displayName, "每百万 Token（元）")
            ZxValueRow("高峰", value = "缓存 ${model.peak.cacheHitCnyPerMillion} / 输入 ${model.peak.cacheMissCnyPerMillion} / 输出 ${model.peak.outputCnyPerMillion}")
            ZxValueRow("低峰", value = "缓存 ${model.offPeak.cacheHitCnyPerMillion} / 输入 ${model.offPeak.cacheMissCnyPerMillion} / 输出 ${model.offPeak.outputCnyPerMillion}")
        }
        (result ?: snap.error)?.let { ZxBanner(it, ZxTone.Info) }
        ZxButton("刷新官方价格", { jobs.launch {
            result = when (val receipt = port.refreshOfficial()) {
                PricingUiReceipt.Completed -> "价格已更新"
                is PricingUiReceipt.Failed -> receipt.message ?: "刷新失败"
            }
        } }, enabled = !snap.refreshing)
    }
}

@Composable
fun ZhaoxiStorageScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf<com.labteto.dshmobile.local.session.LocalSessionStorageStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmCompact by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { storage = settings.sessionStorageStatus() }
    val importBackup = rememberUiDocumentImporter(64 * 1024 * 1024) { result ->
        when (result) {
            UiDocumentPickReceipt.Reading -> { busy = true; status = "正在读取并验证备份…" }
            is UiDocumentPickReceipt.Picked -> jobs.launch {
                try {
                    val staged = settings.stageRestoreSessionStorage(result.document.bytes)
                    status = "已安全暂存 ${staged} 个缺失会话。原有会话保持不变；完全退出并重新启动应用后加载。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { status = failure.message ?: "备份校验失败，没有覆盖原有会话" }
                finally { busy = false }
            }
            UiDocumentPickReceipt.Cancelled -> { busy = false; status = "已取消备份恢复" }
            is UiDocumentPickReceipt.Failed -> { busy = false; status = result.message ?: "无法读取所选备份" }
        }
    }
    val export = rememberUiStreamDocumentSaver<Unit>("application/zip", { "777-sessions.zip" }, { _, output ->
        settings.exportSessionStorage(output); UiDocumentWriteReceipt.Saved
    }, { _, r -> status = if (r == UiDocumentWriteReceipt.Saved) "备份已保存" else "备份失败" }, { status = it.message })
    ZxPage("会话存储", onBack = { scope.onBack() }) {
        ZxSection("存储概览")
        storage?.let {
            ZxValueRow("已保存会话", value = "${it.sessionCount} 个")
            ZxValueRow("会话占用", value = zxHumanFileSize(it.totalBytes.toLong()))
            ZxValueRow("设备剩余空间", value = zxHumanFileSize(it.freeBytes.toLong()))
        }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxSection("维护与备份")
        ZxFooterNote("压缩会整理本地会话文件。建议先导出备份，维护期间不要退出应用。")
        ZxButton("导出会话备份", { export(Unit) }, enabled = !busy)
        ZxButton("恢复缺失的会话", { confirmRestore = true }, enabled = !busy,
            variant = ZxButtonVariant.Outline)
        if (confirmRestore) ZxDialog(
            "从备份恢复会话？",
            "仅导入本机缺失的历史会话，绝不覆盖或合并已有会话。ZIP 不能超过 64 MiB；恢复内容会先安全校验并隔离暂存，下次完全重启应用后才生效。请仅选择信任来源的备份。",
            "选择备份 ZIP", {
                confirmRestore = false
                importBackup()
            }, { confirmRestore = false })
        ZxButton("压缩会话存储", { confirmCompact = true }, enabled = !busy,
            variant = ZxButtonVariant.Outline)
        if (confirmCompact) ZxDialog(
            "压缩会话存储？", "会整理本地会话文件，执行期间请保持应用打开；此操作不是删除全部会话。",
            "确认压缩", {
                confirmCompact = false
                jobs.launch {
                    busy = true
                    try { storage = settings.compactSessionStorage(); status = "压缩完成，空间统计已刷新" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { status = failure.message ?: "压缩失败，请检查存储空间" }
                    finally { busy = false }
                }
            }, { confirmCompact = false })
        if (busy) ZxFooterNote("正在整理会话数据…")
    }
}

/** Scope-qualified search is resolved by the Memory Owner before any UI pagination. */
internal fun zxScopedMemoryQuery(
    scope: MemoryScope,
    context: com.labteto.dshmobile.local.presentation.LocalSettingsMemoryContext?,
    text: String,
    kind: MemoryKind?,
    cursor: MemoryManagementCursor?,
): MemoryManagementQuery {
    require(scope != MemoryScope.LINEAGE || !context?.lineageId.isNullOrBlank()) {
        "当前没有可访问的会话线记忆"
    }
    require(scope != MemoryScope.PROJECT || !context?.projectId.isNullOrBlank()) {
        "当前没有可访问的项目记忆"
    }
    return MemoryManagementQuery(
        scopes = setOf(scope), text = text,
        lineageId = if (scope == MemoryScope.LINEAGE) context?.lineageId else null,
        projectId = if (scope == MemoryScope.PROJECT) context?.projectId else null,
        kinds = kind?.let(::setOf) ?: MemoryKind.entries.toSet(),
        cursor = cursor,
    )
}

@Composable
fun ZhaoxiMemoryManagementScene(memory: MemoryUiPort, scope: UiSceneRenderScope,
    settings: SettingsUiPort? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var kindFilter by rememberSaveable { mutableStateOf<MemoryKind?>(null) }
    var scopeFilter by rememberSaveable { mutableStateOf(MemoryScope.GLOBAL) }
    val context = remember(settings, revision) { settings?.memoryContext() }
    val available = listOfNotNull(
        MemoryScope.GLOBAL,
        MemoryScope.LINEAGE.takeIf { !context?.lineageId.isNullOrBlank() },
        MemoryScope.PROJECT.takeIf { !context?.projectId.isNullOrBlank() },
    )
    val selectedScope = scopeFilter.takeIf { it in available } ?: MemoryScope.GLOBAL
    var cursor by remember(query, kindFilter, selectedScope) { mutableStateOf<MemoryManagementCursor?>(null) }
    val page = remember(memory, query, selectedScope, kindFilter, revision, cursor, context) {
        memory.memoryPage(zxScopedMemoryQuery(
            selectedScope, context, query, kindFilter, cursor))
    }
    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    var editingDirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    fun changeFilter(change: () -> Unit) {
        if (editingDirty) status = "请先保存或关闭正在编辑的记忆"
        else { change(); cursor = null; selectedRecordId = null }
    }
    var pendingForget by remember { mutableStateOf<MemoryRecord?>(null) }
    fun applyReceipt(receipt: MemoryUiReceipt) {
        status = when (receipt) {
            is MemoryUiReceipt.Updated -> "已保存"
            MemoryUiReceipt.Forgotten -> "已遗忘"
            MemoryUiReceipt.StaleOrMissing -> "记录已变化，请重新加载"
            is MemoryUiReceipt.CommittedWithWarning -> receipt.message
            is MemoryUiReceipt.Failed -> receipt.message ?: "操作失败"
        }
        if (receipt is MemoryUiReceipt.Updated ||
            receipt == MemoryUiReceipt.Forgotten ||
            receipt is MemoryUiReceipt.CommittedWithWarning) {
            revision++; cursor = null; selectedRecordId = null; editingDirty = false
        }
    }
    BackHandler(enabled = editingDirty) { status = "请先保存或关闭正在编辑的记忆" }
    ZxPage("记忆管理", onBack = {
        if (editingDirty) status = "请先保存或关闭正在编辑的记忆"
        else scope.onBack()
    }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("可见范围")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { target ->
                ZxChip(when (target) {
                    MemoryScope.GLOBAL -> "全局"
                    MemoryScope.LINEAGE -> "当前会话线"
                    MemoryScope.PROJECT -> "当前项目"
                }, selectedScope == target) { changeFilter { scopeFilter = target } }
            }
        }
        ZxField(query, { value -> changeFilter { query = value } },
            Modifier.fillMaxWidth(), placeholder = "搜索所有当前可见记忆")
        ZxSection("按类型")
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("全部", kindFilter == null) { changeFilter { kindFilter = null } }
            MemoryKind.entries.forEach { kind ->
                ZxChip(zxMemoryKindTitle(kind), kindFilter == kind) { changeFilter { kindFilter = kind } }
            }
        }
        ZxValueRow("符合条件", value = "${page.totalMatching} 条")
        if (kindFilter == null) page.kindCounts.filterValues { it > 0 }.forEach { (kind, count) ->
            ZxValueRow(zxMemoryKindTitle(kind), value = count.toString())
        }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (page.records.isEmpty()) ZxEmpty("没有匹配的记忆", "可以调整搜索条件")
        page.records.forEach { record ->
            val opened = selectedRecordId == record.id
            ZxRow(zxMemoryKindTitle(record.kind), record.content.take(95) +
                if (record.pinned) " · 已置顶" else "", onClick = {
                    if (editingDirty && !opened) status = "请先保存当前编辑"
                    else { selectedRecordId = if (opened) null else record.id; editingDirty = false }
                }, trailing = {
                    ZxChip(if (opened) "收起" else "详情", opened) {
                        if (editingDirty && !opened) status = "请先保存当前编辑"
                        else { selectedRecordId = if (opened) null else record.id; editingDirty = false }
                    }
                })
            if (opened) {
                var text by rememberUiPrivateText("zhaoxi.memory.${record.id}", record.updatedAt) { record.content }
                ZxSettingsGroup {
                    ZxValueRow("记忆类型", value = zxMemoryKindTitle(record.kind))
                    ZxValueRow("披露范围", value = record.disclosure.name)
                    ZxField(text, { text = it; editingDirty = it != record.content },
                        Modifier.fillMaxWidth().heightIn(min = 112.dp))
                    ZxSwitchRow("置顶", "只改变这条记忆的置顶状态", record.pinned) { checked ->
                        if (editingDirty) status = "请先保存正在编辑的正文"
                        else applyReceipt(memory.submit(
                            MemoryUiCommand.Update(record, record.content, checked)))
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxButton("保存修改", {
                            if (text.isBlank()) status = "请填写正文；删除请使用遗忘"
                            else applyReceipt(memory.submit(
                                MemoryUiCommand.Update(record, text, record.pinned)))
                        }, Modifier.weight(1f), enabled = editingDirty && text.isNotBlank())
                        ZxButton("遗忘", { pendingForget = record }, Modifier.weight(1f),
                            ZxButtonVariant.Danger)
                    }
                    ZxButton("关闭编辑", { selectedRecordId = null; editingDirty = false },
                        variant = ZxButtonVariant.Outline)
                }
            }
        }
        page.next?.let { next -> ZxButton("下一页", {
            if (editingDirty) status = "请先保存当前编辑"
            else { cursor = next; selectedRecordId = null }
        }) }
        if (cursor != null) ZxButton("回到首页", {
            if (editingDirty) status = "请先保存当前编辑"
            else { cursor = null; selectedRecordId = null }
        })
        ZxFooterNote("先检索全部有权查看的活跃记录，再分页；已遗忘的记录不会在当前页面恢复。")
    }
    pendingForget?.let { record ->
        ZxDialog("遗忘这条记忆？", "删除后无法直接恢复。", "遗忘", {
            pendingForget = null
            applyReceipt(memory.submit(MemoryUiCommand.Forget(record)))
        }, { pendingForget = null })
    }
}

/** Diagnostics is a distinct E8.18 page, backed by Settings Owner's real read operations.
 * Reports are not generated in the background or posted to external services. */
@Composable
fun ZhaoxiDiagnosticsSettingsScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val co = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var reportKind by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var networkTarget by rememberSaveable { mutableStateOf("") }
    var confirmExport by remember { mutableStateOf(false) }
    val saveDiagnostic = rememberUiStreamDocumentSaver<Unit>(
        "text/plain", { "777-diagnostic.txt" },
        { _, output ->
            // Generate the full report from the owner only after the user picks a destination.
            val content = settings.diagnosticReport()
            output.write(content.toByteArray(Charsets.UTF_8))
            UiDocumentWriteReceipt.Saved
        },
        { _, result ->
            feedback = when (result) {
                UiDocumentWriteReceipt.Saved -> "诊断报告已保存到用户选择的位置"
                UiDocumentWriteReceipt.Cancelled -> "已取消报告导出"
                is UiDocumentWriteReceipt.Failed -> result.message ?: "诊断报告导出失败"
            }
        },
        { failure -> feedback = failure.message }
    )
    fun read(label: String, action: suspend () -> String) {
        if (loading) return
        loading = true
        feedback = null
        co.launch {
            try {
                val result = action()
                reportKind = label
                report = result.take(40000)
                feedback = if (result.length > 40000)
                    "原始报告较长，页面仅展示前 40000 字；诊断未上传" else "读取完成 · 诊断未上传"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "诊断失败，请核实当前状态" }
            finally { loading = false }
        }
    }
    ZxPage("诊断与网络", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("设备与运行环境")
        ZxFooterNote("按需读取真实设备和应用环境；结果只在当前页面显示，请注意其中可能包含本地路径等信息。")
        ZxButton("读取环境信息", { read("环境信息") { settings.environmentInfo() } },
            Modifier.fillMaxWidth(), enabled = !loading)
        ZxSection("诊断报告")
        ZxFooterNote("报告可能包含模型与应用状态，请自行核对后再决定是否分享。")
        ZxButton("生成本地诊断报告", { read("诊断报告") { settings.diagnosticReport() } },
            Modifier.fillMaxWidth(), enabled = !loading, variant = ZxButtonVariant.Outline)
        ZxButton("导出诊断报告", { confirmExport = true },
            Modifier.fillMaxWidth(), enabled = !loading, variant = ZxButtonVariant.Outline)
        if (confirmExport) ZxDialog(
            "导出诊断报告？",
            "报告可能包含本机路径、配置及运行信息。确认后需要你在 Android 文件选择器手动选择保存位置；应用不会自动发送给第三方。",
            "选择保存位置", {
                confirmExport = false
                saveDiagnostic(Unit)
            }, { confirmExport = false })
        ZxSection("网络诊断")
        ZxField(networkTarget, { networkTarget = it }, Modifier.fillMaxWidth(),
            placeholder = "目标地址（例如 api.example.com）")
        ZxButton("检查目标连接", {
            val target = networkTarget.trim()
            read("网络诊断 · $target") { settings.diagnoseNetwork(target) }
        }, Modifier.fillMaxWidth(), enabled = !loading && networkTarget.isNotBlank(),
            variant = ZxButtonVariant.Outline)
        if (loading) ZxFooterNote("正在读取 Owner 诊断状态…")
        feedback?.let { ZxFooterNote(it) }
        if (report != null) {
            ZxSection(reportKind ?: "诊断结果")
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(report.orEmpty(), color = ZhaoxiTheme.colors.ink,
                    fontSize = 12.sp, lineHeight = 19.sp)
            }
            ZxButton("清除屏幕上的诊断结果", {
                report = null
                reportKind = null
                feedback = null
            }, variant = ZxButtonVariant.Outline)
        }
        ZxFooterNote("网络检查会向你输入的目标发起连接诊断。诊断不会自动提交故障单。")
    }
}
