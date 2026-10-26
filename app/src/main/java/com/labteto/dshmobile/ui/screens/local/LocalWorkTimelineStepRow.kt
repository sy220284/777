package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Only recognized service brands are user-visible, never raw tool identifiers. */
internal fun userFacingWorkToolProvider(toolName: String?): String? {
    val normalized = toolName?.trim()?.lowercase().orEmpty()
    if (!(normalized.startsWith("mcp_") || normalized.startsWith("plugin_"))) return null
    return when {
        normalized.contains("github") -> "GitHub"
        normalized.contains("figma") -> "Figma"
        else -> null
    }
}

/** User-facing tool purposes use a small reviewed allowlist, never raw tool identifiers. */
internal fun userFacingWorkToolActionRes(toolName: String?): Int? {
    val name = toolName?.lowercase().orEmpty()
    return when {
        name.contains("github") && (name.contains("workflow") || name.contains("check_run")) ->
            R.string.local_work_action_github_ci
        name.contains("github") && (name.contains("pull_request") || name.contains("_pr_") ||
            name.endsWith("_pr")) -> R.string.local_work_action_github_pr
        name.contains("github") && (name.contains("update_file") || name.contains("create_") ||
            name.contains("merge_") || name.contains("update_ref")) ->
            R.string.local_work_action_github_write
        name.contains("github") -> R.string.local_work_action_github_read
        name.contains("figma") -> R.string.local_work_action_figma
        else -> null
    }
}

/** Specific actions take precedence over broad provider categories, without exposing tool IDs. */
internal fun userFacingWorkToolAction(toolName: String?): String? {
    val name = toolName?.lowercase().orEmpty()
    when (name) {
        "read", "read_file", "file_inspect" -> return "读取文件"
        "grep", "search" -> return "搜索文件内容"
        "glob", "find", "list_files" -> return "查找工作区文件"
        "write", "write_file" -> return "写入文件"
        "edit", "edit_file", "apply_patch", "patch" -> return "修改文件"
        "job_output" -> return "读取后台任务输出"
        "job_poll", "job_list" -> return "检查后台任务状态"
        "job_kill" -> return "停止后台任务"
        "web_search" -> return "搜索网页"
        "web_fetch", "http_request" -> return "读取网页内容"
    }
    if (com.labteto.dshmobile.ui.agentOperationKind(name) == com.labteto.dshmobile.ui.AgentOperationKind.Execute)
        return "运行本地任务"
    if (!name.contains("github")) return null
    return when {
        "list_pr_changed_filenames" in name -> "扫描 PR 改动文件"
        "fetch_pr_file_patch" in name || "fetch_pr_patch" in name || "get_pr_diff" in name ->
            "读取 PR 代码差异"
        "fetch_pr_comments" in name || "list_pull_request_review_threads" in name ->
            "查看 PR 审查记录"
        "fetch_pr" in name || "get_pr_info" in name -> "查看 PR 详情"
        "search_prs" in name -> "查找拉取请求"
        "search_commits" in name -> "搜索提交记录"
        "search" in name -> "搜索仓库代码"
        "fetch_blob" in name -> "获取 GitHub Blob 内容"
        "fetch_file" in name -> "读取代码文件"
        "fetch_workflow_job_logs" in name -> "读取流水线日志"
        "workflow" in name || "check_run" in name || "get_commit_combined_status" in name ->
            "检查流水线状态"
        "merge_pull_request" in name -> "合并拉取请求"
        "update_file" in name -> "更新代码文件"
        "create_file" in name -> "创建代码文件"
        "create_commit" in name -> "提交代码变更"
        "list_pr" in name || "list_pull_request" in name -> "检查 PR 文件与状态"
        "list_" in name -> "扫描仓库文件"
        else -> null
    }
}

