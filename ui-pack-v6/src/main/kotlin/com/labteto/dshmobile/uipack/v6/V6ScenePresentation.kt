package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import com.shenyan.designsystem.components.*
import com.shenyan.designsystem.foundation.LocalSyColors

internal data class V6Destination(val id: String, val title: String, val summary: String? = null)
internal data class V6DestinationGroup(val title: String, val entries: List<V6Destination>)

/** Destination IDs remain unchanged; only their presentation is grouped. */
internal val v6SettingsGroups = listOf(
    V6DestinationGroup("模型与账户", listOf(
        V6Destination("settings.models", "模型配置", "选择模型与连接方式"),
        V6Destination("settings.account", "ChatGPT 账户"),
        V6Destination("settings.minimax_quota", "MiniMax 官方额度", "查看官方套餐与账户额度"),
        V6Destination("settings.pricing", "费用估算", "核算不同模型的推理开销"),
        V6Destination("settings.model_performance", "模型性能", "推理与运行偏好"),
        V6Destination("settings.usage", "用量与额度"))),
    V6DestinationGroup("使用偏好", listOf(
        V6Destination("settings.appearance", "外观"),
        V6Destination("settings.chat", "聊天设置"),
        V6Destination("settings.memory", "记忆与个性化"),
        V6Destination("settings.memory_management", "记忆管理", "管理已保存的长期记忆"),
        V6Destination("settings.notifications", "通知"))),
    V6DestinationGroup("应用与数据", listOf(
        V6Destination("settings.permissions", "系统权限"),
        V6Destination("settings.session_storage", "数据管理"),
        V6Destination("settings.advanced", "高级参数"),
        V6Destination("settings.diagnostics", "诊断"),
        V6Destination("settings.update", "检查更新"))),
)

@Composable
internal fun V6DestinationSection(group: V6DestinationGroup, onSelect: (V6Destination) -> Unit) {
    SySectionHeader(group.title, Modifier.padding(top = 12.dp))
    group.entries.forEachIndexed { index, entry ->
        SyEntrance("destination:${entry.id}", order = index) {
            SyCategoryRow(title = entry.title, subtitle = entry.summary,
                onClick = { onSelect(entry) })
        }
    }
}

internal fun v6RejectionLabel(reason: UiCommandRejection): String = when (reason) {
    UiCommandRejection.EMPTY_INPUT -> "请先输入内容或添加附件"
    UiCommandRejection.LOADING -> "正在加载，请稍后再试"
    UiCommandRejection.UNCONFIGURED -> "请先配置模型"
    UiCommandRejection.STALE_IDENTITY -> "会话已变化，请在当前会话重试"
    UiCommandRejection.SESSION_TRANSITION -> "正在切换会话，请稍候"
    UiCommandRejection.QUEUE_FULL -> "待处理消息已满，请等当前消息处理后再发送"
    UiCommandRejection.QUEUE_UNAVAILABLE -> "当前无法排队，请稍后再试"
    UiCommandRejection.APPROVAL_EXPIRED -> "这次授权请求已失效，请查看最新请求"
    UiCommandRejection.QUESTION_EXPIRED -> "这次提问已失效，请查看最新消息"
    UiCommandRejection.UNSUPPORTED -> "当前模式不支持这项操作"
}

internal fun v6MessageEditLabel(result: UiMessageEditOutcome): String = when (result) {
    UiMessageEditOutcome.SENT -> "修改已提交，正在处理新回复"
    UiMessageEditOutcome.COMMITTED_NOT_STARTED -> "修改已保存，回复尚未启动"
    UiMessageEditOutcome.BUSY -> "正在处理消息，请稍后再修改"
    UiMessageEditOutcome.UNAVAILABLE -> "暂时无法修改，请稍后再试"
    UiMessageEditOutcome.WRONG_MODE -> "请回到这条消息所属的模式再修改"
    UiMessageEditOutcome.UNCONFIGURED -> "请先配置模型"
    UiMessageEditOutcome.HISTORY_UNAVAILABLE -> "历史消息尚未就绪，请重新加载"
    UiMessageEditOutcome.MESSAGE_MISSING -> "这条消息已不存在，请刷新会话"
    UiMessageEditOutcome.EMPTY -> "修改内容不能为空"
    UiMessageEditOutcome.UNCHANGED -> "内容没有变化"
    UiMessageEditOutcome.FAILED -> "修改失败，原消息已保留"
}

internal fun v6PhaseLabel(phase: UiExecutionPhase, workMode: Boolean): String = when (phase) {
    UiExecutionPhase.IDLE -> "等待输入"
    UiExecutionPhase.RUNNING -> if (workMode) "正在执行" else "正在回复"
    UiExecutionPhase.WAITING_FOR_USER -> "需要你回应"
}

internal fun v6ApprovalLabel(impact: UiApprovalImpact): String = when (impact) {
    UiApprovalImpact.LOW -> "低风险"
    UiApprovalImpact.MEDIUM -> "中等风险"
    UiApprovalImpact.HIGH -> "高风险"
    UiApprovalImpact.CRITICAL -> "重大风险"
}

/** Message chrome follows its role; actions stay outside the reading surface. */
@Composable
internal fun V6MessageBody(message: UiMessageSnapshot, workMode: Boolean,
    onOpenFile: ((String) -> Unit)? = null) {
    val colors = LocalSyColors.current
    when (message.role) {
        UiMessageRole.USER -> SyUserBubble(message.text)
        UiMessageRole.ASSISTANT -> {
            if (workMode) V6ReadableContent(message.text, onOpenFile = onOpenFile)
            else SyMaterialSurface(Modifier.fillMaxWidth(.91f), shadowElevation = 0.dp) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                    V6ReadableContent(message.text, preserveLines = true, onOpenFile = onOpenFile)
                }
            }
        }
        UiMessageRole.TOOL, UiMessageRole.SYSTEM -> Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (message.role == UiMessageRole.TOOL) "执行记录" else "会话提示",
                style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            V6ReadableContent(message.text, preserveLines = true, onOpenFile = onOpenFile)
        }
    }
}

internal fun v6StorySyncLabel(sync: UiStorySessionSync): String = when (sync) {
    UiStorySessionSync.APPLIED -> "当前会话已同步"
    UiStorySessionSync.NOT_BOUND -> "当前会话未关联此故事"
    UiStorySessionSync.DEFERRED -> "当前会话暂未同步，请稍后查看"
}
