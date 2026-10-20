package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.model.visibleAssistantText
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.components.FeatherIcons

private val WORK_PROCESS_PRIVATE_NARRATION = Regex(
    "^(?:用户要(?:我)?|用户希望我|根据系统提示|按系统提示|先按系统提示|我需要先|我现在看到了|现在我看到了|先检查系统提示)",
    RegexOption.IGNORE_CASE,
)

/** Shared lightweight action icon, also used by the team worksite. */
@Composable
internal fun WorkOperationIcon(kind: AgentOperationKind, running: Boolean) {
    val icon = when (kind) {
        AgentOperationKind.Inspect -> FeatherIcons.FileText
        AgentOperationKind.Search -> FeatherIcons.Search
        AgentOperationKind.Update -> FeatherIcons.Edit3
        AgentOperationKind.Execute -> FeatherIcons.Code
        AgentOperationKind.Web -> FeatherIcons.Globe
        AgentOperationKind.Device -> FeatherIcons.Device
        AgentOperationKind.Image -> FeatherIcons.Image
        AgentOperationKind.Background -> FeatherIcons.Clock
        AgentOperationKind.Generic, AgentOperationKind.Question -> FeatherIcons.Sparkles
        AgentOperationKind.Plan -> FeatherIcons.FileText
        AgentOperationKind.Delegate -> FeatherIcons.Users
        AgentOperationKind.External -> FeatherIcons.Gear
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (running) com.shenyan.designsystem.foundation.LocalSyColors.current.successInk
            else com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        modifier = Modifier.size(18.dp),
    )
}

/** Preserve public Markdown verbatim; private model reasoning is never shown. */
internal fun localWorkTimelineText(content: String): String? {
    val visible = visibleAssistantText(content)
    return visible.takeIf {
        it.isNotBlank() && !WORK_PROCESS_PRIVATE_NARRATION.containsMatchIn(it.trimStart())
    }
}

private val LOCAL_WORK_PUBLIC_ACTION_START = Regex(
    "^(?:检索|搜索|查找|检查|核对|审查|阅读|读取|浏览|更新|修改|优化|完善|补充|补齐|验证|测试|编写|制定|规划|调整|排查|处理|提交|合并|运行|同步|创建|新增|增强)"
)

/** Only a real single-line action becomes a compact row; prose stays Markdown. */
internal fun localWorkPublicActionLabel(content: String): String? {
    val text = localWorkTimelineText(content)?.trim() ?: return null
    if (text.length !in 4..44 || text.lineSequence().count() != 1 ||
        text.any { it in "。！？，；：:!?;#*|" || it.code == 96 }) return null
    return text.takeIf(LOCAL_WORK_PUBLIC_ACTION_START::containsMatchIn)
}

/** Retain the result fallback when an older event lacks a structured error flag. */
internal fun localWorkToolFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content ||
        "[MODEL_TIMEOUT]" in content || "[NETWORK_ERROR]" in content ||
        "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content
