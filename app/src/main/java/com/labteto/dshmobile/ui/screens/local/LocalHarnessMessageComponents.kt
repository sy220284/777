package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import android.widget.Toast
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalChatBranchInfo
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.editableChatUserText
import com.labteto.dshmobile.local.groupMessageVisibleContent
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.ThinkingRow
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import kotlinx.coroutines.launch

@Composable
internal fun localResourcePressureLabel(pressure: String): String = stringResource(
    when (pressure.lowercase()) {
        "high" -> R.string.local_resource_pressure_high
        "medium" -> R.string.local_resource_pressure_medium
        else -> R.string.local_resource_pressure_low
    },
)

@Composable
internal fun localConversationModeLabel(mode: LocalConversationMode): String = stringResource(
    when (mode) {
        LocalConversationMode.INDEPENDENT -> R.string.local_context_source_independent
        LocalConversationMode.PROJECT -> R.string.local_context_source_project
        LocalConversationMode.CONTINUATION -> R.string.local_context_source_continuation
    },
)

@Composable
internal fun localJobStatusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.jobs_running)
    "completed" -> stringResource(R.string.jobs_completed)
    "killed", "cancelled" -> stringResource(R.string.jobs_killed)
    "failed" -> stringResource(R.string.jobs_failed)
    "interrupted" -> stringResource(R.string.local_run_job_interrupted)
    else -> status
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun LocalMessageRow(
    message: LocalHarnessMessage,
    chatMode: Boolean,
    groupMode: Boolean,
    canEdit: Boolean,
    canRegenerate: Boolean,
    canSelectVariant: Boolean,
    branchInfo: LocalChatBranchInfo?,
    onEdit: (LocalHarnessMessage) -> Unit,
    onSelectVariant: suspend (String, Int) -> Boolean,
    onRegenerate: (String) -> Boolean,
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.chat_copy_success)
    val regenerateFailedMessage = stringResource(R.string.local_regenerate_reply_failed)
    val variantSelectionFailedMessage = stringResource(R.string.local_select_variant_failed)
    val variantScope = rememberCoroutineScope()
    var selectingVariant by remember(message.id) { mutableStateOf(false) }
    val selectVariantWithFeedback: (String, Int) -> Unit = { id, index ->
        if (!selectingVariant) {
            selectingVariant = true
            variantScope.launch {
                try {
                    val selected = onSelectVariant(id, index)
                    if (!selected) {
                        Toast.makeText(context, variantSelectionFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    selectingVariant = false
                }
            }
        }
    }

    when (message.role) {
        "user" -> {
            var actionsOpen by remember(message.id) { mutableStateOf(false) }
            val copyText = editableChatUserText(message)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Box {
                    Box(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { actionsOpen = true },
                        ),
                    ) {
                        UserBubble(message.content)
                    }
                    DsPopupMenu(
                        expanded = actionsOpen,
                        onDismiss = { actionsOpen = false },
                        items = buildList {
                            if (canEdit) {
                                add(
                                    MenuItem(
                                        text = stringResource(R.string.local_edit_user_message),
                                        icon = FeatherIcons.Edit3,
                                        onClick = { onEdit(message) },
                                    ),
                                )
                            }
                            if (copyText.isNotBlank()) {
                                add(
                                    MenuItem(
                                        text = stringResource(R.string.common_copy),
                                        icon = Icons.Outlined.ContentCopy,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(copyText))
                                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
                if (chatMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MessageVariantControls(
                            messageId = message.id,
                            branchInfo = branchInfo,
                            enabled = canSelectVariant && !selectingVariant,
                            onSelectVariant = selectVariantWithFeedback,
                        )
                    }
                }
            }
        }

        "system" -> Unit

        "reasoning", "tool", "progress" -> WorkProcessRow(listOf(message))

        else -> {
            val readingLayer = chatMode && backgroundState.hasImage
            val readingModifier = if (readingLayer) {
                Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundState.wallpaperSurface(
                            base = colors.bgBase,
                            level = WallpaperSurfaceLevel.CARD,
                            region = BackgroundRegion.MIDDLE,
                        ),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                readingModifier,
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                if (groupMode) {
                    message.speakerName?.takeIf(String::isNotBlank)?.let { speaker ->
                        Text(
                            speaker,
                            style = DsType.small13Strong,
                            color = colors.accent,
                        )
                    }
                }
                val visibleContent = if (groupMode) groupMessageVisibleContent(message) else message.content
                MarkdownText(visibleContent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chatMode) {
                        MessageVariantControls(
                            messageId = message.id,
                            branchInfo = branchInfo,
                            enabled = canSelectVariant && !selectingVariant,
                            onSelectVariant = selectVariantWithFeedback,
                        )
                    }
                    CompactMessageAction(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_answer),
                        onClick = {
                            clipboard.setText(AnnotatedString(visibleContent))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        },
                    )
                    if (canRegenerate) CompactMessageAction(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.local_regenerate_reply),
                        onClick = {
                            if (!onRegenerate(message.id)) {
                                Toast.makeText(context, regenerateFailedMessage, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageVariantControls(
    messageId: String,
    branchInfo: LocalChatBranchInfo?,
    enabled: Boolean,
    onSelectVariant: (String, Int) -> Unit,
) {
    val info = branchInfo ?: return
    val colors = DsTheme.colors
    CompactMessageAction(
        icon = Icons.Filled.KeyboardArrowLeft,
        contentDescription = stringResource(R.string.local_previous_variant),
        onClick = { onSelectVariant(messageId, info.index - 1) },
        enabled = enabled && info.hasPrevious,
    )
    Text(
        text = stringResource(R.string.local_variant_position, info.index + 1, info.count),
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
    CompactMessageAction(
        icon = Icons.Filled.KeyboardArrowRight,
        contentDescription = stringResource(R.string.local_next_variant),
        onClick = { onSelectVariant(messageId, info.index + 1) },
        enabled = enabled && info.hasNext,
    )
}

@Composable
private fun CompactMessageAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(10.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.labelTertiary.copy(alpha = if (enabled) 0.82f else 0.34f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal data class LocalWorkProcessNode(
    val kind: AgentOperationKind? = null,
    val thinkingSummary: String? = null,
    val failed: Boolean = false,
    val count: Int = 1,
) {
    val isThinking: Boolean get() = thinkingSummary != null
}

internal fun compactProcessSummary(
    text: String,
    maxChars: Int = 180,
): String? {
    val bounded = if (text.length > 1_200) text.takeLast(1_200) else text
    val normalized = bounded
        .replace(Regex("`[^`]*`"), " ")
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("(?:(?:[A-Za-z]:\\\\)|/)(?:[^\\s/]+[/\\\\]){1,}[^\\s]+"), " ")
        .replace(
            Regex(
                "\\b[^\\s/\\\\]+\\.(?:kt|kts|java|xml|json|md|txt|py|js|ts|tsx|jsx|swift|gradle|toml|yaml|yml)\\b",
                RegexOption.IGNORE_CASE,
            ),
            " ",
        )
        .lineSequence()
        .joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank() || normalized.startsWith("{") || normalized.startsWith("[")) return null
    if (normalized.length <= maxChars) return normalized
    return "…" + normalized.takeLast((maxChars - 1).coerceAtLeast(1)).trimStart()
}

internal fun buildWorkProcessNodes(messages: List<LocalHarnessMessage>): List<LocalWorkProcessNode> {
    if (messages.isEmpty()) return emptyList()
    val nodes = mutableListOf<LocalWorkProcessNode>()

    fun appendThinking(text: String) {
        val summary = compactProcessSummary(text) ?: return
        val last = nodes.lastOrNull()
        if (last?.isThinking == true) {
            val combined = compactProcessSummary(
                listOfNotNull(last.thinkingSummary, summary).joinToString(" · "),
            ) ?: summary
            nodes[nodes.lastIndex] = last.copy(thinkingSummary = combined)
        } else {
            nodes += LocalWorkProcessNode(thinkingSummary = summary)
        }
    }

    fun appendOperation(kind: AgentOperationKind, failed: Boolean) {
        val last = nodes.lastOrNull()
        if (last != null && !last.isThinking && last.kind == kind) {
            nodes[nodes.lastIndex] = last.copy(
                failed = last.failed || failed,
                count = last.count + 1,
            )
        } else {
            nodes += LocalWorkProcessNode(kind = kind, failed = failed)
        }
    }

    messages.forEach { message ->
        when (message.role) {
            // Free-form model reasoning is never surfaced as product UI. Work process rows are
            // built from observable execution events instead.
            "reasoning", "assistant" -> Unit
            "tool" -> appendOperation(
                kind = agentOperationKind(message.toolName),
                failed = toolResultFailed(message.content),
            )
            // Progress rows are intentionally omitted: the semantic operation nodes already say
            // what happened without repeating raw file/tool narration.
            "progress" -> Unit
        }
    }

    return if (nodes.isNotEmpty()) nodes else listOf(LocalWorkProcessNode(kind = AgentOperationKind.Generic))
}

internal const val LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT = 8

internal fun visibleWorkProcessNodes(
    nodes: List<LocalWorkProcessNode>,
    showAll: Boolean,
    collapsedLimit: Int = LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT,
): List<LocalWorkProcessNode> {
    if (showAll || nodes.size <= collapsedLimit.coerceAtLeast(1)) return nodes
    return nodes.takeLast(collapsedLimit.coerceAtLeast(1))
}

internal fun compactReasoningSummary(
    messages: List<LocalHarnessMessage>,
    maxChars: Int = 180,
): String? = compactProcessSummary(
    messages.asSequence()
        .filter { it.role == "reasoning" }
        .mapNotNull { compactProcessSummary(it.content, maxChars = 120) }
        .distinct()
        .take(2)
        .joinToString(" · "),
    maxChars = maxChars,
)

@Composable
internal fun ChatThinkingRow(
    messages: List<LocalHarnessMessage>,
    streaming: Boolean = false,
) {
    if (messages.isEmpty() || !streaming) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        StateDot(StateDotState.Running, size = 8.dp)
        Text(
            stringResource(R.string.local_process_thinking),
            style = DsType.small13,
            color = DsTheme.colors.labelTertiary,
        )
    }
}

@Composable
internal fun WorkProcessRow(
    messages: List<LocalHarnessMessage>,
    running: Boolean = false,
) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    val nodes = remember(messages) { buildWorkProcessNodes(messages) }
    var expanded by remember(messages.first().id) { mutableStateOf(false) }
    var showAllNodes by remember(messages.first().id) { mutableStateOf(false) }
    val thinkingPreview = nodes.lastOrNull { it.isThinking }?.thinkingSummary
    val latestOperation = nodes.lastOrNull { !it.isThinking }?.kind
    val preview = thinkingPreview?.let { compactProcessSummary(it, maxChars = 86) }
        ?: latestOperation?.let { stringResource(agentOperationLabelRes(it)) }
    val collapsedHiddenCount = (nodes.size - LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT).coerceAtLeast(0)
    val visibleNodes = visibleWorkProcessNodes(nodes, showAllNodes)
    val disclosureState = stringResource(
        if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed,
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
                        style = DsType.small13Strong,
                        color = colors.labelPrimary,
                    )
                    preview?.let {
                        Text(
                            it,
                            style = DsType.caption11,
                            color = colors.labelTertiary,
                            maxLines = 1,
                        )
                    }
                }
                if (running) {
                    DsStatusPill(
                        state = DsStatus.Running,
                        label = stringResource(agentOperationStatusRes(running = true, failed = false)),
                    )
                }
                DsPill(text = stringResource(R.string.local_work_process_nodes, nodes.size))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(
                        if (expanded) R.string.local_work_process_collapse else R.string.local_work_process_expand,
                    ),
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (expanded) {
                visibleNodes.forEach { node ->
                    if (node.isThinking) {
                        WorkThinkingNodeRow(node.thinkingSummary.orEmpty())
                    } else {
                        WorkProcessOperationRow(
                            kind = node.kind ?: AgentOperationKind.Generic,
                            failed = node.failed,
                            count = node.count,
                        )
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
private fun WorkThinkingNodeRow(summary: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsIconBox(icon = Icons.Outlined.Tune, family = DsIconFamily.Neutral)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.local_process_thinking),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            Text(
                summary,
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun WorkProcessOperationRow(
    kind: AgentOperationKind,
    failed: Boolean,
    count: Int,
) {
    val colors = DsTheme.colors
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsIconBox(icon = icon, family = family)
        Text(
            stringResource(agentOperationLabelRes(kind)),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        if (count > 1) {
            DsPill(text = count.toString())
        }
        DsStatusPill(
            state = if (failed) DsStatus.Failed else DsStatus.Done,
            label = stringResource(agentOperationStatusRes(running = false, failed = failed)),
        )
    }
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content

internal const val LOCAL_TRANSCRIPT_INITIAL_WINDOW_MESSAGES = 200
internal const val MAX_LOCAL_IMAGE_SELECTION = 20


internal fun decodeLocalAttachmentThumbnail(
    workspacePath: String,
    relativePath: String,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val root = File(workspacePath).canonicalFile
    val file = File(root, relativePath).canonicalFile
    require(file.toPath().startsWith(root.toPath()) && file.isFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 160 || bounds.outHeight / (sample * 2) >= 160) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}.getOrNull()

