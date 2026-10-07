package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.presentation.isUnboundChatPersona
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsComposerMetrics
import com.labteto.dshmobile.ui.components.DsConversationComposer
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalConversationComposer(
    state: LocalConversationSurfaceState,
    activeModelProfile: LocalModelProfile?,
    input: String,
    attachments: List<LocalImportedAttachment>,
    onInputChange: (String) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearAttachments: () -> Unit,
    onOpenAttachmentPicker: () -> Unit,
    onShowReplySuggestions: () -> Unit,
    onGenerateReplySuggestions: suspend () -> Boolean,
    onConfigure: () -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    onStop: () -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onAutoApprove: () -> Unit,
    onDisableAutoApprove: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keep real TextField focus across session projection changes. Otherwise the IME can remain
    // visible while the composer is incorrectly reset to its idle one-row state.
    var focused by remember { mutableStateOf(false) }
    var replySuggestionsLoading by remember(state.sessionId) { mutableStateOf(false) }

    val expanded = focused || input.contains('\n') || attachments.isNotEmpty()
    val groupChatReady = !state.groupChat.enabled || state.groupChat.members.size >= 2
    val canSend = !state.loading &&
        groupChatReady &&
        (input.isNotBlank() || attachments.isNotEmpty())
    val attachmentLabel = stringResource(R.string.chat_composer_add_attachment)
    val replySuggestionsLabel = stringResource(R.string.local_reply_suggestions_open)
    val replySuggestionsAvailable =
        state.usageMode == LocalUsageMode.CHAT &&
            !state.groupChat.enabled &&
            state.messages.any { it.role == "assistant" && it.content.isNotBlank() }

    fun openReplySuggestions() {
        if (!replySuggestionsAvailable || replySuggestionsLoading || state.running) return
        if (state.replySuggestions.any { it.text.isNotBlank() }) {
            onShowReplySuggestions()
            return
        }
        replySuggestionsLoading = true
        scope.launch {
            val generated = try {
                onGenerateReplySuggestions()
            } finally {
                replySuggestionsLoading = false
            }
            if (generated) onShowReplySuggestions()
        }
    }

    fun submit() {
        if (!state.configured) {
            onConfigure()
            return
        }
        val result = onSend(input, attachments.toList())
        if (!result.accepted) return
        onInputChange("")
        onClearAttachments()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    @Composable
    fun AttachmentControl() {
        if (state.running) return
        DsComposerAction(
            icon = FeatherIcons.Plus,
            contentDescription = attachmentLabel,
            onClick = onOpenAttachmentPicker,
            tint = colors.labelPrimary,
            containerColor = Color.Transparent,
        )
    }

    @Composable
    fun ReplySuggestionsControl() {
        if (!replySuggestionsAvailable) return
        DsComposerAction(
            icon = FeatherIcons.Sparkles,
            contentDescription = if (replySuggestionsLoading) {
                stringResource(R.string.common_loading)
            } else {
                replySuggestionsLabel
            },
            onClick = ::openReplySuggestions,
            enabled = !state.running && !replySuggestionsLoading,
            tint = if (replySuggestionsLoading) colors.labelTertiary else colors.labelSecondary,
            containerColor = Color.Transparent,
        )
    }

    @Composable
    fun SendControl(queue: Boolean = false) {
        DsComposerAction(
            icon = FeatherIcons.ArrowUp,
            contentDescription = if (queue && state.queuedInputCount > 0) {
                stringResource(R.string.local_queue_message_count, state.queuedInputCount)
            } else if (queue) {
                stringResource(R.string.local_queue_message)
            } else {
                stringResource(R.string.chat_composer_send)
            },
            onClick = ::submit,
            enabled = canSend,
            tint = if (canSend) colors.onAccent else colors.labelTertiary,
            containerColor = if (canSend) colors.buttonInfoFill else colors.buttonPrimaryDimmed,
            visualSize = DsComposerMetrics.primaryActionVisualSize,
        )
    }

    @Composable
    fun StopControl() {
        DsComposerAction(
            icon = FeatherIcons.Square,
            contentDescription = stringResource(R.string.chat_composer_stop),
            onClick = onStop,
                        tint = colors.bgBase,
            containerColor = colors.labelPrimary,
            visualSize = DsComposerMetrics.primaryActionVisualSize,
        )
    }

    DsConversationComposer(
        surfaceColor = if (backgroundState.hasImage) Color.Transparent else colors.composerCard,
        shadowElevation = if (backgroundState.hasImage) 0.dp else 1.dp,
        // This composer owns a targeted row reveal. Avoid a second parent size animation while
        // the IME is already animating the whole surface.
        animateSize = false,
    ) {
        ChatGptPlanUsageBar(activeModelProfile)
        val imageAttachments = attachments.mapIndexedNotNull { index, attachment ->
            (index to attachment).takeIf { attachment.mediaType.startsWith("image/") }
        }
        if (imageAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                items(
                    items = imageAttachments,
                    key = { (index, attachment) -> "$index:${attachment.relativePath}" },
                ) { (index, attachment) ->
                    ImportedImageAttachmentTile(
                        attachment = attachment,
                        workspacePath = state.workspacePath,
                        onRemove = { onRemoveAttachment(index) },
                    )
                }
            }
        }
        attachments.forEachIndexed { index, attachment ->
            if (!attachment.mediaType.startsWith("image/")) {
                ImportedAttachmentRow(
                    attachment = attachment,
                    workspacePath = state.workspacePath,
                    onRemove = { onRemoveAttachment(index) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            AnimatedVisibility(
                visible = !expanded,
                enter = expandHorizontally(DsAnimations.composerReveal) + fadeIn(DsAnimations.composerFade),
                exit = shrinkHorizontally(DsAnimations.composerReveal) + fadeOut(DsAnimations.composerFade),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    AttachmentControl()
                    ReplySuggestionsControl()
                }
            }
            DsComposerField(
                value = input,
                onValueChange = onInputChange,
                placeholder = when {
                    state.usageMode == LocalUsageMode.WORK ->
                        stringResource(R.string.local_work_composer_hint)
                    state.groupChat.enabled ->
                        stringResource(R.string.local_group_chat_composer_hint)
                    state.chatPersona.isUnboundChatPersona() ->
                        stringResource(R.string.local_chat_composer_no_persona_hint)
                    else ->
                        stringResource(R.string.local_chat_composer_persona_hint, state.chatPersona.name)
                },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                onFocusedChange = {
                    focused = it
                    onFocusChanged(it)
                },
            )
            AnimatedVisibility(
                visible = !expanded,
                enter = expandHorizontally(DsAnimations.composerReveal) + fadeIn(DsAnimations.composerFade),
                exit = shrinkHorizontally(DsAnimations.composerReveal) + fadeOut(DsAnimations.composerFade),
            ) {
                if (state.running) StopControl() else SendControl()
            }
        }

        LocalConversationComposerExpandedRow(
            visible = expanded,
            state = state,
            menuControl = { AttachmentControl() },
            replySuggestionsControl = { ReplySuggestionsControl() },
            stopControl = { StopControl() },
            sendControl = { queue -> SendControl(queue) },
            onPlanModeChange = onPlanModeChange,
            onAutoApprove = onAutoApprove,
            onDisableAutoApprove = onDisableAutoApprove,
        )
    }
}

@Composable
private fun ImportedImageAttachmentTile(
    attachment: LocalImportedAttachment,
    workspacePath: String,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    var thumbnail by remember(attachment.relativePath, workspacePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.relativePath, workspacePath) {
        thumbnail = withContext(Dispatchers.IO) {
            decodeLocalAttachmentThumbnail(
                workspacePath = workspacePath,
                relativePath = attachment.relativePath,
                targetPx = 256,
            )
        }
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(DsShapes.block)
            .background(colors.hoverSolid)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        thumbnail?.let { image ->
            Image(
                bitmap = image,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxWidth().size(88.dp).clip(DsShapes.block),
            )
        }
        DsIconButton(
            icon = FeatherIcons.X,
            contentDescription = stringResource(R.string.common_remove),
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd),
            iconSize = 16.dp,
            tint = colors.labelPrimary,
            containerColor = colors.bgBase.copy(alpha = 0.82f),
        )
    }
}

@Composable
private fun ImportedAttachmentRow(
    attachment: LocalImportedAttachment,
    workspacePath: String,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    var thumbnail by remember(attachment.relativePath, workspacePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.relativePath, workspacePath) {
        thumbnail = if (attachment.mediaType.startsWith("image/")) {
            withContext(Dispatchers.IO) {
                decodeLocalAttachmentThumbnail(workspacePath, attachment.relativePath)
            }
        } else {
            null
        }
    }
    DsCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            thumbnail?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(DsShapes.block),
                )
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    attachment.name,
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                val dimensions = if (attachment.width != null && attachment.height != null) {
                    " · ${attachment.width}×${attachment.height}"
                } else {
                    ""
                }
                Text(
                    "${attachment.mediaType}$dimensions · ${attachment.bytes} B",
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            DsButton(
                stringResource(R.string.common_remove),
                onRemove,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}
