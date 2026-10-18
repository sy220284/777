package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoveModel by remember { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("https://api.deepseek.com") }
    var protocol by rememberSaveable { mutableStateOf(LocalModelProtocol.CHAT_COMPLETIONS) }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>?>(null) }
    var catalogFilter by remember { mutableStateOf("") }
    var catalogFailure by remember { mutableStateOf<String?>(null) }
    fun execute(action: suspend () -> String) {
        if (busy) return
        jobs.launch {
            busy = true
            try { status = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    ZxPage("模型", onBack = { scope.onBack() }) {
        snap.error?.let { ZxBanner(it, ZxTone.Err) }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxSection("已配置模型 · ${snap.modelProfiles.size}")
        if (snap.modelProfiles.isEmpty()) ZxEmpty("还没有模型", "填写下方表单添加可用模型")
        snap.modelProfiles.sortedByDescending { snap.modelSelection.isActive(it) }.forEach { p ->
            val active = snap.modelSelection.isActive(p)
            ZxRow(p.displayName ?: p.model,
                if (active) "当前使用 · ${p.model}" else p.model,
                onClick = { expandedModelId = if (expandedModelId == p.id) null else p.id })
            if (expandedModelId == p.id) {
                ZxValueRow("接口", value = p.baseUrl)
                ZxValueRow("协议", value = p.protocol.name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!active) ZxChip("使用此模型", false) {
                        status = settings.submit(SettingsUiCommand.SelectModel(p.id)).zxUserMessage("切换模型")
                    }
                    ZxChip("编辑", false) {
                        profileId = p.id; model = p.model; baseUrl = p.baseUrl
                        protocol = p.protocol; apiKey = ""
                    }
                    ZxChip("移除", false) { pendingRemoveModel = p.id }
                }
            }
        }
        pendingRemoveModel?.let { id ->
            ZxDialog("移除模型？", "此操作会删除本地模型配置。", "移除", {
                pendingRemoveModel = null
                status = when (val receipt = settings.submit(SettingsUiCommand.RemoveModel(id))) {
                    SettingsUiReceipt.Applied -> "模型已移除"
                    SettingsUiReceipt.Submitted -> "移除已提交"
                    SettingsUiReceipt.Rejected -> "当前无法移除"
                    is SettingsUiReceipt.Failed -> receipt.message ?: "移除失败"
                }
                if (profileId == id) profileId = null
                if (expandedModelId == id) expandedModelId = null
            }, { pendingRemoveModel = null })
        }
        ZxSection(if (profileId == null) "添加模型" else "编辑模型")
        ZxSection("模型名称")
        ZxField(model, { model = it }, Modifier.fillMaxWidth(), placeholder = "模型名称（支持手动输入）",
            fieldLabel = "模型名称")
        ZxFooterNote("可使用供应商目录选择模型，也可手动填写目录里没有的自定义模型。")
        ZxSection("接口地址")
        ZxField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), placeholder = "接口地址")
        ZxSection("访问凭据")
        ZxField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), placeholder = "API Key（编辑时留空保留原凭据）",
            visualTransformation = PasswordVisualTransformation())
        ZxFooterNote(if (profileId == null) "凭据仅用于连接该模型" else
            "不填写密钥时保留原凭据；不会在界面中回显已有密钥")
        LocalModelProtocol.entries.forEach { p -> ZxChip(p.name, p == protocol) {
            protocol = p
            discoveredModels = null
            catalogFailure = null
        } }
        ZxSection("从提供商读取可用模型")
        ZxButton("读取 /models 目录", {
            if (!busy) {
                val oldAddress = baseUrl.trim()
                val oldProtocol = protocol
                val oldProfile = profileId
                val oldKey = apiKey
                busy = true
                catalogFailure = null
                discoveredModels = null
                jobs.launch {
                    try {
                        val names = settings.discoverApiModels(oldKey, oldAddress, oldProtocol, oldProfile)
                            .map(String::trim).filter(String::isNotBlank).distinct().sorted()
                        if (oldAddress != baseUrl.trim() || oldProtocol != protocol ||
                            oldProfile != profileId || oldKey != apiKey) {
                            catalogFailure = "连接配置已变化，请重新查询模型列表"
                        } else {
                            discoveredModels = names
                            status = if (names.isEmpty()) "目录没有返回模型，可以继续手动填写" else
                                "已读取 ${names.size} 个可用模型，请选择后保存"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        catalogFailure = failure.message ?: "读取模型目录失败"
                        status = "目录不可用，不影响手动填写模型"
                    } finally { busy = false }
                }
            }
        }, enabled = !busy && baseUrl.isNotBlank() && (apiKey.isNotBlank() || profileId != null),
            variant = ZxButtonVariant.Outline, loading = busy)
        catalogFailure?.let { ZxFooterNote("目录读取失败：$it。可手动填写模型名称并测试。") }
        discoveredModels?.let { names ->
            if (names.isEmpty()) ZxFooterNote("当前目录为空，手动配置仍然可用")
            else {
                ZxField(catalogFilter, { catalogFilter = it }, Modifier.fillMaxWidth(),
                    placeholder = "筛选可用模型")
                names.filter { it.contains(catalogFilter.trim(), ignoreCase = true) }
                    .take(60).forEach { available ->
                        ZxRow(available, "选用此模型标识", onClick = {
                            model = available
                            status = "已选择模型标识，还需要点击保存模型"
                        }, trailing = {
                            ZxChip(if (model == available) "已选择" else "选择", model == available) {
                                model = available
                            }
                        })
                    }
                if (names.size > 60) ZxFooterNote("列表较长，使用上方搜索框快速定位模型")
            }
        }
        ZxButton("测试连接", { execute { settings.testModel(apiKey, model, baseUrl, protocol, profileId) } }, enabled = !busy && model.isNotBlank())
        ZxButton("保存模型", { execute {
            when (val result = settings.save(SettingsUiCommand.SaveModel(apiKey, model, baseUrl, protocol, profileId))) {
                SettingsUiReceipt.Applied -> { apiKey = ""; profileId = null; "模型已保存" }
                SettingsUiReceipt.Submitted -> "保存已提交"
                SettingsUiReceipt.Rejected -> "无法保存模型"
                is SettingsUiReceipt.Failed -> result.message ?: "保存失败"
            }
        } }, enabled = !busy && model.isNotBlank())
        ZxChip("新建配置", false) {
            profileId = null; model = ""; apiKey = ""; status = null
            discoveredModels = null; catalogFailure = null; catalogFilter = ""
        }
        ZxNavRow("查看模型用量", sub = "按模型对照 Token 与估算费用") {
            scope.onNavigate(UiSceneId("settings.model_usage"))
        }
        ZxNavRow("ChatGPT 账户") { scope.onNavigate(UiSceneId("settings.account")) }
        ZxNavRow("套餐额度") { scope.onNavigate(UiSceneId("settings.minimax_quota")) }
        ZxNavRow("模型性能") { scope.onNavigate(UiSceneId("settings.model_performance")) }
        ZxNavRow("官方价格") { scope.onNavigate(UiSceneId("settings.pricing")) }
    }
}

