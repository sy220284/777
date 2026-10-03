package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

internal const val LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT = 8
internal const val LOCAL_WORK_PROCESS_SUMMARY_LIMIT = 180

private val WORK_PROCESS_WHITESPACE = Regex("\\s+")

internal data class LocalWorkProcessNode(
    val summary: String? = null,
    val operationKinds: List<AgentOperationKind> = emptyList(),
    val failed: Boolean = false,
    val count: Int = 0,
) {
    val kind: AgentOperationKind
        get() = operationKinds.firstOrNull() ?: AgentOperationKind.Generic
}

internal fun localWorkProcessSummary(content: String): String? {
    val compact = WORK_PROCESS_WHITESPACE.replace(content.trim(), " ")
    if (compact.isBlank()) return null
    return truncateWithoutSplittingSurrogatePair(compact, LOCAL_WORK_PROCESS_SUMMARY_LIMIT)
}

internal fun buildWorkProcessNodes(messages: List<LocalHarnessMessage>): List<LocalWorkProcessNode> {
    if (messages.isEmpty()) return emptyList()
    val nodes = mutableListOf<LocalWorkProcessNode>()

    fun appendSummary(content: String) {
        localWorkProcessSummary(content)?.let { summary ->
            nodes += LocalWorkProcessNode(summary = summary)
        }
    }

    fun appendOperation(kind: AgentOperationKind, failed: Boolean) {
        val last = nodes.lastOrNull()
        if (last?.summary != null) {
            val kinds = if (kind in last.operationKinds) {
                last.operationKinds
            } else {
                last.operationKinds + kind
            }
            nodes[nodes.lastIndex] = last.copy(
                operationKinds = kinds,
                failed = last.failed || failed,
                count = last.count + 1,
            )
            return
        }

        if (last?.kind == kind) {
            nodes[nodes.lastIndex] = last.copy(
                failed = last.failed || failed,
                count = last.count + 1,
            )
        } else {
            nodes += LocalWorkProcessNode(
                operationKinds = listOf(kind),
                failed = failed,
                count = 1,
            )
        }
    }

    messages.forEach { message ->
        when (message.role) {
            "progress", "assistant" -> appendSummary(message.content)
            "tool" -> appendOperation(
                kind = agentOperationKind(message.toolName),
                failed = toolResultFailed(message.content),
            )
        }
    }

    return nodes
}

internal fun visibleWorkProcessNodes(
    nodes: List<LocalWorkProcessNode>,
    showAll: Boolean,
    collapsedLimit: Int = LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT,
): List<LocalWorkProcessNode> {
    if (showAll || nodes.size <= collapsedLimit.coerceAtLeast(1)) return nodes
    return nodes.takeLast(collapsedLimit.coerceAtLeast(1))
}

@Composable
internal fun WorkProcessRow(
    messages: List<LocalHarnessMessage>,
    running: Boolean = false,
) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    val nodes = remember(messages) { buildWorkProcessNodes(messages) }
    if (nodes.isEmpty()) return
    var expanded by remember(messages.first().id) { mutableStateOf(false) }
    var showAllNodes by remember(messages.first().id) { mutableStateOf(false) }
    val latestNode = nodes.last()
    val preview = latestNode.summary ?: stringResource(agentOperationLabelRes(latestNode.kind))
    val collapsedHiddenCount = (nodes.size - LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT).coerceAtLeast(0)
    val visibleNodes = visibleWorkProcessNodes(nodes, showAllNodes)
    val visibleStartIndex = nodes.size - visibleNodes.size
    val disclosureState = stringResource(
        if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed,
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "workProcessChevron",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSpacing.touchTarget)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = disclosureState }
                    .padding(vertical = DsSpacing.xsmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.local_work_process),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    Text(
                        preview,
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (running) {
                    DsStatusPill(
                        state = DsStatus.Running,
                        label = stringResource(agentOperationStatusRes(running = true, failed = false)),
                    )
                }
                DsPill(text = stringResource(R.string.local_work_process_steps, nodes.size))
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(
                        if (expanded) R.string.local_work_process_collapse else R.string.local_work_process_expand,
                    ),
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    visibleNodes.forEachIndexed { visibleIndex, node ->
                        WorkProcessOperationRow(
                            node = node,
                            stepNumber = visibleStartIndex + visibleIndex + 1,
                            running = running && visibleStartIndex + visibleIndex == nodes.lastIndex,
                        )
                    }
                    if (collapsedHiddenCount > 0) {
                        DsButton(
                            text = if (showAllNodes) {
                                stringResource(R.string.local_work_process_show_recent)
                            } else {
                                stringResource(R.string.local_work_process_show_more, collapsedHiddenCount)
                            },
                            onClick = { showAllNodes = !showAllNodes },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkProcessOperationRow(
    node: LocalWorkProcessNode,
    stepNumber: Int,
    running: Boolean,
) {
    val colors = DsTheme.colors
    val kind = node.kind
    val icon = when (kind) {
        AgentOperationKind.Inspect -> FeatherIcons.FileText
        AgentOperationKind.Search -> Icons.Outlined.Search
        AgentOperationKind.Update -> Icons.Outlined.Tune
        AgentOperationKind.Execute -> Icons.Outlined.Terminal
        AgentOperationKind.Web -> Icons.Outlined.Extension
        AgentOperationKind.Device -> Icons.Outlined.QrCodeScanner
        AgentOperationKind.Image -> Icons.Outlined.Image
        AgentOperationKind.Background -> Icons.Outlined.Schedule
        AgentOperationKind.Delegate -> Icons.Outlined.PersonSearch
        AgentOperationKind.External -> Icons.Outlined.Extension
        AgentOperationKind.Generic -> Icons.Outlined.Tune
    }
    val family = when (kind) {
        AgentOperationKind.Update -> DsIconFamily.Green
        AgentOperationKind.Execute -> DsIconFamily.Cyan
        AgentOperationKind.Search,
        AgentOperationKind.Web -> DsIconFamily.Accent
        AgentOperationKind.Device -> DsIconFamily.Amber
        AgentOperationKind.Delegate -> DsIconFamily.Purple
        else -> DsIconFamily.Neutral
    }
    val operationLabel = stringResource(agentOperationLabelRes(kind))
    val detail = when {
        node.summary == null -> operationLabel
        node.count > 0 -> stringResource(
            R.string.local_work_process_step_operation_detail,
            stepNumber,
            operationLabel,
            node.count,
        )
        else -> stringResource(R.string.local_work_process_step_number, stepNumber)
    }
    val rowRunning = running && !node.failed

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsIconBox(icon = icon, family = family, active = rowRunning)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                node.summary ?: operationLabel,
                style = DsType.std14.withReadingWeight(),
                color = if (node.summary != null) colors.labelPrimary else colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.summary != null) {
                Text(
                    detail,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (node.summary == null && node.count > 1) {
            DsPill(text = stringResource(R.string.local_work_process_operation_count, node.count))
        }
        DsStatusPill(
            state = when {
                node.failed -> DsStatus.Failed
                rowRunning -> DsStatus.Running
                else -> DsStatus.Done
            },
            label = stringResource(
                agentOperationStatusRes(
                    running = rowRunning,
                    failed = node.failed,
                ),
            ),
        )
    }
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content
