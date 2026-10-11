package com.labteto.dshmobile.ui.screens.local

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
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
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

/** A readable chronological step, with optional explanation instead of a hidden disclosure. */
@Composable
internal fun WorkContentBlock(node: LocalWorkProcessNode, running: Boolean) {
    val action = if (node.failed) {
        stringResource(R.string.local_reasoning_work_failed)
    } else stringResource(agentOperationLabelRes(node.kind))
    val provider = userFacingWorkToolProvider(node.toolName)
    val summary = node.summary ?: if (provider != null) "$provider · $action" else action
    val label = if (provider != null && node.summary != null &&
        !summary.contains(provider, ignoreCase = true)
    ) "$provider · $summary" else summary
    val colors = DsTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.tiny),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            when {
                node.failed -> Icon(
                    FeatherIcons.AlertTriangle,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(18.dp),
                )
                node.count > 0 -> WorkOperationIcon(node.kind, running = running)
                running -> WorkOperationIcon(node.kind, running = true)
                else -> Icon(
                    FeatherIcons.CheckCircle,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = (if (node.summary != null) DsType.mdSmall else DsType.small13)
                    .withReadingWeight().copy(fontFamily = FontFamily.Default),
                color = when {
                    node.failed -> colors.error
                    node.count > 0 -> colors.labelSecondary
                    else -> colors.labelPrimary
                },
            )
            if (node.failed) {
                Text(
                    stringResource(agentOperationStatusRes(running = false, failed = true)),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.error,
                )
            } else if (running) {
                Text(
                    stringResource(agentOperationStatusRes(running = true, failed = false)),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.accent,
                )
            } else if (node.count > 1) {
                Text(
                    stringResource(R.string.local_work_process_operation_count, node.count),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
        }
        node.detail?.let { detail ->
            Text(
                detail,
                modifier = Modifier.padding(start = 18.dp + DsSpacing.small),
                style = DsType.small13.withReadingWeight(),
                color = if (node.failed) colors.error else colors.labelPrimary,
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
    val colors = DsTheme.colors
    if (message.role != "tool") {
        val content = localWorkTimelineText(message.content) ?: return
        val milestone = isWorkTimelineMilestone(content)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.tiny),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (!milestone) {
                WorkOperationIcon(com.labteto.dshmobile.ui.AgentOperationKind.Generic, active)
            }
            Text(
                text = content, modifier = Modifier.weight(1f),
                style = (if (milestone) DsType.mdSmall else DsType.small13).withReadingWeight(),
                color = if (milestone) colors.labelPrimary else colors.labelSecondary,
            )
            if (active) {
                Text(
                    stringResource(agentOperationStatusRes(running = true, failed = false)),
                    style = DsType.caption11.withReadingWeight(), color = colors.accent,
                )
            }
        }
        return
    }

    val failed = message.toolIsError ?: localWorkToolFailed(message.content)
    val provider = userFacingWorkToolProvider(message.toolName)
    val kind = com.labteto.dshmobile.ui.agentOperationKind(message.toolName)
    val action = stringResource(agentOperationLabelRes(kind))
    val label = if (provider != null) "$provider · $action" else action
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(
            imageVector = when {
                failed -> FeatherIcons.AlertTriangle
                provider == "GitHub" -> FeatherIcons.GitBranch
                provider == "Figma" -> FeatherIcons.Palette
                else -> FeatherIcons.CheckCircle
            },
            contentDescription = null,
            tint = if (failed) colors.error else colors.labelSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label, modifier = Modifier.weight(1f),
            style = DsType.small13.withReadingWeight(),
            color = if (failed) colors.error else colors.labelSecondary,
        )
        if (failed) {
            Text(
                stringResource(agentOperationStatusRes(running = false, failed = true)),
                style = DsType.caption11.withReadingWeight(), color = colors.error,
            )
        } else if (repeatCount > 1) {
            Text(
                stringResource(R.string.local_work_process_operation_count, repeatCount),
                style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary,
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
