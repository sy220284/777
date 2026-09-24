package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.CompactionNode
import com.labteto.dshmobile.core.session.GoalNode
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.PlanModeNode
import com.labteto.dshmobile.core.session.RetryNode
import com.labteto.dshmobile.core.session.SubagentNode
import com.labteto.dshmobile.core.session.TitleNode
import com.labteto.dshmobile.core.session.TodoNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnErrorNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.WorkflowNode
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.AttachmentImage
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DisclosureState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Everything one transcript row needs that is not on the node itself. */
internal data class ChatNodeContext(
    val nodes: List<ChatNode>,
    val running: Boolean,
    val cwd: String?,
    /** Host account home retained for context compatibility. */
    val home: String? = null,
    val onOpenSubagent: (String) -> Unit,
    val onBranchFrom: (Long) -> Unit,
    val onFeedback: (Long, Boolean) -> Unit,
    val onCopied: () -> Unit = {},
    val eventTimes: Map<Long, Long> = emptyMap(),
)

/**
 * One node of the conversation. The `when` is exhaustive over [ChatNode] on purpose: a harness that
 * grows a new event type still renders, because the fold produces an `OtherNode` rather than
 * dropping it, and this shows it rather than a gap in the transcript.
 */
@Composable
internal fun ChatNodeItem(node: ChatNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    when (node) {
        // Turn boundaries are structure, not content — the transcript shows the work, not the frame.
        is TurnStartNode -> Unit

        is UserMessageNode -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.blocks.filter { it.kind == "image" }.forEach { block ->
                parseImageRef(block)?.let { ref ->
                    AttachmentImage(
                        attachmentId = ref.attachmentId,
                        intrinsicWidth = ref.width,
                        intrinsicHeight = ref.height,
                        contentDescription = ref.name,
                    )
                }
            }
            // A file is a chip, not a preview: the host stores it verbatim and the model reads it
            // through its file tools, so there is nothing to render but what it is called.
            node.blocks.filter { it.kind == "file" }.forEach { block ->
                parseFileRef(block)?.let { ref ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FileChip(name = ref.name, bytes = ref.bytes)
                    }
                }
            }
            val text = node.displayText()
            if (text.isNotBlank()) UserBubble(text)
        }

        is AssistantMessageNode -> AssistantMessage(node, context)

        is ToolCallNode -> ToolCallRow(node, context)

        // Rendered inside the matching ToolCallNode's card; not a standalone row.
        is ToolResultNode -> Unit

        is TurnEndNode -> when (node.reasonKind) {
            "completed" -> Unit
            "aborted", "interrupted" -> DsPill(text = stringResource(R.string.chat_stopped), warn = true)
            "error" -> Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.chat_error_turn),
                    style = DsType.small13,
                    color = colors.error,
                )
            }
            "max-tokens" -> DsPill(text = stringResource(R.string.chat_max_tokens), warn = true)
            else -> Unit
        }

        is TodoNode -> parseTodos(node.todos)?.let { TodoDock(it) }

        is GoalNode -> parseGoal(node.data)?.let { GoalSummary(it) }

        is PlanModeNode -> DsPill(
            text = stringResource(if (node.active) R.string.plan_mode_on else R.string.plan_mode_off),
            warn = true,
        )

        is CompactionNode -> CompactionRow()

        is RetryNode -> {
            val delayMs = (node.data as? JsonObject)?.let { obj ->
                obj["delayMs"].asLong() ?: obj["ms"].asLong() ?: obj["providerRetryAfterMs"].asLong()
            }
            val label = if (delayMs != null && delayMs > 0) {
                stringResource(R.string.chat_retry_scheduled, (delayMs / 1000).toInt().coerceAtLeast(1))
            } else {
                // A retry with no stated delay used to read "Loading…", which says nothing about
                // what happened; four of them in a row before a failure is a story worth telling.
                stringResource(R.string.chat_retrying)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Warning, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(label, style = DsType.caption11, color = colors.labelTertiary)
            }
        }

        is TurnErrorNode -> Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.chat_error_turn),
                style = DsType.small13,
                color = colors.error,
            )
        }

        is CommandNode -> CommandRow(node)

        is WorkflowNode -> WorkflowRow(node.data)

        is TitleNode -> Text(node.title, style = DsType.caption11, color = colors.labelTertiary)
        is SubagentNode -> Text(
            stringResource(R.string.subagents_title),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        // Raw protocol/debug events are execution details. Only final deliverables remain visible.
        is OtherNode -> if (node.type == "deliverables/presented") {
            val open = com.labteto.dshmobile.ui.components.LocalFileOpener.current
            ((node.data as? JsonObject)?.get("files") as? JsonArray)?.forEach { file ->
                val obj = file as? JsonObject
                val path = obj?.get("path").asString()
                if (!path.isNullOrBlank()) {
                    androidx.compose.material3.OutlinedCard(
                        onClick = { open(path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(basename(path), style = DsType.std14)
                            obj?.get("description").asString()?.let { Text(it, style = DsType.small13) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One stored file in a message: its display name and exact size, nothing more.
 *
 * There is no preview and no download — the harness keeps the bytes for the agent's file tools,
 * and the reference the log carries is a content digest rather than a path or a URL. The chip
 * says what was sent, which is all a transcript needs.
 */
@Composable
internal fun FileChip(name: String, bytes: Long, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform)
            .border(1.dp, colors.borderL3, DsShapes.block)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                name,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(fileSizeText(bytes), style = DsType.caption11, color = colors.labelTertiary)
        }
    }
}

// ---------------------------------------------------------------------------
// Assistant messages
// ---------------------------------------------------------------------------

@Composable
private fun AssistantMessage(node: AssistantMessageNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val isLast = context.nodes.lastOrNull()?.seq == node.seq
    val streaming = context.running && isLast && !node.interrupted
    val isWorkProcess = node.isWorkProcess(context.nodes)
    var actionsVisible by remember(node.seq) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    if (isWorkProcess) return

    // A running conversation does not make the durable/streaming answer itself execution detail.
    // Only protocol-matched work-process narration is hidden; user-facing answer text stays visible.
    if (!streaming && !node.interrupted && !node.isFinalAnswerAnchor(context.nodes)) return
    val finalText = node.finalAnswerText(context.nodes).ifBlank { node.plainText }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL2),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !streaming) { actionsVisible = !actionsVisible }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.chat_final_answer),
                    style = DsType.small13Strong,
                    color = colors.labelSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!streaming && finalText.isNotBlank()) {
                    DsButton(
                        text = stringResource(R.string.chat_copy_answer),
                        onClick = {
                            clipboard.setText(AnnotatedString(finalText))
                            context.onCopied()
                        },
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }

            node.blocks.forEach { block ->
                when (block.kind) {
                    "reasoning" -> Unit
                    "image" -> parseImageRef(block)?.let { ref ->
                        AttachmentImage(
                            attachmentId = ref.attachmentId,
                            intrinsicWidth = ref.width,
                            intrinsicHeight = ref.height,
                            contentDescription = ref.name,
                        )
                    }
                    "file" -> parseFileRef(block)?.let { ref ->
                        FileChip(name = ref.name, bytes = ref.bytes)
                    }
                    else -> Unit
                }
            }

            if (finalText.isNotBlank()) {
                MarkdownText(finalText)
            }
            if (node.interrupted) {
                DsPill(text = stringResource(R.string.chat_stopped), warn = true)
            }
            AnimatedVisibility(
                visible = actionsVisible && !streaming,
                enter = fadeIn(DsAnimations.fade),
                exit = fadeOut(DsAnimations.fade),
            ) {
                MessageActionsRow(node, context)
            }
        }
    }
}