/** A readable chronological step, with optional explanation instead of a hidden disclosure. */
/** Public prose uses the same reading style before and after the streaming commit. */
@Composable
internal fun WorkTimelineStepRow(
    message: com.labteto.dshmobile.local.session.LocalHarnessMessage,
    workspacePath: String = "",
    repeatCount: Int = 1,
    active: Boolean = false,
    activity: com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem? = null,
    groupedMessages: List<com.labteto.dshmobile.local.session.LocalHarnessMessage> = emptyList(),
) {
    if (message.role != "tool") {
        val content = localWorkTimelineText(message.content) ?: return
        val action = localWorkPublicActionLabel(content)
        if (action != null) {
            val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                Icon(FeatherIcons.Sparkles, contentDescription = null,
                    tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                Text(action, style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary)
            }
        } else {
            SelectionContainer(Modifier.fillMaxWidth().padding(vertical = SyMetric.gapSm)) {
                MarkdownText(content, bodyStyle = DsType.workBody.withReadingWeight())
            }
        }
        return
    }
    val phase = completedWorkToolPhase(message)
    val action = workToolActionLabel(message.toolName, activity?.argumentsPreview)
    var detailsOpen by remember(message.id) { mutableStateOf(false) }
    val hasDetails = message.content.isNotBlank() || message.blocks.isNotEmpty() || groupedMessages.any { it.content.isNotBlank() || it.blocks.isNotEmpty() }
    WorkToolStatusRow(message.toolName, phase, argumentsPreview = activity?.argumentsPreview,
        repeatCount = repeatCount,
        modifier = Modifier.testTag(message.toolCallId?.let { "work-tool:$it" } ?: "work-tool:${message.id}"),
        onClick = if (hasDetails) ({ detailsOpen = true }) else null)
    if (detailsOpen) {
        SyResponsiveSheet(title = "工具回执 · $action", onDismiss = { detailsOpen = false },
            scrollable = true, seamlessBackground = false,
            trailing = {
                SyButton("关闭", onClick = { detailsOpen = false }, size = SyButtonSize.Small,
                    variant = SyButtonVariant.Ghost)
            }) {
            Text(workToolPhaseText(phase), style = DsType.std14Strong.withReadingWeight())
            message.toolErrorCode?.let { Text(it, style = DsType.small13.withReadingWeight()) }
            (listOf(message) + groupedMessages).forEachIndexed { index, receipt ->
                if (repeatCount > 1) {
                    Text("第 " + (index + 1) + " 次检查",
                        style = DsType.small13.withReadingWeight())
                }
                receipt.blocks.filterIsInstance<com.labteto.dshmobile.local.session.LocalMessageBlock.Image>().forEach { image ->
                    LocalTranscriptImage(image, workspacePath)
                }
                SelectionContainer {
                    Text(receipt.content, style = DsType.std14.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
                }
            }
        }
    }
}

internal fun completedWorkToolPhase(
    message: com.labteto.dshmobile.local.session.LocalHarnessMessage,
): LocalToolUiPhase = when {
    message.toolErrorCode == "TOOL_OUTCOME_UNKNOWN" -> LocalToolUiPhase.OUTCOME_UNKNOWN
    message.toolErrorCode?.contains("CANCEL", ignoreCase = true) == true ||
        message.toolErrorCode == "TOOL_NOT_STARTED" -> LocalToolUiPhase.CANCELLED
    message.toolIsError ?: localWorkToolFailed(message.content) -> LocalToolUiPhase.FAILED
    else -> LocalToolUiPhase.COMPLETED
}

@Composable
private fun workToolActionLabel(name: String?, argumentsPreview: String? = null): String =
    naturalWorkToolAction(name, argumentsPreview)
        ?: userFacingWorkToolAction(name)
        ?: stringResource(userFacingWorkToolActionRes(name)
            ?: agentOperationLabelRes(com.labteto.dshmobile.ui.agentOperationKind(name)))

internal fun workToolPhaseText(phase: LocalToolUiPhase): String = when (phase) {
    LocalToolUiPhase.DECLARED -> "等待执行"
    LocalToolUiPhase.RUNNING -> "执行中"
    LocalToolUiPhase.COMPLETED -> "已返回"
    LocalToolUiPhase.FAILED -> "失败"
    LocalToolUiPhase.CANCELLED -> "已中断"
    LocalToolUiPhase.OUTCOME_UNKNOWN -> "结果待确认"
}

/** Labels and status can wrap independently; narrow screens never squeeze the action column. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkToolStatusRow(
    name: String?,
    phase: LocalToolUiPhase,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    argumentsPreview: String? = null,
    repeatCount: Int = 1,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val provider = userFacingWorkToolProvider(name)
    val action = workToolActionLabel(name, argumentsPreview)
    // Provider is already visible via the icon. Repeating it in every title adds noise.
    val label = if (repeatCount > 1) "$action · 已检查 " + repeatCount + " 次" else action
    val status = workToolPhaseText(phase)
    val tint = when (phase) {
        LocalToolUiPhase.FAILED -> colors.dangerInk
        LocalToolUiPhase.OUTCOME_UNKNOWN -> colors.warningInk
        LocalToolUiPhase.RUNNING, LocalToolUiPhase.DECLARED -> colors.successInk
        else -> colors.textSecondary
    }
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button,
        onClickLabel = "查看工具回执", onClick = onClick) else Modifier
    Row(
        modifier = modifier.fillMaxWidth().then(clickable)
            .heightIn(min = if (onClick == null) 30.dp else 40.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = if (provider != null) "$provider，$status" else status
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        Icon(imageVector = when {
            phase == LocalToolUiPhase.FAILED || phase == LocalToolUiPhase.OUTCOME_UNKNOWN -> FeatherIcons.AlertTriangle
            phase == LocalToolUiPhase.RUNNING || phase == LocalToolUiPhase.DECLARED || phase == LocalToolUiPhase.CANCELLED -> FeatherIcons.Clock
            provider == "GitHub" -> FeatherIcons.GitBranch
            provider == "Figma" -> FeatherIcons.Palette
            else -> when (com.labteto.dshmobile.ui.agentOperationKind(name)) {
                com.labteto.dshmobile.ui.AgentOperationKind.Search -> FeatherIcons.Search
                com.labteto.dshmobile.ui.AgentOperationKind.Update -> FeatherIcons.Edit3
                com.labteto.dshmobile.ui.AgentOperationKind.Execute -> FeatherIcons.Code
                else -> FeatherIcons.FileText
            }
        }, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        FlowRow(modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = DsType.small13.withReadingWeight(),
                color = if (phase == LocalToolUiPhase.FAILED) tint else colors.textSecondary)
            if (phase != LocalToolUiPhase.COMPLETED) {
                Text(status, style = DsType.small13.withReadingWeight(), color = tint)
            }
        }
    }
}

@Composable
internal fun WorkPendingToolRow(activity: com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem) {
    if (activity.phase !in setOf(LocalToolUiPhase.DECLARED, LocalToolUiPhase.RUNNING)) return
    WorkToolStatusRow(activity.name, activity.phase, argumentsPreview = activity.argumentsPreview)
}

@Composable
internal fun WorkToolStatusNotice() {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Text("工具状态同步暂不可用，正在自动恢复；已保存的回复仍可查看",
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapMd),
        style = DsType.std14.withReadingWeight(), color = colors.warningInk)
}
