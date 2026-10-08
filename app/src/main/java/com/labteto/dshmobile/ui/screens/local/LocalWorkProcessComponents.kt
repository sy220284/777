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
import androidx.compose.runtime.LaunchedEffect
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
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
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

internal const val LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT = 5
internal const val LOCAL_WORK_PROCESS_SUMMARY_LIMIT = 260

private val WORK_PROCESS_WHITESPACE = Regex("\\s+")
private val WORK_PROCESS_TECHNICAL_LINE = Regex(
    "^(?:[>$#]\\s*|(?:git|curl|grep|rg|adb|gradle|npm|python|bash|sh)\\s+|[\\\\/]|\\x60{3})",
    RegexOption.IGNORE_CASE,
)
private val WORK_PROCESS_PATH = Regex("(?:[A-Za-z]:)?(?:[\\\\/][A-Za-z0-9_.-]+){2,}")

internal data class LocalWorkProcessNode(
    val summary: String? = null,
    val operationKinds: List<AgentOperationKind> = emptyList(),
    val failed: Boolean = false,
    val count: Int = 0,
    val toolContent: String? = null,
    val toolName: String? = null,
) {
    val kind: AgentOperationKind
        get() = operationKinds.firstOrNull() ?: AgentOperationKind.Generic
}

internal fun localWorkProcessSummary(content: String): String? {
    val compact = WORK_PROCESS_WHITESPACE.replace(content.trim(), " ")
    if (compact.isBlank() || WORK_PROCESS_TECHNICAL_LINE.containsMatchIn(compact)) return null
    val readable = WORK_PROCESS_PATH.replace(compact, "…")
    return truncateWithoutSplittingSurrogatePair(readable, LOCAL_WORK_PROCESS_SUMMARY_LIMIT)
}

internal fun buildWorkProcessNodes(messages: List<LocalHarnessMessage>): List<LocalWorkProcessNode> {
    if (messages.isEmpty()) return emptyList()
    val nodes = mutableListOf<LocalWorkProcessNode>()

    fun appendSummary(content: String) {
        localWorkProcessSummary(content)?.let { summary ->
            nodes += LocalWorkProcessNode(summary = summary)
        }
    }

    fun appendOperation(message: LocalHarnessMessage) {
        nodes += LocalWorkProcessNode(
            operationKinds = listOf(agentOperationKind(message.toolName)),
            failed = message.toolIsError ?: toolResultFailed(message.content),
            count = 1,
            toolContent = message.content,
            toolName = message.toolName,
        )
    }

    messages.forEach { message ->
        when (message.role) {
            "progress", "assistant" -> appendSummary(message.content)
            "tool" -> appendOperation(message)
        }
    }

    return nodes
}

/** Associate durable tool results with their semantic milestone without exposing raw output. */
internal fun semanticWorkProcessNodes(nodes: List<LocalWorkProcessNode>): List<LocalWorkProcessNode> {
    val result = mutableListOf<LocalWorkProcessNode>()
    nodes.forEach { node ->
        val milestone = result.lastOrNull()
        if (node.summary == null && milestone?.summary != null) {
            result[result.lastIndex] = milestone.copy(
                operationKinds = (milestone.operationKinds + node.operationKinds).distinct(),
                failed = milestone.failed || node.failed,
                count = milestone.count + node.count,
            )
        } else {
            result += node.copy(toolContent = null)
        }
    }
    return result
}

/**
 * Compose projection keeps the exact persisted event order: narration, tool outcome,
 * narration, tool outcome. The Run Center retains raw results and diagnostic details.
 */
internal fun conversationWorkProcessNodes(nodes: List<LocalWorkProcessNode>): List<LocalWorkProcessNode> =
    nodes.map { it.copy(toolContent = null) }

internal fun visibleWorkProcessNodes(
    nodes: List<LocalWorkProcessNode>,
    showAll: Boolean,
    collapsedLimit: Int = LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT,
): List<LocalWorkProcessNode> {
    if (showAll || nodes.size <= collapsedLimit.coerceAtLeast(1)) return nodes
    return nodes.takeLast(collapsedLimit.coerceAtLeast(1))
}