/**
 * Branching and feedback remain secondary tap actions. Copy is intentionally absent here: completed
 * answers expose one persistent copy button, while work-process rows expose none.
 */
@Composable
private fun MessageActionsRow(node: AssistantMessageNode, context: ChatNodeContext) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.AutoMirrored.Outlined.CallSplit, stringResource(R.string.chat_branch_message)) {
            context.onBranchFrom(node.seq)
        }
        ActionIcon(Icons.Filled.ThumbUp, stringResource(R.string.chat_feedback_up)) {
            context.onFeedback(node.seq, true)
        }
        ActionIcon(Icons.Filled.ThumbDown, stringResource(R.string.chat_feedback_down)) {
            context.onFeedback(node.seq, false)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = label,
        tint = DsTheme.colors.labelTertiary,
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .padding(12.dp),
    )
}

// ---------------------------------------------------------------------------
// Tool calls
// ---------------------------------------------------------------------------

@Composable
private fun ToolCallRow(node: ToolCallNode, context: ChatNodeContext) {
    val kind = agentOperationKind(node.name)
    val peers = if (node.turn == null) {
        listOf(node)
    } else {
        context.nodes.filterIsInstance<ToolCallNode>()
            .filter { it.turn == node.turn && agentOperationKind(it.name) == kind }
    }
    val results = context.nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
    val failed = peers.any { results[it.callId]?.isError == true }
    val running = context.running && peers.any { results[it.callId] == null }
    val state = when {
        failed -> DisclosureState.Error
        running -> DisclosureState.Running
        else -> DisclosureState.Idle
    }
    DisclosureRow(
        title = stringResource(agentOperationLabelRes(node.name)),
        summary = stringResource(agentOperationStatusRes(running = running, failed = failed)),
        icon = FeatherIcons.Tool,
        state = state,
        expanded = false,
        onToggle = null,
    )
}


// ---------------------------------------------------------------------------
// Compaction / commands / workflow
// ---------------------------------------------------------------------------

@Composable
private fun CompactionRow() {
    DisclosureRow(
        title = stringResource(R.string.chat_compaction),
        summary = stringResource(R.string.chat_compaction_summary),
        icon = FeatherIcons.Archive,
        expanded = false,
        onToggle = null,
    )
}

@Composable
private fun CommandRow(node: CommandNode) {
    val running = node.kind == "command/run"
    DisclosureRow(
        title = stringResource(R.string.agent_operation_execute),
        summary = stringResource(agentOperationStatusRes(running = running, failed = false)),
        icon = FeatherIcons.Tool,
        state = if (running) DisclosureState.Running else DisclosureState.Idle,
        expanded = false,
        onToggle = null,
    )
}

@Composable
private fun WorkflowRow(
    data: kotlinx.serialization.json.JsonElement,
) {
    val obj = data as? JsonObject ?: return
    val status = obj["status"].asString() ?: obj["stopReason"].asString() ?: obj["outcome"].asString()
    DisclosureRow(
        title = stringResource(R.string.agent_operation_delegate),
        summary = workflowStatusLabel(status),
        icon = FeatherIcons.Tool,
        expanded = false,
        onToggle = null,
    )
}