@Composable
/** Dedicated account management, separate from API model configuration. */
internal fun zxChatGptAccountAuthLabel(account: com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary): String = when {
    !account.signedIn -> "登录凭据已失效"
    !account.sharingEnabled -> "已登录 · 套餐尚未授权"
    else -> "已登录 · 套餐授权已启用"
}

fun ZhaoxiAccountScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.chatGptState.collectAsState()
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
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
        ZxSection("连接状态")
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
        ZxValueRow("ChatGPT 连接", value = phaseLabel)
        ZxValueRow("已保存账户", value = "${snap.accounts.size} 个")
        snap.selectedAccount?.let { ZxValueRow("当前账户", value =
            it.displayName ?: it.email ?: "已选账户") }
        (status ?: snap.error)?.let { ZxBanner(it, ZxTone.Warn) }
        if (busy) ZxFooterNote("正在请求账户 Owner，完成前不会重复提交")
        ZxSection("账户操作")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxButton("添加账户", { execute {
                settings.connectChatGpt()
                "授权流程已发起，请在系统浏览器完成操作"
            } }, Modifier.weight(1f), enabled = !busy)
            ZxButton("刷新账户", { execute {
                settings.refreshChatGpt()
                "已重新读取账户状态"
            } }, Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
        }
        ZxNavRow("查看本地模型用量",
            sub = "查看已记录的 Token 和估算费用；此处不代表 ChatGPT 平台套餐剩余额度") {
            scope.onNavigate(UiSceneId("settings.model_usage"))
        }
        if (snap.pendingAccountId != null) ZxButton("取消进行中的授权", {
            execute { settings.cancelChatGptAuthorization(); "取消授权请求已发出" }
        }, variant = ZxButtonVariant.Outline, enabled = !busy)
        ZxSection("已有账户")
        if (snap.accounts.isEmpty()) ZxEmpty("还没有账户", "点击添加账户，使用浏览器完成授权")
        snap.accounts.forEach { account ->
            val active = snap.selectedAccountId == account.id
            ZxRow(account.displayName ?: account.email ?: account.id,
                (if (active) "当前使用" else "其他账户") + " · " +
                    zxChatGptAccountAuthLabel(account),
                onClick = { expanded = if (expanded == account.id) null else account.id },
                trailing = {
                    ZxChip(if (expanded == account.id) "收起" else "管理",
                        expanded == account.id) {
                        expanded = if (expanded == account.id) null else account.id
                    }
                })
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("使用此账户", { execute {
                        settings.selectChatGptAccount(account.id)
                        "已提交账户切换，请以当前账户标记为准"
                    } }, Modifier.weight(1f), enabled = !busy && !active)
                    ZxButton("重新连接", { execute {
                        settings.restartChatGptAuthorization(account.id)
                        "重新授权流程已发起"
                    } }, Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZxButton("测试连接", { execute { settings.testChatGptAccount(account.id) } },
                        Modifier.weight(1f), variant = ZxButtonVariant.Outline, enabled = !busy)
                    ZxButton("断开连接", { disconnectConfirm = account.id },
                        Modifier.weight(1f), variant = ZxButtonVariant.Danger, enabled = !busy)
                }
                ZxButton("移除本地账户", { removeConfirm = account.id },
                    Modifier.fillMaxWidth(), variant = ZxButtonVariant.Outline, enabled = !busy)
                ZxFooterNote("重新连接只更新此账户的授权状态，不会自动切换到其他付费模型。")
            }
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
    var temperature by rememberSaveable(snap.temperatureCeiling) { mutableStateOf(snap.temperatureCeiling.toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    var showTemperatureError by remember { mutableStateOf(false) }
    val temperatureError = if (showTemperatureError) zxTemperatureCeilingError(temperature) else null
    ZxPage("模型性能", onBack = { scope.onBack() }) {
        ZxSection("推理强度")
        UiModelReasoningCeiling.entries.forEach { ceiling ->
            ZxChip(ceiling.name, ceiling == snap.reasoning) {
                status = port.submit(ModelPerformanceUiCommand.SetReasoning(ceiling)).zxPerformanceMessage()
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
    LaunchedEffect(settings) { storage = settings.sessionStorageStatus() }
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
    ZxPage("记忆管理", onBack = {
        if (editingDirty) status = "请先保存或关闭正在编辑的记忆"
        else scope.onBack()
    }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("可见范围")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
