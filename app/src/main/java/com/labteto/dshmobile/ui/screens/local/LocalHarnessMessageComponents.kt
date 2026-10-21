package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.LocalChatBranchInfo
import com.labteto.dshmobile.local.presentation.extractLocalCharacterThought
import com.labteto.dshmobile.local.presentation.editableChatUserText
import com.labteto.dshmobile.local.presentation.groupMessageVisibleContent
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalMessageMediaSource
import com.labteto.dshmobile.local.session.visibleBlocks
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.visibleAssistantText
import com.labteto.dshmobile.ui.shenyan.SyContextActionMenu
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ThinkingRow
import com.labteto.dshmobile.ui.shenyan.SyUserBubble
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    streaming: Boolean = false,
    skillDisplayNames: Map<String, String> = emptyMap(),
    onLocateSourceMemory: ((LocalHarnessMessage) -> Unit)? = null,
    onCreateMedia: ((LocalHarnessMessage, LocalMediaKind) -> Unit)? = null,
    onEditGeneratedImage: ((LocalHarnessMessage) -> Unit)? = null,
    onConvertToWork: ((LocalHarnessMessage) -> Unit)? = null,
    onCreateDocument: ((LocalHarnessMessage) -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val pulse = rememberHapticPulse()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.chat_copy_success)
    val regenerateFailedMessage = stringResource(R.string.local_regenerate_reply_failed)
    val variantSelectionFailedMessage = stringResource(R.string.local_select_variant_failed)
    val variantScope = rememberCoroutineScope()
    var selectingVariant by remember(message.id) { mutableStateOf(false) }
    var copied by remember(message.id, message.content) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
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
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Box {
                    Box(
                        modifier = Modifier.combinedClickable(
                            onClick = { actionsOpen = false },
                            onLongClick = { pulse(); actionsOpen = true },
                        ),
                    ) {
                        LocalUserMessageContent(
                            message = message,
                            workspacePath = workspacePath,
                            showSkillNames = !chatMode,
                            skillDisplayNames = skillDisplayNames,
                        )
                    }
                    val actionMenu: @Composable (Boolean, () -> Unit, List<SyMenuItem>) -> Unit =
                        if (chatMode) { open, dismiss, items -> FluidMessageActionSheet(open, dismiss, items) }
                        else { open, dismiss, items -> SyContextActionMenu(open, dismiss, items) }
                    actionMenu(
                        actionsOpen,
                        { actionsOpen = false },
                        buildList {
                            if (copyText.isNotBlank()) {
                                add(
                                    SyMenuItem(
                                        text = stringResource(R.string.common_copy),
                                        icon = FeatherIcons.Copy,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(copyText))
                                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                                        },
                                    ),
                                )
                            }
                            // Design 01 → 02: a chosen USER message opens the real Work
                            // capability/handoff sheet. The sheet handles confirmation,
                            // permissions and session linking; a chat menu never runs tools.
                            if (chatMode && copyText.isNotBlank()) onConvertToWork?.let { convert ->
                                add(SyMenuItem(
                                    text = stringResource(R.string.sy_v6_convert_to_work),
                                    icon = SyEntryIcons.Tasks,
                                    onClick = { convert(message.copy(content = copyText)) },
                                ))
                            }
                            if (canEdit) {
                                add(
                                    SyMenuItem(
                                        text = stringResource(R.string.local_edit_user_message),
                                        icon = FeatherIcons.Edit3,
                                        onClick = { onEdit(message) },
                                    ),
                                )
                            }
                            if (chatMode && onLocateSourceMemory != null) {
                                add(
                                    SyMenuItem(
                                        text = stringResource(R.string.chat_locate_message_memory),
                                        icon = FeatherIcons.BookOpen,
                                        onClick = { onLocateSourceMemory(message) },
                                    ),
                                )
                            }
                            if (chatMode && copyText.isNotBlank()) onCreateMedia?.let { create ->
                                addAll(fluidMessageMediaItems(message.copy(content = copyText), create))
                            }
                            if (chatMode && !streaming && editableGeneratedImage(message.visibleBlocks()) != null) {
                                onEditGeneratedImage?.let { edit ->
                                    add(SyMenuItem(
                                        text = stringResource(R.string.media_chat_edit_generated_image),
                                        icon = FeatherIcons.Edit3,
                                        onClick = { edit(message) },
                                    ))
                                }
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

        "system" -> if (!chatMode) Text(
            text = message.content,
            style = DsType.workBody.withReadingWeight(),
            color = if (message.content.startsWith("执行失败：")) colors.dangerInk else colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapMd),
        )

        "reasoning" -> ChatThinkingRow(listOf(message), streaming = streaming)
        "tool", "progress" -> WorkTimelineStepRow(message, workspacePath = workspacePath)

        else -> {
            var actionsOpen by remember(message.id) { mutableStateOf(false) }
            val readingLayer = chatMode
            val visibleContent = if (groupMode) groupMessageVisibleContent(message) else message.content
            val copyableContent = if (chatMode) extractLocalCharacterThought(visibleContent, streaming).second else visibleContent
            // Primary actions live on the toolbar; the overflow contains only secondary actions.
            // Long-press and the overflow button open this SAME set of actions.
            val secondaryActions = buildList {
                if (!chatMode && !streaming) onCreateDocument?.let { create ->
                    add(SyMenuItem(
                        text = "为此回复生成交互文档",
                        icon = FeatherIcons.FileText,
                        onClick = { create(message) },
                    ))
                }
                if (chatMode && !streaming && editableGeneratedImage(message.visibleBlocks()) != null) {
                    onEditGeneratedImage?.let { edit ->
                        add(SyMenuItem(
                            text = stringResource(R.string.media_chat_edit_generated_image),
                            icon = FeatherIcons.Edit3,
                            onClick = { edit(message) },
                        ))
                    }
                }
                if (!streaming && copyableContent.isNotBlank()) onCreateMedia?.let { create ->
                    // Image creation is the one-tap toolbar action; video and speech stay discoverable here.
                    addAll(fluidMessageMediaItems(message.copy(content = copyableContent), create).drop(1))
                }
            }
            val readingModifier = if (readingLayer) {
                Modifier
                    .fillMaxWidth(0.91f)
                    .background(
                        com.shenyan.designsystem.components.syMaterialColor(SyMaterialTier.L3_CARD),
                        DsShapes.characterBubble,
                    )
                    .border(
                        width = 1.dp,
                        color = colors.border,
                        shape = DsShapes.characterBubble,
                    )
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Column(
                    readingModifier.testTag("messageActionSurface:${message.id}").combinedClickable(
                        onClick = {},
                        onLongClick = if (secondaryActions.isNotEmpty()) {
                            { pulse(); actionsOpen = true }
                        } else null,
                    ),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                if (groupMode) {
                    message.speakerName?.takeIf(String::isNotBlank)?.let { speaker ->
                        Text(
                            speaker,
                            style = DsType.small13Strong.withReadingWeight().copy(fontFamily = DsType.contentFont),
                            color = colors.lilac,
                        )
                    }
                }
                LocalAssistantMessageContent(
                    message = message,
                    streaming = streaming,
                    workspacePath = workspacePath,
                    groupMode = groupMode,
                    chatMode = chatMode,
                )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().testTag("messageActionToolbar:${message.id}"),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (chatMode) {
                        // 分支切换作为一个整体换行，窄屏不拆散左右箭头与页码。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MessageVariantControls(
                                messageId = message.id,
                                branchInfo = branchInfo,
                                enabled = canSelectVariant && !selectingVariant,
                                onSelectVariant = selectVariantWithFeedback,
                            )
                        }
                    }
                    if (copyableContent.isNotBlank()) {
                        CompactMessageAction(
                            icon = if (copied) FeatherIcons.Check else FeatherIcons.MessageCopy,
                            iconSize = 22.dp,
                            contentDescription = if (copied) copiedMessage else stringResource(R.string.chat_copy_answer),
                            onClick = {
                                clipboard.setText(AnnotatedString(copyableContent))
                                copied = true
                                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    if (canRegenerate) CompactMessageAction(
                        icon = FeatherIcons.Retry,
                        iconSize = 22.dp,
                        contentDescription = stringResource(R.string.local_regenerate_reply),
                        onClick = {
                            if (!onRegenerate(message.id)) {
                                Toast.makeText(context, regenerateFailedMessage, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    // 创作是独立的明确入口；正文长按继续保留原生选词能力。
                    onCreateMedia?.takeIf { !streaming && copyableContent.isNotBlank() }?.let { create ->
                        SyButton(
                            text = stringResource(R.string.fluid_message_create),
                            icon = FeatherIcons.Sparkles,
                            onClick = { create(message.copy(content = copyableContent), LocalMediaKind.IMAGE) },
                            modifier = Modifier.testTag("messageCreate:${message.id}"),
                            variant = SyButtonVariant.Ghost,
                            size = SyButtonSize.Small,
                        )
                    }
                    // Shared anchored menu for both long press and the visible overflow.
                    if (secondaryActions.isNotEmpty()) {
                        Box {
                            CompactMessageAction(
                                icon = FeatherIcons.MoreVertical,
                                contentDescription = stringResource(R.string.fluid_message_actions),
                                onClick = { actionsOpen = true },
                            )
                            SyPopupMenu(
                                expanded = actionsOpen,
                                onDismiss = { actionsOpen = false },
                                items = secondaryActions,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Model-produced image only; user-uploaded and tool images are not generated assets. */
internal fun editableGeneratedImage(blocks: List<LocalMessageBlock>): LocalMessageBlock.Image? =
    blocks.filterIsInstance<LocalMessageBlock.Image>()
        .firstOrNull { it.source == LocalMessageMediaSource.MODEL }

@Composable
private fun LocalUserMessageContent(
    message: LocalHarnessMessage,
    workspacePath: String,
    showSkillNames: Boolean,
    skillDisplayNames: Map<String, String>,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        message.visibleBlocks().forEach { block ->
            when (block) {
                is LocalMessageBlock.Text -> if (block.text.isNotBlank()) {
                    SyUserBubble(if (showSkillNames) localSkillCallDisplayText(block.text, skillDisplayNames) else block.text)
                }
                is LocalMessageBlock.Image -> LocalTranscriptImage(
                    image = block,
                    workspacePath = workspacePath,
                )
                is LocalMessageBlock.File -> LocalTranscriptFile(block, workspacePath)
                is LocalMessageBlock.Unknown -> LocalTranscriptUnknown(block)
            }
        }
    }
}

/**
 * Keep Markdown and model-visible text contiguous until a media boundary. Besides avoiding
 * broken Markdown across adjacent text chunks, this filters legacy thinking markers even
 * when the opening tag spans multiple persisted Text blocks. Media order is unchanged.
 */
internal fun localVisibleAssistantBlocks(message: LocalHarnessMessage): List<LocalMessageBlock> = buildList {
    val text = StringBuilder()
    fun flushText() {
        if (text.isEmpty()) return
        val visible = visibleAssistantText(text.toString())
        if (visible.isNotBlank()) add(LocalMessageBlock.Text(visible))
        text.clear()
    }
    message.visibleBlocks().forEach { block ->
        when (block) {
            is LocalMessageBlock.Text -> text.append(block.text)
            else -> {
                flushText()
                add(block)
            }
        }
    }
    flushText()
}

@Composable
private fun LocalAssistantMessageContent(
    message: LocalHarnessMessage,
    streaming: Boolean,
    workspacePath: String,
    groupMode: Boolean,
    chatMode: Boolean,
) {
    var firstText = true
    localVisibleAssistantBlocks(message).forEach { block ->
        when (block) {
            is LocalMessageBlock.Text -> {
                val text = if (groupMode && firstText) {
                    groupMessageVisibleContent(message)
                } else {
                    block.text
                }
                firstText = false
                val (innerThought, body) = if (chatMode) extractLocalCharacterThought(text, streaming) else null to text
                if (innerThought != null) {
                    Text(
                        text = stringResource(R.string.local_character_inner_thought) + " · " + innerThought,
                        style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                    )
                }
                if (body.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(
                            body,
                            bodyStyle = if (chatMode) DsType.chatBody else DsType.workBody,
                            preserveSoftLineBreaks = chatMode,
                        )
                    }
                }
            }
            is LocalMessageBlock.Image -> LocalTranscriptImage(
                image = block,
                workspacePath = workspacePath,
            )
            is LocalMessageBlock.File -> LocalTranscriptFile(block, workspacePath)
            is LocalMessageBlock.Unknown -> LocalTranscriptUnknown(block)
        }
    }
}

@Composable
internal fun LocalTranscriptImage(
    image: LocalMessageBlock.Image,
    workspacePath: String,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var showingImage by remember(workspacePath, image.relativePath) { mutableStateOf(false) }
    val file = remember(workspacePath, image.relativePath) {
        resolveLocalTranscriptFile(workspacePath, image.relativePath)
    }
    var bitmap by remember(workspacePath, image.relativePath) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var loading by remember(workspacePath, image.relativePath) { mutableStateOf(true) }
    LaunchedEffect(workspacePath, image.relativePath) {
        loading = true
        bitmap = if (file == null) null else withContext(Dispatchers.IO) {
            decodeLocalAttachmentThumbnail(
                workspacePath = workspacePath,
                relativePath = image.relativePath,
                targetPx = 720,
            )
        }
        loading = false
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
        .border(1.dp, colors.border, DsShapes.block)
        .clickable(enabled = file != null) { showingImage = true }
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Icon(
                    FeatherIcons.Image,
                    contentDescription = image.name,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(if (loading) R.string.media_chat_preview_pending
                        else R.string.media_chat_preview_unavailable),
                    color = colors.textSecondary,
                    style = DsType.caption11.withReadingWeight(),
                )
            }
        }
    }
    if (showingImage && file != null) {
        MediaImageDetailDialog(
            file = file,
            remoteUrl = null,
            onDismiss = { showingImage = false },
        )
    }
}

@Composable
private fun LocalTranscriptUnknown(block: LocalMessageBlock.Unknown) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    SyMaterialSurface(
        shape = DsShapes.block,
        tier = SyMaterialTier.L3_CARD,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Text(
            text = stringResource(R.string.local_unknown_message_block, block.kind),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun LocalTranscriptFile(file: LocalMessageBlock.File, workspacePath: String) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val context = LocalContext.current
    val localFile = remember(workspacePath, file.relativePath) {
        resolveLocalTranscriptFile(workspacePath, file.relativePath)
    }
    SyMaterialSurface(
        modifier = Modifier.clickable(enabled = localFile != null) {
            localFile?.let { attached ->
                viewMediaFile(context, attached, file.mediaType) { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }
            }
        },
        shape = DsShapes.block,
        tier = SyMaterialTier.L3_CARD,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Icon(
                FeatherIcons.Tool,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (localFile == null) {
                    Text(
                        stringResource(R.string.media_chat_work_file_missing),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
            }
            if (localFile != null) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = stringResource(R.string.media_chat_open_result),
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    CompactMessageAction(
        icon = FeatherIcons.ArrowLeft,
        contentDescription = stringResource(R.string.local_previous_variant),
        onClick = { onSelectVariant(messageId, info.index - 1) },
        enabled = enabled && info.hasPrevious,
    )
    Text(
        text = stringResource(R.string.local_variant_position, info.index + 1, info.count),
        style = DsType.caption11.withReadingWeight(),
        color = colors.textTertiary,
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
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(SyMetric.touch),
        shape = RoundedCornerShape(8.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) colors.textTertiary else colors.textTertiary.copy(alpha = 0.38f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun ChatThinkingRow(
    messages: List<LocalHarnessMessage>,
    streaming: Boolean = false,
) {
    if (messages.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        StateDot(if (streaming) StateDotState.Running else StateDotState.Done, size = 8.dp)
        Text(
            stringResource(if (streaming) R.string.local_process_thinking else R.string.local_work_thought),
            style = DsType.small13.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        )
    }
}

internal const val MAX_LOCAL_IMAGE_SELECTION = 20


/** Never open an attachment that escapes the active workspace (including symlinks). */
internal fun resolveLocalTranscriptFile(workspacePath: String, relativePath: String): File? =
    runCatching {
        if (workspacePath.isBlank() || relativePath.isBlank()) return@runCatching null
        val root = File(workspacePath).canonicalFile
        val file = File(root, relativePath).canonicalFile
        file.takeIf { it.isFile && it.toPath().startsWith(root.toPath()) }
    }.getOrNull()

internal fun decodeLocalAttachmentThumbnail(
    workspacePath: String,
    relativePath: String,
    targetPx: Int = 160,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val file = resolveLocalTranscriptFile(workspacePath, relativePath) ?: return@runCatching null
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
