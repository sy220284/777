package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.LocalChatBranchInfo
import com.labteto.dshmobile.local.presentation.editableChatUserText
import com.labteto.dshmobile.local.presentation.groupMessageVisibleContent
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.visibleBlocks
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsContextActionMenu
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ThinkingRow
import com.labteto.dshmobile.ui.components.UserBubble
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    "dormant" -> stringResource(R.string.jobs_waiting_message)
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
    workspacePath: String = "",
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
                            onClick = { actionsOpen = false },
                            onLongClick = { actionsOpen = true },
                        ),
                    ) {
                        LocalUserMessageContent(
                            message = message,
                            workspacePath = workspacePath,
                        )
                    }
                    DsContextActionMenu(
                        expanded = actionsOpen,
                        onDismiss = { actionsOpen = false },
                        items = buildList {
                            if (copyText.isNotBlank()) {
                                add(
                                    MenuItem(
                                        text = stringResource(R.string.common_copy),
                                        icon = FeatherIcons.Copy,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(copyText))
                                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                                        },
                                    ),
                                )
                            }
                            if (canEdit) {
                                add(
                                    MenuItem(
                                        text = stringResource(R.string.local_edit_user_message),
                                        icon = FeatherIcons.Edit3,
                                        onClick = { onEdit(message) },
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
                    .border(
                        width = 1.dp,
                        color = if (backgroundState.darkTheme) {
                            Color.White.copy(alpha = 0.26f)
                        } else {
                            Color.Black.copy(alpha = 0.22f)
                        },
                        shape = RoundedCornerShape(18.dp),
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
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.characterAccent,
                        )
                    }
                }
                val visibleContent = if (groupMode) groupMessageVisibleContent(message) else message.content
                LocalAssistantMessageContent(
                    message = message,
                    workspacePath = workspacePath,
                    groupMode = groupMode,
                    chatMode = chatMode,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                    if (visibleContent.isNotBlank()) {
                        CompactMessageAction(
                            icon = FeatherIcons.Copy,
                            contentDescription = stringResource(R.string.chat_copy_answer),
                            onClick = {
                                clipboard.setText(AnnotatedString(visibleContent))
                                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    if (canRegenerate) CompactMessageAction(
                        icon = FeatherIcons.RefreshCw,
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
private fun LocalUserMessageContent(
    message: LocalHarnessMessage,
    workspacePath: String,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        message.visibleBlocks().forEach { block ->
            when (block) {
                is LocalMessageBlock.Text -> if (block.text.isNotBlank()) {
                    UserBubble(block.text)
                }
                is LocalMessageBlock.Image -> LocalTranscriptImage(
                    image = block,
                    workspacePath = workspacePath,
                )
                is LocalMessageBlock.File -> LocalTranscriptFile(block)
                is LocalMessageBlock.Unknown -> LocalTranscriptUnknown(block)
            }
        }
    }
}

@Composable
private fun LocalAssistantMessageContent(
    message: LocalHarnessMessage,
    workspacePath: String,
    groupMode: Boolean,
    chatMode: Boolean,
) {
    var firstText = true
    message.visibleBlocks().forEach { block ->
        when (block) {
            is LocalMessageBlock.Text -> {
                val text = if (groupMode && firstText) {
                    groupMessageVisibleContent(message)
                } else {
                    block.text
                }
                firstText = false
                if (text.isNotBlank()) {
                    MarkdownText(
                        text,
                        bodyStyle = if (chatMode) DsType.chatBody else DsType.mdBody,
                    )
                }
            }
            is LocalMessageBlock.Image -> LocalTranscriptImage(
                image = block,
                workspacePath = workspacePath,
            )
            is LocalMessageBlock.File -> LocalTranscriptFile(block)
            is LocalMessageBlock.Unknown -> LocalTranscriptUnknown(block)
        }
    }
}

@Composable
private fun LocalTranscriptImage(
    image: LocalMessageBlock.Image,
    workspacePath: String,
) {
    val colors = DsTheme.colors
    var bitmap by remember(workspacePath, image.relativePath) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(workspacePath, image.relativePath) {
        bitmap = if (workspacePath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                decodeLocalAttachmentThumbnail(
                    workspacePath = workspacePath,
                    relativePath = image.relativePath,
                    targetPx = 720,
                )
            }
        }
    }
    val ratio = if (
        image.width != null &&
        image.height != null &&
        image.width > 0 &&
        image.height > 0
    ) {
        (image.width.toFloat() / image.height.toFloat()).coerceIn(0.45f, 2.2f)
    } else {
        1f
    }
    val modifier = Modifier
        .fillMaxWidth(0.72f)
        .widthIn(max = 440.dp)
        .aspectRatio(ratio)
        .clip(DsShapes.block)
        .background(colors.wallpaperSurface(WallpaperSurfaceLevel.CARD))
        .border(1.dp, colors.borderL3, DsShapes.block)
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = image.name,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FeatherIcons.Image,
                contentDescription = image.name,
                tint = colors.labelTertiary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun LocalTranscriptUnknown(block: LocalMessageBlock.Unknown) {
    val colors = DsTheme.colors
    Surface(
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL3),
    ) {
        Text(
            text = stringResource(R.string.local_unknown_message_block, block.kind),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
    }
}

@Composable
private fun LocalTranscriptFile(file: LocalMessageBlock.File) {
    val colors = DsTheme.colors
    Surface(
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL3),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                FeatherIcons.Tool,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                file.name,
                style = DsType.small13.withReadingWeight(),
                color = colors.labelPrimary,
            )
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
        icon = FeatherIcons.ArrowLeft,
        contentDescription = stringResource(R.string.local_previous_variant),
        onClick = { onSelectVariant(messageId, info.index - 1) },
        enabled = enabled && info.hasPrevious,
    )
    Text(
        text = stringResource(R.string.local_variant_position, info.index + 1, info.count),
        style = DsType.caption11.withReadingWeight(),
        color = colors.labelTertiary,
    )
    CompactMessageAction(
        icon = FeatherIcons.ChevronRight,
        contentDescription = stringResource(R.string.local_next_variant),
        onClick = { onSelectVariant(messageId, info.index + 1) },
        enabled = enabled && info.hasNext,
    )
}

@Composable
internal fun CompactMessageAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(DsSpacing.touchTarget),
        shape = RoundedCornerShape(8.dp),
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
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
        )
    }
}

internal const val MAX_LOCAL_IMAGE_SELECTION = 20


internal fun decodeLocalAttachmentThumbnail(
    workspacePath: String,
    relativePath: String,
    targetPx: Int = 160,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val root = File(workspacePath).canonicalFile
    val file = File(root, relativePath).canonicalFile
    require(file.toPath().startsWith(root.toPath()) && file.isFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    val target = targetPx.coerceIn(64, 2_048)
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}.getOrNull()
