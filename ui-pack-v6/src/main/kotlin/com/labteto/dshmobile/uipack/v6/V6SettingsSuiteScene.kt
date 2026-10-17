package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dedicated V6 Settings sections; all reads/writes delegate to the existing Settings/Usage Owners. */
@Composable
fun V6SettingsSuiteScene(
    scene: UiSceneId,
    settings: SettingsUiPort,
    usage: UsageUiPort?,
    onNavigate: (UiSceneId) -> UiSceneNavigationResult,
    onBack: () -> Unit,
    updateStatus: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    checkForUpdate: (() -> Unit)? = null,
    performance: ModelPerformanceUiPort? = null,
    providerPricing: PricingUiPort? = null,
) {
    val state by settings.state.collectAsStateWithLifecycle(initialValue = settings.initialState)
    val scope = rememberCoroutineScope()
    var feedback by remember(scene) { mutableStateOf<String?>(null) }
    var output by remember(scene) { mutableStateOf<String?>(null) }
    var busy by remember(scene) { mutableStateOf(false) }
    var confirmCompact by remember(scene) { mutableStateOf(false) }
    var selectedModel by remember(scene) { mutableStateOf<String?>(null) }
    var usageModelFilter by remember(scene) { mutableStateOf<String?>(null) }
    var selectedRequestId by remember(scene) { mutableStateOf<String?>(null) }
    var modelName by remember(scene) { mutableStateOf("") }
    var modelUrl by remember(scene) { mutableStateOf("") }
    // A secret must never enter SavedState, settings navigation or a retained draft.
    var modelKey by remember(scene) { mutableStateOf("") }
    var rules by remember(scene) { mutableStateOf("") }
    var recall by remember(scene) { mutableStateOf(true) }
    var autoMemory by remember(scene) { mutableStateOf(true) }
    var mainSteps by remember(scene) { mutableStateOf("") }
    var subSteps by remember(scene) { mutableStateOf("") }
    var attempts by remember(scene) { mutableStateOf("") }
    var diagnosticHost by remember(scene) { mutableStateOf("") }
    LaunchedEffect(scene, state.loading) {
        if (!state.loading) {
            rules = state.userRules
            recall = state.autoRecall
            autoMemory = state.autoMemory
            mainSteps = state.mainMaxSteps.toString()
            subSteps = state.subagentMaxSteps.toString()
            attempts = state.modelAttempts.toString()
        }
    }
    fun submit(command: SettingsUiCommand) {
        if (busy || state.loading) return
        feedback = when (val result = settings.submit(command)) {
            SettingsUiReceipt.Applied -> "设置已更新"
            SettingsUiReceipt.Submitted -> "已提交，正在保存设置"
            SettingsUiReceipt.Rejected -> "请求被拒绝，设置未更新"
            is SettingsUiReceipt.Failed -> result.message ?: "设置修改失败"
        }
    }
    fun read(task: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { output = withContext(Dispatchers.IO) { task() }; feedback = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "无法读取数据" }
            finally { busy = false }
        }
    }
    val name = when(scene.value) {
        "route.settings","settings.root" -> "设置中心"
        "settings.models" -> "模型管理"
        "conversation.model_picker" -> "选择模型"
        "settings.model_performance" -> "模型性能与推理"
        "settings.pricing" -> "费用估算"
        "settings.usage","settings.model_usage" -> "模型额度与用量"
        "settings.usage_log" -> "请求日志"
        "settings.usage_detail" -> "请求明细"
        "settings.memory","settings.memory_settings" -> "记忆与个性化规则"
        "settings.advanced" -> "高级运行参数"
        "settings.session_storage" -> "会话数据存储"
        "settings.diagnostics" -> "诊断"
        "settings.minimax_quota" -> "MiniMax 官方额度"
        "settings.account" -> "ChatGPT 账户"
        "settings.update" -> "检查更新"
        else -> scene.value
    }
    // Performance has its own scrollable layout. Nesting it inside this page's scroll
    // would give an unbounded height and crash Compose when navigating the scene.
    if (scene.value == "settings.model_performance" && performance != null) {
        V6ModelPerformanceScene(performance, onBack)
        return
    }
    if (scene.value == "settings.pricing" && providerPricing != null) {
        V6ProviderPricingScene(providerPricing, onBack)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        if (state.loading) SyNote("正在加载设置")
        state.error?.let { SyNote(it) }
        feedback?.let { SyNote(it) }
        when(scene.value) {
            "route.settings","settings.root" -> {
                v6SettingsGroups.forEach { group ->
                    V6DestinationSection(group) { entry ->
                        if (entry.id == "settings.update") {
                            // The retired standalone update page is a SLOT, not a Host navigation target.
                            // Execute the real Host update action in place instead of navigating to a dead end.
                            if (checkForUpdate != null) {
                                checkForUpdate()
                                feedback = "已请求检查更新，请查看下方结果"
                            } else {
                                feedback = "当前无法检查更新"
                            }
                        } else {
                            val result = onNavigate(UiSceneId(entry.id))
                            if (result !is UiSceneNavigationResult.Changed)
                                feedback = "当前无法进入：" + entry.title
                        }
                    }
                }
                if (checkForUpdate != null) {
                    val updateResult by updateStatus?.collectAsStateWithLifecycle()
                        ?: remember { mutableStateOf<String?>(null) }
                    updateResult?.let { SyNote(it) }
                }
            }
            "settings.models","conversation.model_picker" -> {
                val picking = scene.value == "conversation.model_picker"
                Text("当前模型：" + state.model)
                state.modelProfiles.forEach { profile ->
                    SyGlassCard(Modifier.fillMaxWidth()) {
                        Text(profile.model + " · " + profile.provider)
                        SyButton(if (state.modelSelection.activeProfileId == profile.id) "当前模型" else "选择模型", {
                            submit(SettingsUiCommand.SelectModel(profile.id))
                        }, enabled = !state.loading && !busy &&
                            state.modelSelection.activeProfileId != profile.id)
                        if (!picking) SyButton("删除该配置", { selectedModel = profile.id },
                            enabled = !state.loading && !busy, style = SyButtonStyle.GHOST)
                    }
                }
                if (picking) SyButton("管理模型配置", {
                    if (onNavigate(UiSceneId("settings.models")) !is UiSceneNavigationResult.Changed)
                        feedback = "模型管理暂时无法打开"
                }, style = SyButtonStyle.GHOST)
                if (!picking) {
                    SyInput(modelName, { modelName = it }, label = "模型 ID")
                    SyInput(modelUrl, { modelUrl = it }, label = "API 基础地址")
                    androidx.compose.material3.OutlinedTextField(
                        modelKey, { modelKey = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key（仅临时输入）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    SyButton("保存模型配置", onClick = {
                        if (busy) return@SyButton
                        val secret = modelKey
                        modelKey = ""
                        busy = true
                        scope.launch {
                            try {
                                feedback = when (val result = settings.save(
                                    SettingsUiCommand.SaveModel(secret, modelName.trim(), modelUrl.trim()))) {
                                    SettingsUiReceipt.Applied -> "模型配置已保存"
                                    SettingsUiReceipt.Submitted -> "模型配置已提交"
                                    SettingsUiReceipt.Rejected -> "模型配置未被接受"
                                    is SettingsUiReceipt.Failed -> result.message ?: "保存失败"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { feedback = failure.message ?: "保存模型失败" }
                            finally { busy = false }
                        }
                    }, enabled = !busy && !state.loading &&
                        modelName.isNotBlank() && modelUrl.isNotBlank())
                }
            }
            "settings.model_performance" ->
                SyNote("模型性能设置暂不可用，请稍后再试")
            "settings.pricing" ->
                SyNote("官方价格暂不可用，请稍后刷新")
            "settings.minimax_quota" -> {
                val profile = state.miniMaxQuotaProfile
                if (profile == null) SyNote("当前无可查询的 MiniMax 模型账户")
                else {
                    Text("读取所配置账户的真实供应商额度，不从请求日志推算。")
                    SyButton("向 MiniMax 查询额度", {
                        read { settings.queryMiniMaxQuota(profile).toString() }
                    }, enabled = !busy)
                }
            }
            "settings.memory","settings.memory_settings" -> {
                SyInput(rules, { rules = it }, label = "个人规则", singleLine = false)
                SySwitch("自动调用记忆", recall, { recall = it })
                SySwitch("自动保存记忆", autoMemory, { autoMemory = it })
                SyButton("保存记忆与个性化", {
                    submit(SettingsUiCommand.Personalization(rules, recall, autoMemory))
                }, enabled = !state.loading && !busy)
                SyButton("管理现有记忆", {
                    val result = onNavigate(UiSceneId("settings.memory_management"))
                    if (result !is UiSceneNavigationResult.Changed) feedback = "记忆管理暂不可达"
                }, style = SyButtonStyle.SECONDARY)
            }
            "settings.advanced" -> {
                SyInput(mainSteps, { mainSteps = it.filter(Char::isDigit).take(5) },
                    label = "主智能体步骤上限")
                SyInput(subSteps, { subSteps = it.filter(Char::isDigit).take(5) },
                    label = "子智能体步骤上限")
                SyInput(attempts, { attempts = it.filter(Char::isDigit).take(3) },
                    label = "请求重试上限")
                val a = mainSteps.toIntOrNull()
                val b = subSteps.toIntOrNull()
                val c = attempts.toIntOrNull()
                SyButton("保存运行限制", {
                    if (a != null && b != null && c != null)
                        submit(SettingsUiCommand.RuntimeLimits(a,b,c,state.modelSelection.workerProfileId))
                }, enabled = !busy && !state.loading && a != null && b != null && c != null &&
                    a in 1..10000 && b in 1..10000 && c in 1..100)
            }
            "settings.usage","settings.model_usage","settings.usage_log",
            "settings.usage_detail" -> {
                if (state.miniMaxQuotaProfile != null) SyButton("MiniMax 账户额度", {
                    if (onNavigate(UiSceneId("settings.minimax_quota")) !is UiSceneNavigationResult.Changed)
                        feedback = "账户额度暂时无法打开"
                }, style = SyButtonStyle.GHOST)
                SyButton("费用估算", {
                    if (onNavigate(UiSceneId("settings.pricing")) !is UiSceneNavigationResult.Changed)
                        feedback = "费用估算暂时无法打开"
                }, style = SyButtonStyle.GHOST)
                val owner = usage
                if (owner == null) SyNote("请求记录暂不可用")
                else {
                    val revision by owner.revision.collectAsStateWithLifecycle()
                    val figures = remember(owner, revision) { owner.snapshot() }
                    val selectedFigures = usageModelFilter?.let { figures.byModel[it] }
                        ?: figures.takeIf { usageModelFilter == null }
                    if (selectedFigures == null) SyNote("所选模型暂无本地用量记录")
                    val active = selectedFigures ?: com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot()
                    Text("当前账本：" + (usageModelFilter ?: "全部模型"))
                    Text("请求：${active.tracked.requestCount}")
                    Text("输入 Token：${active.tracked.inputTokens}")
                    Text("输出 Token：${active.tracked.outputTokens}")
                    Text("台账预估成本（人民币）：${active.tracked.estimatedCostCny}")
                    if (active.tracked.unreportedRequestCount > 0)
                        SyNote("部分请求缺少供应商用量回执，费用可能不完整")
                    Text("聊天请求：${active.chat.aggregate.requestCount}；Work 请求：${active.work.aggregate.requestCount}")
                    Text("费用与额度以服务商账单为准；这里仅显示本地请求账本。")
                    if (figures.byModel.isNotEmpty()) {
                        Text("切换账本模型", style = MaterialTheme.typography.titleMedium)
                        SyButton("所有已配置模型", { usageModelFilter = null },
                            style = if (usageModelFilter == null) SyButtonStyle.PRIMARY
                                else SyButtonStyle.GHOST)
                        figures.byModel.keys.sorted().forEach { model ->
                            SyButton(model, { usageModelFilter = model; selectedRequestId = null },
                                style = if (usageModelFilter == model) SyButtonStyle.PRIMARY
                                    else SyButtonStyle.SECONDARY)
                        }
                    }
                    figures.byModel.filterKeys {
                        usageModelFilter == null || it == usageModelFilter
                    }.forEach { (key, value) ->
                        SyGlassCard(Modifier.fillMaxWidth()) {
                            Text(key)
                            Text("调用数：${value.tracked.requestCount} · Tokens：${value.tracked.inputTokens + value.tracked.outputTokens}")
                        }
                    }
                    if (scene.value in setOf("settings.usage_log","settings.usage_detail",
                            "settings.model_usage")) {
                        val records = figures.recentRecords.filter {
                            usageModelFilter == null || it.model == usageModelFilter
                        }
                        Text("当前列表：" + records.size + " 条（最多显示前 80 条）")
                        records.take(80).forEach { record ->
                            SyGlassCard(Modifier.fillMaxWidth()) {
                                Text(record.model + " · " + record.context.action)
                                Text("请求 ID：" + record.requestId)
                                Text("已报告 Tokens：" + record.totalTokens.toString())
                                SyButton("查看此请求明细", {
                                    selectedRequestId = record.requestId
                                }, style = SyButtonStyle.SECONDARY)
                            }
                        }
                        selectedRequestId?.let { id ->
                            val detail = remember(owner, revision, id) {
                                owner.requestDetail(id)
                            }
                            SyGlassCard(Modifier.fillMaxWidth()) {
                                Text("请求明细：" + id,
                                    style = MaterialTheme.typography.titleMedium)
                                if (detail == null)
                                    SyNote("该请求已不在当前账本或已过期")
                                else {
                                    Text("模型：" + detail.model)
                                    Text("输入 Tokens：" + detail.inputTokens)
                                    Text("输出 Tokens：" + detail.outputTokens)
                                    Text("合计 Tokens：" + detail.totalTokens)
                                    Text("操作：" + detail.context.action)
                                }
                                SyButton("关闭明细", { selectedRequestId = null },
                                    style = SyButtonStyle.GHOST)
                            }
                        }
                    }
                }
            }
            "settings.session_storage" -> {
                SyNote("整理会话存储，保留已有消息记录。")
                SyButton("读取当前存储状态", {
                    read { settings.sessionStorageStatus().toString() }
                }, enabled = !busy)
                SyButton("整理会话存储", { confirmCompact = true },
                    style = SyButtonStyle.SECONDARY, enabled = !busy)
            }
            "settings.diagnostics" -> {
                SyButton("运行环境", { read { settings.environmentInfo() } }, enabled = !busy)
                SyButton("生成诊断报告", { read { settings.diagnosticReport() } },
                    enabled = !busy, style = SyButtonStyle.SECONDARY)
                SyInput(diagnosticHost, { diagnosticHost = it.take(256) },
                    label = "诊断目标地址")
                SyButton("检查目标网络连接", {
                    read { settings.diagnoseNetwork(diagnosticHost.trim()) }
                }, enabled = !busy && diagnosticHost.isNotBlank(),
                    style = SyButtonStyle.SECONDARY)
            }
            "settings.account" -> V6AccountManagementSection(settings)
            "settings.update" -> {
                val actual by updateStatus?.collectAsStateWithLifecycle()
                    ?: remember { mutableStateOf<String?>(null) }
                Text(actual ?: "尚未查询版本更新")
                if (checkForUpdate != null)
                    SyButton("检查更新", onClick = checkForUpdate)
            }
        }
        output?.let { SelectionContainer { Text(it) } }
    }
    selectedModel?.let { id ->
        AlertDialog(onDismissRequest = { selectedModel = null },
            title = { Text("确认删除模型配置") }, text = { Text("仅删除已选择的配置：" + id) },
            confirmButton = { TextButton(onClick = {
                selectedModel = null
                submit(SettingsUiCommand.RemoveModel(id))
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { selectedModel = null }) { Text("取消") } })
    }
    if (confirmCompact) AlertDialog(onDismissRequest = { confirmCompact = false },
        title = { Text("确认整理本地会话存储") },
        text = { Text("将整理会话存储，建议先备份重要数据。") },
        confirmButton = { TextButton(onClick = {
            confirmCompact = false
            read { settings.compactSessionStorage().toString() }
        }) { Text("确认整理") } },
        dismissButton = { TextButton(onClick = { confirmCompact = false }) { Text("取消") } })
}

@Composable
private fun V6AccountManagementSection(settings: SettingsUiPort) {
    val state by settings.chatGptState.collectAsStateWithLifecycle()
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun invoke(task: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            try { feedback = task() ?: "请求已提交，正在更新账户状态" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "账户操作失败" }
            finally { busy = false }
        }
    }
    Text("账户阶段：" + state.phase)
    state.error?.let { SyNote(it) }
    feedback?.let { SyNote(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SyButton("授权连接", { invoke { settings.connectChatGpt(requestPlanConsent = true); null } },
            enabled = !busy)
        SyButton("刷新", { invoke { settings.refreshChatGpt(); null } },
            enabled = !busy, style = SyButtonStyle.SECONDARY)
    }
    state.accounts.forEach { account ->
        SyGlassCard(Modifier.fillMaxWidth()) {
            Text(account.displayName ?: account.email ?: "已保存账户")
            Text(if (account.signedIn) "已登录" else "需要授权")
            Text(if (account.sharingEnabled) "套餐调用已获同意" else "套餐调用未获同意")
            if (state.selectedAccountId == account.id) Text("当前账户")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SyButton("选择", { invoke { settings.selectChatGptAccount(account.id); null } },
                    enabled = !busy, style = SyButtonStyle.SECONDARY)
                SyButton("测试", { invoke { settings.testChatGptAccount(account.id) } },
                    enabled = !busy, style = SyButtonStyle.SECONDARY)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SyButton("重新授权", { invoke {
                    settings.restartChatGptAuthorization(account.id); null
                } }, enabled = !busy, style = SyButtonStyle.GHOST)
                SyButton("断开", { invoke { settings.disconnectChatGptAccount(account.id) } },
                    enabled = !busy, style = SyButtonStyle.GHOST)
                SyButton("移除", { removal = account.id }, enabled = !busy,
                    style = SyButtonStyle.DANGER)
            }
        }
    }
    removal?.let { id -> AlertDialog(onDismissRequest = { removal = null },
        title = { Text("从本机移除账户？") },
        text = { Text("只移除对应账户授权，不影响其他账户。") },
        confirmButton = { TextButton(onClick = {
            removal = null
            invoke { settings.removeChatGptAccount(id) }
        }) { Text("确认移除") } },
        dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } })
    }
}
