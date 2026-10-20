package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.agentOperationStatusRes
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
@Composable
internal fun WorkContentBlock(node: LocalWorkProcessNode, running: Boolean) {
    val action = when {
        node.failed -> stringResource(R.string.local_reasoning_work_failed)
        node.summary == null && node.operationKinds.size > 1 -> "工具操作"
        node.summary == null && node.toolName != null &&
            userFacingWorkToolActionRes(node.toolName) != null ->
            stringResource(userFacingWorkToolActionRes(node.toolName)!!)
        node.summary == null &&
            node.kind == com.labteto.dshmobile.ui.AgentOperationKind.Generic -> "工具操作"
        else -> stringResource(agentOperationLabelRes(node.kind))
    }
    val provider = userFacingWorkToolProvider(node.toolName)
    val summary = node.summary ?: if (provider != null) "$provider · $action" else action
    val label = if (provider != null && node.summary != null &&
        !summary.contains(provider, ignoreCase = true)
    ) "$provider · $summary" else summary
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapXs),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            when {
                node.failed -> Icon(
                    FeatherIcons.AlertTriangle,
                    contentDescription = null,
                    tint = colors.dangerInk,
                    modifier = Modifier.size(18.dp),
                )
                node.count > 0 -> WorkOperationIcon(node.kind, running = running)
                running -> WorkOperationIcon(node.kind, running = true)
                else -> WorkOperationIcon(node.kind, running = false)
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = (if (node.summary != null) DsType.mdSmall else DsType.small13)
                    .withReadingWeight().copy(fontFamily = FontFamily.Default),
                color = when {
                    node.failed -> colors.dangerInk
                    node.count > 0 -> colors.textSecondary
                    else -> colors.text
                },
            )
            if (node.failed) {
                Text(
                    stringResource(agentOperationStatusRes(running = false, failed = true)),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.dangerInk,
                )
            } else if (running) {
                Text(
                    stringResource(agentOperationStatusRes(running = true, failed = false)),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.primary,
                )
            } else if (node.count > 1) {
                Text(
                    stringResource(R.string.local_work_process_operation_count, node.count),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                )
            }
        }
        node.detail?.let { detail ->
            Text(
                detail,
                modifier = Modifier.padding(start = 18.dp + SyMetric.gapMd),
                style = DsType.small13.withReadingWeight(),
                color = if (node.failed) colors.dangerInk else colors.text,
            )
        }
    }
}


/** Each item is composed lazily, with milestones distinguished from routine operations. */
@Composable
internal fun WorkTimelineStepRow(
    message: com.labteto.dshmobile.local.session.LocalHarnessMessage,
    repeatCount: Int = 1,
    active: Boolean = false,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    if (message.role != "tool") {
        val content = localWorkTimelineText(message.content) ?: return
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapSm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            // Public narration is first-class reading content, not a tool-status subrow.
            SelectionContainer(modifier = Modifier.weight(1f)) {
                MarkdownText(
                    text = content,
                    bodyStyle = DsType.mdBody.withReadingWeight().copy(color = colors.text),
                    preserveSoftLineBreaks = true,
                )
            }
            if (active) {
                Text(
                    stringResource(agentOperationStatusRes(running = true, failed = false)),
                    style = DsType.caption11.withReadingWeight(), color = colors.primary,
                )
            }
        }
        return
    }

    val phase = completedWorkToolPhase(message)
    val failed = phase == com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED
    val unsettled = phase == com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN
    val cancelled = phase == com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED
    val provider = userFacingWorkToolProvider(message.toolName)
    val kind = com.labteto.dshmobile.ui.agentOperationKind(message.toolName)
    val action = userFacingWorkToolAction(message.toolName) ?:
        stringResource(userFacingWorkToolActionRes(message.toolName) ?: agentOperationLabelRes(kind))
    val label = if (provider != null) "$provider · $action" else action
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Icon(
            imageVector = when {
                failed || unsettled -> FeatherIcons.AlertTriangle
                cancelled -> FeatherIcons.Clock
                provider == "GitHub" -> FeatherIcons.GitBranch
                provider == "Figma" -> FeatherIcons.Palette
                else -> FeatherIcons.CheckCircle
            },
            contentDescription = null,
            tint = if (failed) colors.dangerInk else if (unsettled) colors.primary else colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label, modifier = Modifier.weight(1f),
            style = DsType.small13.withReadingWeight(),
            color = if (failed) colors.dangerInk else colors.textSecondary,
        )
        Text(
            text = when (phase) {
                com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED ->
                    stringResource(agentOperationStatusRes(running = false, failed = true))
                com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN -> "结果待确认"
                com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED -> "已取消"
                else -> stringResource(agentOperationStatusRes(running = false, failed = false))
            },
            style = DsType.caption11.withReadingWeight(),
            color = if (failed) colors.dangerInk else if (unsettled) colors.primary else colors.textTertiary,
        )
    }
}

/** Status facts come from structured tool outcome, never content matching for new records. */
internal fun completedWorkToolPhase(
    message: com.labteto.dshmobile.local.session.LocalHarnessMessage,
): com.labteto.dshmobile.local.presentation.LocalToolUiPhase {
    val code = message.toolErrorCode.orEmpty()
    return when {
        code == "TOOL_OUTCOME_UNKNOWN" ->
            com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN
        code.contains("CANCEL", ignoreCase = true) ->
            com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED
        message.toolIsError ?: localWorkToolFailed(message.content) ->
            com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED
        else -> com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED
    }
}

/** An in-flight call reads its status from the Tool-owned event projection. */
@Composable
internal fun WorkPendingToolRow(
    activity: com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val provider = userFacingWorkToolProvider(activity.name)
    val kind = com.labteto.dshmobile.ui.agentOperationKind(activity.name)
    val action = userFacingWorkToolAction(activity.name) ?:
        stringResource(userFacingWorkToolActionRes(activity.name) ?: agentOperationLabelRes(kind))
    val label = if (provider != null) "$provider · $action" else action
    val status = when (activity.phase) {
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.DECLARED -> "等待执行"
        com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING -> "执行中"
        else -> return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Icon(
            imageVector = when (provider) {
                "GitHub" -> FeatherIcons.GitBranch
                "Figma" -> FeatherIcons.Palette
                else -> FeatherIcons.Clock
            },
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        Text(
            text = status,
            style = DsType.caption11.withReadingWeight(),
            color = colors.primary,
        )
    }
}

/** Explicit narrated findings are foregrounded; prose alone never proves a tool succeeded. */
internal fun isWorkTimelineMilestone(text: String): Boolean =
    WORK_TIMELINE_MILESTONE.containsMatchIn(text.trimStart())

private val WORK_TIMELINE_MILESTONE = Regex(
    "^(?:发现|已发现|已定位|定位到|确认|已确认|已完成|完成了|结果|验证通过|验证失败|阻塞|失败|风险|问题是|存在.+问题)"
)
