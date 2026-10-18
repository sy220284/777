package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.model.*
import com.labteto.dshmobile.local.memory.*
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Credentials exist only in the current form; all writes stay with the existing Model owner. */
@Composable
fun ZhaoxiModelsScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    val jobs = rememberCoroutineScope()
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("https://api.deepseek.com") }
    var protocol by rememberSaveable { mutableStateOf(LocalModelProtocol.CHAT_COMPLETIONS) }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun execute(action: suspend () -> String) {
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
        snap.modelProfiles.forEach { p ->
            ZxRow(p.displayName ?: p.model, if (snap.modelSelection.isActive(p)) "当前模型" else p.baseUrl,
                onClick = { status = settings.submit(SettingsUiCommand.SelectModel(p.id)).toString() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZxChip("编辑", false) { profileId = p.id; model = p.model; baseUrl = p.baseUrl; protocol = p.protocol; apiKey = "" }
                ZxChip("移除", false) { status = settings.submit(SettingsUiCommand.RemoveModel(p.id)).toString() }
            }
        }
        ZxSection(if (profileId == null) "添加模型" else "编辑模型")
        ZxField(model, { model = it }, Modifier.fillMaxWidth(), placeholder = "模型名称")
        ZxField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), placeholder = "接口地址")
        ZxField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), placeholder = "API Key（编辑时留空保留原凭据）",
            visualTransformation = PasswordVisualTransformation())
        LocalModelProtocol.entries.forEach { p -> ZxChip(p.name, p == protocol) { protocol = p } }
        ZxButton("测试连接", { execute { settings.testModel(apiKey, model, baseUrl, protocol, profileId) } }, enabled = !busy && model.isNotBlank())
        ZxButton("保存模型", { execute {
            when (val result = settings.save(SettingsUiCommand.SaveModel(apiKey, model, baseUrl, protocol, profileId))) {
                SettingsUiReceipt.Applied -> { apiKey = ""; profileId = null; "模型已保存" }
                is SettingsUiReceipt.Failed -> result.message ?: "保存失败"
                else -> "保存未完成"
            }
        } }, enabled = !busy && model.isNotBlank())
        ZxChip("新建", false) { profileId = null; model = ""; apiKey = "" }
        ZxNavRow("ChatGPT 账户") { scope.onNavigate(UiSceneId("settings.account")) }
        ZxNavRow("套餐额度") { scope.onNavigate(UiSceneId("settings.minimax_quota")) }
        ZxNavRow("模型性能") { scope.onNavigate(UiSceneId("settings.model_performance")) }
        ZxNavRow("官方价格") { scope.onNavigate(UiSceneId("settings.pricing")) }
    }
}

@Composable
fun ZhaoxiAccountScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.chatGptState.collectAsState()
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    fun execute(action: suspend () -> Unit) { jobs.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { status = failure.message ?: "账户操作失败" }
    } }
    ZxPage("ChatGPT 账户", onBack = { scope.onBack() }) {
        Text(snap.phase.name, color = ZhaoxiTheme.colors.ink)
        (status ?: snap.error)?.let { ZxBanner(it, ZxTone.Warn) }
        snap.accounts.forEach { account ->
            ZxRow(account.displayName ?: account.email ?: account.id,
                if (snap.selectedAccountId == account.id) "当前账户" else "",
                onClick = { execute { settings.selectChatGptAccount(account.id) } })
            ZxButton("测试连接", { execute { status = settings.testChatGptAccount(account.id) } })
            ZxButton("断开连接", { execute { status = settings.disconnectChatGptAccount(account.id) } })
            ZxButton("移除账户", { execute { status = settings.removeChatGptAccount(account.id) } })
        }
        ZxButton("连接 ChatGPT", { execute { settings.connectChatGpt() } })
        ZxButton("刷新账户与模型", { execute { settings.refreshChatGpt() } })
        if (snap.pendingAccountId != null) ZxButton("取消授权", { execute { settings.cancelChatGptAuthorization() } })
    }
}

@Composable
fun ZhaoxiQuotaScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    var result by remember { mutableStateOf<String?>(null) }
    val jobs = rememberCoroutineScope()
    ZxPage("套餐额度", onBack = { scope.onBack() }) {
        snap.modelProfiles.forEach { profile ->
            ZxButton("查询 ${profile.displayName ?: profile.model}", { jobs.launch {
                try { result = settings.queryMiniMaxQuota(profile).toString() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { result = failure.message ?: "查询失败" }
            } })
        }
        result?.let { Text(it, color = ZhaoxiTheme.colors.ink) }
    }
}

