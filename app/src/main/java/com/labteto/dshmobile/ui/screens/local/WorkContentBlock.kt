package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
        val milestone = isWorkTimelineMilestone(content)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapXs),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            if (!milestone) {
                WorkOperationIcon(com.labteto.dshmobile.ui.AgentOperationKind.Generic, active)
            }
            Text(
                text = content, modifier = Modifier.weight(1f),
                style = (if (milestone) DsType.mdSmall else DsType.small13).withReadingWeight(),
                color = if (milestone) colors.text else colors.textSecondary,
            )
            if (active) {
                Text(
                    stringResource(agentOperationStatusRes(running = true, failed = false)),
                    style = DsType.caption11.withReadingWeight(), color = colors.primary,
                )
            }
        }
        return
    }

    val failed = message.toolIsError ?: localWorkToolFailed(message.content)
    val provider = userFacingWorkToolProvider(message.toolName)
    val kind = com.labteto.dshmobile.ui.agentOperationKind(message.toolName)
    val action = stringResource(userFacingWorkToolActionRes(message.toolName) ?: agentOperationLabelRes(kind))
    val label = if (provider != null) "$provider · $action" else action
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Icon(
            imageVector = when {
                failed -> FeatherIcons.AlertTriangle
                provider == "GitHub" -> FeatherIcons.GitBranch
                provider == "Figma" -> FeatherIcons.Palette
                else -> FeatherIcons.CheckCircle
            },
            contentDescription = null,
            tint = if (failed) colors.dangerInk else colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label, modifier = Modifier.weight(1f),
            style = DsType.small13.withReadingWeight(),
            color = if (failed) colors.dangerInk else colors.textSecondary,
        )
        if (failed) {
            Text(
                stringResource(agentOperationStatusRes(running = false, failed = true)),
                style = DsType.caption11.withReadingWeight(), color = colors.dangerInk,
            )
        } else if (repeatCount > 1) {
            Text(
                stringResource(R.string.local_work_process_operation_count, repeatCount),
                style = DsType.caption11.withReadingWeight(), color = colors.textTertiary,
            )
        }
    }
}

/** Explicit narrated findings are foregrounded; prose alone never proves a tool succeeded. */
internal fun isWorkTimelineMilestone(text: String): Boolean =
    WORK_TIMELINE_MILESTONE.containsMatchIn(text.trimStart())

private val WORK_TIMELINE_MILESTONE = Regex(
    "^(?:发现|已发现|已定位|定位到|确认|已确认|已完成|完成了|结果|验证通过|验证失败|阻塞|失败|风险|问题是|存在.+问题)"
)
