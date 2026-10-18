package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.memory.MemoryManagementQuery
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope

/** 先列出真实保存的配置，再补齐曾有请求记录但现在未保存的历史模型。 */
internal fun zxUsageModelNames(configured: List<String>, recorded: Set<String>): List<String> =
    (configured + recorded).distinct().sorted()

/** All totals here are rendered from the existing Usage Owner; no locally computed billing facts. */
@Composable
fun ZhaoxiUsageOverviewScene(
    usage: UsageUiPort, scope: UiSceneRenderScope, modelOnly: Boolean = false,
    settings: SettingsUiPort? = null,
) {
    val revision by usage.revision.collectAsState()
    val source = remember(revision, usage) { usage.snapshot() }
    val configured = settings?.let { owner ->
        owner.state.collectAsState(initial = owner.initialState).value.modelProfiles
    }.orEmpty()
    val modelNames = zxUsageModelNames(configured.map { it.model }, source.byModel.keys)
    val modelLabels = configured.associate { it.model to (it.displayName ?: it.model) }
    var selectedModel by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(modelNames) {
        if (selectedModel != null && selectedModel !in modelNames) selectedModel = null
    }
    val selectedHasRecords = selectedModel == null || selectedModel in source.byModel
    val snapshot = selectedModel?.let { source.byModel[it] ?: TokenUsageAnalyticsSnapshot() } ?: source
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    ZxPage(if (modelOnly) "模型用量" else "用量统计", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("按模型")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZxChip("全部", selectedModel == null) { selectedModel = null; selectedGroup = null }
            modelNames.forEach { model ->
                ZxChip(modelLabels[model] ?: model, selectedModel == model) {
                    selectedModel = model
                    selectedGroup = null
                }
            }
        }
        if (!selectedHasRecords) {
            ZxFooterNote("该模型尚无保留的请求用量记录，下面显示的是零记录统计，并非所有模型总和。")
        }
        ZxSection("用量总览")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Token 总量", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                Text(snapshot.tracked.totalTokens.toString(), fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold, color = ZhaoxiTheme.colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("请求", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                Text(snapshot.tracked.requestCount.toString(), fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold, color = ZhaoxiTheme.colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("估算费用", fontSize = 11.sp, color = ZhaoxiTheme.colors.ink2)
                Text("¥" + "%.4f".format(snapshot.tracked.estimatedCostCny), fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = ZhaoxiTheme.colors.ink)
            }
        }
        ZxValueRow("输入 / 输出", value = "${snapshot.tracked.inputTokens} / ${snapshot.tracked.outputTokens}", mono = true)
        if (snapshot.tracked.requestCount.toLong() == 0L) {
            ZxFooterNote("这里只统计已记录的模型请求；已有会话不一定保留旧请求的用量明细。")
        }
        if (snapshot.tracked.unreportedRequestCount > 0) {
            ZxFooterNote("${snapshot.tracked.unreportedRequestCount} 次请求未上报精确用量，统计仅反映可追踪事实")
        }
        if (snapshot.modelHistoryPartial || source.modelHistoryPartial) {
            ZxFooterNote("部分较早用量记录未保留，单模型历史统计可能不完整")
        }
        ZxSection("会话与任务")
        if (snapshot.sessions.isEmpty() && snapshot.tasks.isEmpty()) {
            ZxEmpty("暂无用量分组", "完成聊天或工作请求后将在这里显示")
        }
        snapshot.sessions.forEach { item ->
            ZxRow(item.title, "${item.aggregate.totalTokens} Token · ${item.aggregate.requestCount} 次请求",
                onClick = { selectedGroup = item.key })
        }
        snapshot.tasks.forEach { item ->
            ZxRow(item.title, "${item.aggregate.totalTokens} Token · ${item.aggregate.requestCount} 次请求")
        }
        selectedGroup?.let { key ->
            usage.groupDetail(TokenUsageGroupKind.SESSION, key, selectedModel)?.let { detail ->
                ZxSection(detail.title)
                ZxValueRow("汇总", value = "${detail.aggregate.totalTokens} Token")
                ZxValueRow("请求", value = detail.aggregate.requestCount.toString())
            }
        }
        ZxNavRow("用量日志", sub = "逐次请求记录与用量明细") {
            scope.onNavigate(UiSceneId("settings.usage_log"))
        }
    }
}