@Composable
fun ZhaoxiRuntimeSettingsScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    var main by rememberSaveable(snap.mainMaxSteps) { mutableStateOf(snap.mainMaxSteps.toString()) }
    var sub by rememberSaveable(snap.subagentMaxSteps) { mutableStateOf(snap.subagentMaxSteps.toString()) }
    var attempts by rememberSaveable(snap.modelAttempts) { mutableStateOf(snap.modelAttempts.toString()) }
    var rules by rememberUiPrivateText("zhaoxi.rules", snap.userRules) { snap.userRules }
    var status by remember { mutableStateOf<String?>(null) }
    ZxPage("行为与运行", onBack = { scope.onBack() }) {
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxField(main, { main = it }, placeholder = "主任务步数")
        ZxField(sub, { sub = it }, placeholder = "子任务步数")
        ZxField(attempts, { attempts = it }, placeholder = "模型尝试次数")
        ZxButton("保存运行设置", {
            val m = main.toIntOrNull(); val s = sub.toIntOrNull(); val a = attempts.toIntOrNull()
            status = if (m == null || s == null || a == null) "请输入整数" else
                settings.submit(SettingsUiCommand.RuntimeLimits(m, s, a, snap.modelSelection.workerProfileId)).toString()
        })
        ZxField(rules, { rules = it }, Modifier.fillMaxWidth(), placeholder = "长期规则")
        ZxButton("保存规则", { status = settings.submit(SettingsUiCommand.Personalization(rules, snap.autoRecall, snap.autoMemory)).toString() })
        ZxSwitchRow("自动召回", "", snap.autoRecall) { status = settings.submit(SettingsUiCommand.Personalization(snap.userRules, it, snap.autoMemory)).toString() }
        ZxSwitchRow("自动记忆", "", snap.autoMemory) { status = settings.submit(SettingsUiCommand.Personalization(snap.userRules, snap.autoRecall, it)).toString() }
        ZxSwitchRow("对话风格约束", "", snap.chatStyleGuardEnabled) { status = settings.submit(SettingsUiCommand.StyleGuard(it)).toString() }
    }
}

@Composable
fun ZhaoxiPerformanceScene(port: ModelPerformanceUiPort, scope: UiSceneRenderScope) {
    val snap by port.snapshots.collectAsState(initial = port.snapshot())
    var temperature by rememberSaveable(snap.temperatureCeiling) { mutableStateOf(snap.temperatureCeiling.toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    ZxPage("模型性能", onBack = { scope.onBack() }) {
        UiModelReasoningCeiling.entries.forEach { ceiling ->
            ZxChip(ceiling.name, ceiling == snap.reasoning) { status = port.submit(ModelPerformanceUiCommand.SetReasoning(ceiling)).toString() }
        }
        ZxField(temperature, { temperature = it }, placeholder = "温度上限")
        ZxButton("保存温度", { status = temperature.toDoubleOrNull()?.let { port.submit(ModelPerformanceUiCommand.SetTemperature(it)).toString() } ?: "请输入数值" })
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
        ZxButton("刷新官方价格", { jobs.launch { result = port.refreshOfficial().toString() } }, enabled = !snap.refreshing)
    }
}

@Composable
fun ZhaoxiStorageScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf<com.labteto.dshmobile.local.session.LocalSessionStorageStatus?>(null) }
    LaunchedEffect(settings) { storage = settings.sessionStorageStatus() }
    val export = rememberUiStreamDocumentSaver<Unit>("application/zip", { "777-sessions.zip" }, { _, output ->
        settings.exportSessionStorage(output); UiDocumentWriteReceipt.Saved
    }, { _, r -> status = r.toString() }, { status = it.message })
    ZxPage("会话存储", onBack = { scope.onBack() }) {
        storage?.let { ZxValueRow("${it.sessionCount} 个会话", value = "${it.totalBytes} 字节", sub = "剩余空间 ${it.freeBytes} 字节") }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxButton("压缩会话存储", { jobs.launch {
            try { storage = settings.compactSessionStorage(); status = "压缩完成" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "压缩失败" }
        } })
        ZxButton("导出会话备份", { export(Unit) })
    }
}

@Composable
fun ZhaoxiMemoryManagementScene(memory: MemoryUiPort, scope: UiSceneRenderScope) {
    var query by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var cursor by remember(query) { mutableStateOf<MemoryManagementCursor?>(null) }
    val page = remember(memory, query, revision, cursor) {
        memory.memoryPage(MemoryManagementQuery(setOf(MemoryScope.GLOBAL), text = query, cursor = cursor))
    }
    var status by remember { mutableStateOf<String?>(null) }
    ZxPage("记忆管理", onBack = { scope.onBack() }) {
        ZxField(query, { query = it }, placeholder = "搜索记忆")
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        page.records.forEach { record ->
            var text by rememberUiPrivateText("zhaoxi.memory.${record.id}", record.updatedAt) { record.content }
            ZxField(text, { text = it }, Modifier.fillMaxWidth())
            ZxButton("保存", { status = memory.submit(MemoryUiCommand.Update(record, text, record.pinned)).toString(); revision++ })
            ZxButton("遗忘", { status = memory.submit(MemoryUiCommand.Forget(record)).toString(); revision++ })
        }
        page.next?.let { next -> ZxButton("下一页", { cursor = next }) }
        if (cursor != null) ZxButton("回到首页", { cursor = null })
    }
}
