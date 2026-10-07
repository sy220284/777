package com.labteto.dshmobile.ui.screens.local

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsTimeline
import com.labteto.dshmobile.ui.components.DsTimelineItem
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
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
            val kinds = if (kind in last.operationKinds) last.operationKinds else last.operationKinds + kind
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
                failed = message.toolIsError ?: toolResultFailed(message.content),
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

internal fun workProcessStatus(
    nodes: List<LocalWorkProcessNode>,
    running: Boolean,
): DsStatus = when {
    running -> DsStatus.Running
    nodes.any(LocalWorkProcessNode::failed) -> DsStatus.Warning
    else -> DsStatus.Done
}

@Composable
internal fun WorkProcessRow(
    messages: List<LocalHarnessMessage>,
    running: Boolean = false,
) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val nodes = remember(messages) { buildWorkProcessNodes(messages) }
    if (nodes.isEmpty()) return
    val processStatus = workProcessStatus(nodes, running)
    val processStatusLabel = stringResource(when (processStatus) {
        DsStatus.Running -> R.string.agent_operation_status_running
        DsStatus.Warning, DsStatus.Failed -> R.string.audit_attempt_failed
        else -> R.string.agent_operation_status_done
    })
    val processSurface = if (backgroundState.hasImage) {
        colors.wallpaperSurface(
            level = WallpaperSurfaceLevel.CARD,
            region = BackgroundRegion.MIDDLE,
        )
    } else {
        Color.Transparent
    }

    var expanded by remember(messages.first().id) { mutableStateOf(false) }
    var showAllNodes by remember(messages.first().id) { mutableStateOf(false) }
    val latestNode = nodes.last()
    val processFailed = nodes.any { it.failed }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.small)
            .clip(DsShapes.block)
            .background(processSurface)
            .padding(horizontal = DsSpacing.xsmall),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DsSpacing.touchTarget)
                .clip(DsShapes.row)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = disclosureState }
                .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            WorkOperationIcon(
                kind = latestNode.kind,
                running = processStatus == DsStatus.Running,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.local_work_process) + " · " + processStatusLabel,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    preview,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DsPill(text = stringResource(R.string.local_work_process_steps, nodes.size))
            Icon(
                FeatherIcons.ChevronRight,
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
            Column(
                modifier = Modifier.padding(start = DsSpacing.xsmall, top = DsSpacing.xsmall),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                val timelineItems = visibleNodes.mapIndexed { visibleIndex, node ->
                    val operationLabel = stringResource(agentOperationLabelRes(node.kind))
                    val stepNumber = visibleStartIndex + visibleIndex + 1
                    val rowRunning = running && stepNumber - 1 == nodes.lastIndex && !node.failed
                    val rowStatus = when {
                        node.failed -> DsStatus.Failed
                        rowRunning -> DsStatus.Running
                        else -> DsStatus.Done
                    }
                    val statusLabel = stringResource(
                        agentOperationStatusRes(
                            running = rowStatus == DsStatus.Running,
                            failed = rowStatus == DsStatus.Failed,
                        ),
                    )
                    val detail = when {
                        node.summary != null && node.count > 0 -> stringResource(
                            R.string.local_work_process_step_operation_detail,
                            stepNumber,
                            operationLabel,
                            node.count,
                        )
                        node.summary != null -> stringResource(
                            R.string.local_work_process_step_number,
                            stepNumber,
                        )
                        node.count > 1 -> stringResource(
                            R.string.local_work_process_operation_count,
                            node.count,
                        )
                        else -> stringResource(R.string.local_work_process_step_number, stepNumber)
                    }
                    DsTimelineItem(
                        text = node.summary ?: operationLabel,
                        detail = "$detail · $statusLabel",
                        state = rowStatus,
                    )
                }
                DsTimeline(
                    items = timelineItems,
                    modifier = Modifier.fillMaxWidth(),
                )
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

@Composable
private fun KimiAnimatedToolIcon(
    resId: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val drawable = remember(resId) {
        ImageDecoder.decodeDrawable(
            ImageDecoder.createSource(context.resources, resId),
        ) as? AnimatedImageDrawable
    }
    DisposableEffect(drawable) {
        drawable?.start()
        onDispose { drawable?.stop() }
    }
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(drawable)
            }
        },
        update = { imageView ->
            if (imageView.drawable !== drawable) imageView.setImageDrawable(drawable)
            if (drawable?.isRunning == false) drawable.start()
        },
        modifier = modifier,
    )
}

@Composable
private fun WorkOperationIcon(
    kind: AgentOperationKind,
    running: Boolean,
) {
    if (running && kind == AgentOperationKind.Generic) {
        KimiAnimatedToolIcon(
            resId = R.drawable.kimi_anim_tool_think,
            modifier = Modifier.size(34.dp),
        )
        return
    }
    when (kind) {
        AgentOperationKind.Delegate -> DsIconBox(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_kimi_create_subagent),
            active = running,
            modifier = Modifier.size(30.dp),
        )
        AgentOperationKind.External -> DsIconBox(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_kimi_plugin),
            active = running,
            modifier = Modifier.size(30.dp),
        )
        else -> {
            val icon = when (kind) {
                AgentOperationKind.Inspect -> FeatherIcons.FileText
                AgentOperationKind.Search -> FeatherIcons.Search
                AgentOperationKind.Update -> FeatherIcons.Edit3
                AgentOperationKind.Execute -> FeatherIcons.Code
                AgentOperationKind.Web -> FeatherIcons.Globe
                AgentOperationKind.Device -> FeatherIcons.Device
                AgentOperationKind.Image -> FeatherIcons.Image
                AgentOperationKind.Background -> FeatherIcons.Clock
                AgentOperationKind.Generic -> FeatherIcons.Tool
                AgentOperationKind.Delegate, AgentOperationKind.External -> FeatherIcons.Tool
            }
            DsIconBox(
                icon = icon,
                active = running,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content