/** Distinct request ledger page, rather than rendering the overview again. */
@Composable
fun ZhaoxiUsageLogScene(
    usage: UsageUiPort,
    scope: UiSceneRenderScope,
    openRequest: (String) -> Unit,
) {
    val revision by usage.revision.collectAsState()
    val source = remember(usage, revision) { usage.snapshot() }
    var selectedModel by rememberSaveable { mutableStateOf<String?>(null) }
    val current = selectedModel?.let { source.byModel[it] } ?: source
    ZxPage("用量日志", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("模型筛选")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZxChip("全部", selectedModel == null) { selectedModel = null }
            source.byModel.keys.sorted().forEach { model ->
                ZxChip(model, selectedModel == model) { selectedModel = model }
            }
        }
        ZxSection("最近请求")
        if (current.recentRecords.isEmpty()) ZxEmpty("暂无请求记录", "完成模型请求后可查看明细")
        current.recentRecords.forEach { record ->
            ZxRow(record.model, "${record.context.action.name} · ${record.totalTokens} Token · ¥${"%.4f".format(record.estimatedCostCny)}",
                onClick = { openRequest(record.requestId) })
        }
        if (current.tracked.unreportedRequestCount > 0) {
            ZxFooterNote("部分请求未上报准确用量")
        }
    }
}

/** Show only an Owner-resolved request ID; no prompt content is exposed or reconstructed. */
@Composable
fun ZhaoxiUsageDetailScene(usage: UsageUiPort, requestId: String?, scope: UiSceneRenderScope) {
    val revision by usage.revision.collectAsState()
    val record = remember(usage, requestId, revision) { requestId?.let(usage::requestDetail) }
    ZxPage("单次请求", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (record == null) {
            ZxEmpty("没有可查看的请求", "从用量日志选择一条仍有效的请求")
        } else {
            ZxSection("请求归属")
            ZxValueRow("模型", value = record.model)
            ZxValueRow("行为", value = record.context.action.name)
            record.context.sessionTitle?.let { ZxValueRow("会话", value = it) }
            record.context.taskLabel?.let { ZxValueRow("任务", value = it) }
            ZxSection("Token 用量")
            ZxValueRow("输入", value = record.inputTokens.toString(), mono = true)
            ZxValueRow("输出", value = record.outputTokens.toString(), mono = true)
            ZxValueRow("缓存命中", value = record.cacheHitTokens.toString(), mono = true)
            ZxValueRow("推理", value = record.reasoningTokens.toString(), mono = true)
            ZxSection("费用")
            ZxValueRow("估算金额", value = "¥" + "%.4f".format(record.estimatedCostCny), mono = true)
            ZxFooterNote(if (record.reported) "模型已上报用量" else "当前为未上报/估算用量")
        }
    }
}

/** Memory overview deliberately says global scope; other scopes require their own authorized context. */
@Composable
fun ZhaoxiMemoryOverviewScene(memory: MemoryUiPort, settings: SettingsUiPort?, scope: UiSceneRenderScope) {
    val page = remember(memory) { memory.memoryPage(MemoryManagementQuery(setOf(MemoryScope.GLOBAL))) }
    ZxPage("记忆", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("全局记忆概览")
        ZxValueRow("有效记忆", value = page.totalMatching.toString(), mono = true)
        ZxValueRow("记忆分类", value = page.kindCounts.count { it.value > 0 }.toString())
        page.kindCounts.filterValues { it > 0 }.forEach { (kind, count) ->
            ZxValueRow(kind.name, value = count.toString())
        }
        ZxSection("记忆管理")
        if (settings != null) {
            ZxNavRow("记忆设置", sub = "自动召回 · 自动记忆") {
                scope.onNavigate(UiSceneId("settings.memory_settings"))
            }
        }
        ZxNavRow("记忆管理", sub = "搜索 · 修改 · 遗忘") {
            scope.onNavigate(UiSceneId("settings.memory_management"))
        }

    }
}

