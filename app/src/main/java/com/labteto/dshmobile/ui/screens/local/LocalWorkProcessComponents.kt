package com.labteto.dshmobile.ui.screens.local

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
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
    "^(?:[>$#]\\s*|(?:git|curl|grep|rg|adb|gradle|npm|python|bash|sh)\\s+|[\\\\/]|\\x60{3}|(?:运行任务步骤|处理当前步骤|检查相关内容|查找相关信息|正在运行任务步骤)$)",
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
        val previous = result.lastOrNull()
        when {
            node.summary == null && previous?.summary != null -> {
                // A tool completed after the narration that described it.
                result[result.lastIndex] = previous.copy(
                    operationKinds = (previous.operationKinds + node.operationKinds).distinct(),
                    failed = previous.failed || node.failed,
                    count = previous.count + node.count,
                )
            }
            node.summary != null && previous?.summary == null && result.isNotEmpty() -> {
                // Some providers persist the operation before its visible explanation.
                // Attach consecutive unlabelled tools to the next real progress sentence.
                val start = result.indexOfLast { it.summary != null } + 1
                val tools = result.subList(start, result.size).toList()
                result.subList(start, result.size).clear()
                result += node.copy(
                    operationKinds = (tools.flatMap { it.operationKinds } + node.operationKinds).distinct(),
                    failed = tools.any { it.failed } || node.failed,
                    count = tools.sumOf { it.count } + node.count,
                )
            }
            else -> result += node.copy(toolContent = null)
        }
    }
    return result.map { it.copy(toolContent = null) }
}

/**
 * Compose projection keeps the exact persisted event order: narration, tool outcome,
 * narration, tool outcome. The Run Center retains raw results and diagnostic details.
 */
internal fun conversationWorkProcessNodes(nodes: List<LocalWorkProcessNode>): List<LocalWorkProcessNode> =
    semanticWorkProcessNodes(nodes)

internal fun visibleWorkProcessNodes(
    nodes: List<LocalWorkProcessNode>,
    showAll: Boolean,
    collapsedLimit: Int = LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT,
): List<LocalWorkProcessNode> {
    if (showAll || nodes.size <= collapsedLimit.coerceAtLeast(1)) return nodes
    val limit = collapsedLimit.coerceAtLeast(1)
    val recent = nodes.takeLast(limit)
    val failed = nodes.indexOfLast { it.failed }
    if (failed < 0 || failed >= nodes.size - limit) return recent
    // Show a hidden failed milestone alongside the newest steps.
    return listOf(nodes[failed]) + nodes.takeLast((limit - 1).coerceAtLeast(0))
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
    val processSurface = if (backgroundState.hasImage) {
        colors.wallpaperSurface(
            level = WallpaperSurfaceLevel.CARD,
            region = BackgroundRegion.MIDDLE,
        )
    } else {
        Color.Transparent
    }

    var expanded by remember(messages.first().id) { mutableStateOf(running) }
    var manuallyToggled by remember(messages.first().id) { mutableStateOf(false) }
    var showAllNodes by remember(messages.first().id) { mutableStateOf(false) }
    // Automatically fold only if the reader has not chosen their own disclosure state.
    LaunchedEffect(messages.first().id, running) {
        if (!manuallyToggled) expanded = running
    }
    // Keep event order, but merge tool outcomes into their immediately preceding narration.
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
    val latestNode = if (running) semanticNodes.last() else workProcessFocus(semanticNodes)
    // The heading represents overall state; the detailed narration belongs exclusively
    // to the step below. Repeating the active summary breaks reading and accessibility.
    val headerLabel = when {
        processStatus == DsStatus.Warning || processStatus == DsStatus.Failed ->
            stringResource(R.string.local_work_process_warning)
        running -> stringResource(R.string.local_work_process_running)
        thinkingSeconds != null -> stringResource(R.string.local_work_thought_seconds, thinkingSeconds)
        messages.any { it.role == "reasoning" } -> stringResource(R.string.local_work_thought)
        else -> stringResource(R.string.local_work_process)
    }
    val visibleNodes = visibleWorkProcessNodes(semanticNodes, showAllNodes)
    val collapsedHiddenCount = (semanticNodes.size - visibleWorkProcessNodes(semanticNodes, false).size).coerceAtLeast(0)
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
                .clickable(role = Role.Button) {
                    manuallyToggled = true
                    expanded = !expanded
                }
                .semantics { stateDescription = disclosureState }
                .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            when {
                processStatus == DsStatus.Warning || processStatus == DsStatus.Failed -> Icon(
                    imageVector = FeatherIcons.AlertTriangle,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(18.dp),
                )
                processStatus == DsStatus.Running -> WorkOperationIcon(
                    kind = latestNode.kind, running = true,
                )
                else -> Icon(
                    imageVector = FeatherIcons.CheckCircle,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                headerLabel,
                modifier = Modifier.weight(1f),
                style = DsType.small13.withReadingWeight(),
                color = if (processStatus == DsStatus.Warning || processStatus == DsStatus.Failed) {
                    colors.error
                } else colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                visibleNodes.forEachIndexed { index, node ->
                    // One semantic stage per visible row. Tool results are attached to their
                    // preceding narration, instead of showing a duplicate generic tool title.
                    WorkContentBlock(
                        node = node,
                        running = running && !node.failed &&
                            index == visibleNodes.lastIndex &&
                            semanticNodes.last() == node,
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

/** Consistent Feather outline icon; only the active step receives accent colour. */
@Composable
internal fun WorkOperationIcon(
    kind: AgentOperationKind,
    running: Boolean,
) {
    val icon = when (kind) {
        AgentOperationKind.Inspect -> FeatherIcons.FileText
        AgentOperationKind.Search -> FeatherIcons.Search
        AgentOperationKind.Update -> FeatherIcons.Edit3
        AgentOperationKind.Execute -> FeatherIcons.Code
        AgentOperationKind.Web -> FeatherIcons.Globe
        AgentOperationKind.Device -> FeatherIcons.Device
        AgentOperationKind.Image -> FeatherIcons.Image
        AgentOperationKind.Background -> FeatherIcons.Clock
        AgentOperationKind.Generic -> FeatherIcons.Sparkles
        AgentOperationKind.Delegate -> FeatherIcons.Users
        AgentOperationKind.External -> FeatherIcons.Gear
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (running) DsTheme.colors.accent else DsTheme.colors.labelSecondary,
        modifier = Modifier.size(18.dp),
    )
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content