internal fun workProcessFocus(nodes: List<LocalWorkProcessNode>): LocalWorkProcessNode =
    nodes.lastOrNull { it.failed } ?: nodes.last()

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
    if (nodes.isEmpty()) {
        // Do not render private reasoning; keep a visible thinking state until progress arrives.
        if (messages.any { it.role == "reasoning" }) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                WorkOperationIcon(AgentOperationKind.Generic, running = running)
                Text(
                    stringResource(if (running) R.string.local_work_thinking else R.string.local_work_thought),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelTertiary,
                )
            }
        }
        return
    }
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

    var expanded by remember(messages.first().id) { mutableStateOf(running) }
    var showAllNodes by remember(messages.first().id) { mutableStateOf(false) }
    // Reveal live milestones by default; collapse to the result-first summary on completion.
    LaunchedEffect(messages.first().id, running) {
        expanded = running
    }
    // Do not merge prose into tool rows: the dialogue must read in event order.
    val semanticNodes = remember(nodes) { conversationWorkProcessNodes(nodes) }
    val firstReasoningTimestamp = messages.firstOrNull {
        it.role == "reasoning" && it.createdAt > 0L
    }?.createdAt
    val firstProgressTimestamp = messages.firstOrNull {
        (it.role == "progress" || it.role == "tool") && it.createdAt > 0L
    }?.createdAt
    // Persisted event timestamps must be present and ordered. Unknown durations stay hidden.
    val thinkingSeconds = if (firstReasoningTimestamp != null && firstProgressTimestamp != null) {
        (firstProgressTimestamp - firstReasoningTimestamp)
            .takeIf { it in 1_000L..600_000L }
            ?.div(1_000L)
    } else null
    val latestNode = workProcessFocus(semanticNodes)
    val preview = if (latestNode.failed) {
        stringResource(R.string.local_work_failed_stage, latestNode.summary ?: stringResource(agentOperationLabelRes(latestNode.kind)))
    } else latestNode.summary ?: stringResource(agentOperationLabelRes(latestNode.kind))
    val collapsedHiddenCount = (semanticNodes.size - LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT).coerceAtLeast(0)
    val visibleNodes = visibleWorkProcessNodes(semanticNodes, showAllNodes)
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
                running = processStatus == DsStatus.Running && !latestNode.failed,
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
            DsPill(text = stringResource(R.string.local_work_process_stage_operations, semanticNodes.size, nodes.sumOf { it.count }))
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

        if (messages.any { it.role == "reasoning" }) {
            Row(
                modifier = Modifier.padding(start = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Text(
                    text = if (thinkingSeconds != null) {
                        stringResource(R.string.local_work_thought_seconds, thinkingSeconds)
                    } else {
                        stringResource(
                            if (running && nodes.none { it.summary != null })
                                R.string.local_work_thinking
                            else R.string.local_work_thought,
                        )
                    },
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
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
                visibleNodes.forEach { node ->
                    if (node.summary != null) {
                        // Model-authored, user-facing status narrative, never provider reasoning.
                        Text(
                            text = node.summary,
                            style = DsType.mdBody.withReadingWeight(),
                            color = colors.labelPrimary,
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = DsSpacing.small,
                                vertical = DsSpacing.small,
                            ),
                        )
                    } else {
                        // A completed tool outcome keeps its own icon/status and never leaks arguments.
                        WorkContentBlock(node, running = false)
                    }
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

@Composable
private fun AnimatedToolIcon(
    resId: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val drawable = remember(resId) {
        runCatching {
            ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(context.resources, resId),
            ) as? AnimatedImageDrawable
        }.getOrNull()
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
internal fun WorkOperationIcon(
    kind: AgentOperationKind,
    running: Boolean,
) {
    val animatedRes = when (kind) {
        AgentOperationKind.Inspect -> R.drawable.work_anim_tool_file
        AgentOperationKind.Search -> R.drawable.work_anim_tool_search
        AgentOperationKind.Update, AgentOperationKind.Execute -> R.drawable.work_anim_tool_code
        AgentOperationKind.Web -> R.drawable.work_anim_tool_web
        AgentOperationKind.Generic -> R.drawable.work_anim_tool_think
        AgentOperationKind.Delegate -> R.drawable.work_anim_tool_create_subagent
        AgentOperationKind.Image -> R.drawable.work_anim_tool_image
        AgentOperationKind.External -> R.drawable.work_anim_tool_mcp
        AgentOperationKind.Background -> R.drawable.work_anim_tool_task
        AgentOperationKind.Device -> R.drawable.work_anim_tool_browser
        else -> null
    }
    if (running && animatedRes != null) {
        AnimatedToolIcon(
            resId = animatedRes,
            modifier = Modifier.size(34.dp),
        )
        return
    }
    when (kind) {
        AgentOperationKind.Delegate -> DsIconBox(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_ui_create_subagent),
            active = running,
            modifier = Modifier.size(30.dp),
        )
        AgentOperationKind.External -> DsIconBox(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_ui_plugin),
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