@Composable
fun ZhaoxiChatGuardScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    var draft by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    ZxPage("聊天风格守卫", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSwitchRow("启用风格守卫", "", snap.chatStyleGuardEnabled) {
            result = settings.submit(SettingsUiCommand.StyleGuard(it)).zxUserMessage("更新")
        }
        ZxSection("自定义过滤词")
        ZxValueRow("已配置", value = "${snap.chatStyleGuardCustomPhrases.size} 项")
        ZxField(draft, { draft = it }, Modifier.fillMaxWidth(), placeholder = "输入过滤词")
        ZxButton("添加过滤词", {
            val receipt = settings.submit(SettingsUiCommand.AddStylePhrase(draft.trim()))
            result = receipt.zxUserMessage("添加")
            if (receipt == SettingsUiReceipt.Applied) draft = ""
        }, enabled = draft.isNotBlank())
        snap.chatStyleGuardCustomPhrases.forEach { phrase ->
            ZxRow(phrase, "自定义", trailing = {
                ZxChip("移除", false) {
                    result = settings.submit(SettingsUiCommand.RemoveStylePhrase(phrase)).zxUserMessage("移除")
                }
            })
        }
        ZxSection("人物过滤词")
        if (snap.personaBannedPhrases.isEmpty()) ZxFooterNote("当前人物没有单独设置的过滤词")
        else snap.personaBannedPhrases.forEach { ZxRow(it, "人物资料来源") }
        ZxSection("最近命中")
        if (snap.styleGuardHits.isEmpty()) ZxFooterNote("暂无命中记录")
        snap.styleGuardHits.forEach { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (snap.styleGuardHits.isNotEmpty()) {
            ZxButton(if (confirmClear) "确认清空记录" else "清空命中记录", {
                if (confirmClear) {
                    result = settings.submit(SettingsUiCommand.ClearStyleHits).zxUserMessage("清空")
                    confirmClear = false
                } else confirmClear = true
            })
        }
        result?.let { ZxFooterNote(it) }
    }
}

@Composable
fun ZhaoxiMemorySettingsScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = settings.initialState)
    var status by remember { mutableStateOf<String?>(null) }
    ZxPage("记忆设置", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("记忆行为")
        ZxSwitchRow("自动召回", "按模型与会话权限检索记忆", snap.autoRecall) {
            status = settings.submit(SettingsUiCommand.Personalization(snap.userRules, it, snap.autoMemory)).zxUserMessage("更新")
        }
        ZxSwitchRow("自动记忆", "", snap.autoMemory) {
            status = settings.submit(SettingsUiCommand.Personalization(snap.userRules, snap.autoRecall, it)).zxUserMessage("更新")
        }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }

    }
}

/** Agent approval policy is a standalone settings destination, separate from Android grants. */
@Composable
fun ZhaoxiApprovalModeScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    ZxPage("操作审批模式", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZhaoxiApprovalModeSelector(settings)
    }
}

/** Shared Owner-backed policy selector: no fake permission flags. */
@Composable
fun ZhaoxiApprovalModeSelector(settings: SettingsUiPort) {
    val mode by settings.approvalMode.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    ZxSection("操作审批模式")
    LocalApprovalMode.entries.forEach { candidate ->
        ZxChip(when (candidate) {
            LocalApprovalMode.DEFAULT -> "默认"
            LocalApprovalMode.MANUAL -> "手动审批"
            LocalApprovalMode.AUTO -> "自动审批"
        }, mode == candidate) {
            status = settings.submit(SettingsUiCommand.ApprovalMode(candidate)).zxUserMessage("更新审批模式")
        }
    }
    ZxFooterNote("审批模式不会修改系统权限")
    status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
}
