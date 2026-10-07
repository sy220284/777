package com.labteto.dshmobile.ui.screens.main

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.FULL_ACCESS_PRESET
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.FileAttachmentRef
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.displayPermissionPreset
import com.labteto.dshmobile.ui.components.ContextMeter
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsComposerMetrics
import com.labteto.dshmobile.ui.components.DsConversationComposer
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * Something picked and waiting to be sent with the next message.
 *
 * Two kinds, because the harness carries them two ways. An [Image] rides the prompt as bytes,
 * so it is held here fully encoded. A [File] is uploaded the moment it is picked — harness
 * 0.1.3 streams it to the host verbatim — and what the prompt carries is the receipt that upload
 * answered with, so the chip tracks the upload rather than the bytes.
 */
internal sealed interface PendingAttachment {
    /** Display name, when the picker had one. */
    val name: String?

    /**
     * A picked image, held with its decoded preview.
     *
     * [bytes], [width] and [height] are the encoded size and intrinsic dimensions the picker already
     * had to learn to admit the image; keeping them means the *next* pick can be measured against
     * the message's running totals without decoding everything already attached a second time.
     */
    data class Image(
        val mediaType: String,
        val base64: String,
        val preview: ImageBitmap?,
        val bytes: Int,
        val width: Int,
        val height: Int,
        override val name: String? = null,
    ) : PendingAttachment {
        /** The wire form `session/prompt` carries. */
        fun encoded(): EncodedImageAttachment = EncodedImageAttachment(mediaType, base64, name)
    }

    /** A picked file and the state of its upload. [id] is what a progress callback keys on. */
    data class File(
        val id: String,
        val uri: Uri,
        override val name: String,
        /** Size as the provider reported it; `-1` when it would not say. */
        val size: Long,
        val state: FileUploadState,
    ) : PendingAttachment {
        /** The receipt to cite, once the upload has one. */
        val receiptId: String? get() = (state as? FileUploadState.Ready)?.receiptId
    }
}

/** Where one file's upload stands. */
internal sealed interface FileUploadState {
    /** Bytes are still going up; [sent] is the running count. */
    data class Uploading(val sent: Long) : FileUploadState

    /** The host holds the file and minted a receipt for it. */
    data class Ready(val receiptId: String, val file: FileAttachmentRef) : FileUploadState

    /** The upload did not complete; the chip offers a retry. */
    data class Failed(val message: String) : FileUploadState
}

/**
 * The message composer, laid out like the harness's own: the `+` and the permission chip on the
 * left, the send affordance on the right.
 *
 * The model selector is deliberately *not* here — it moved to the top bar, which leaves this row
 * for the two controls you change mid-conversation and keeps the composer from wrapping on a
 * narrow phone.
 */
@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onRetryAttachment: (Int) -> Unit,
    permissions: PermissionSelect?,
    pendingPermission: String?,
    onPermissionPick: (String) -> Unit,
    contextBreakdown: ContextBreakdownView?,
    contextPressure: ContextPressureView?,
    running: Boolean,
    enabled: Boolean,
    onOpenAttachments: () -> Unit,
    onOpenTools: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    preparing: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var composerMenuOpen by remember { mutableStateOf(false) }
    var composerFocused by remember { mutableStateOf(false) }
    // A file that is still uploading has no receipt to cite yet, and one that failed never will;
    // the send affordance waits for the chips rather than sending a message that names neither.
    val attachmentsSettled = attachments.none { it is PendingAttachment.File && it.state !is FileUploadState.Ready }
    val canSend = enabled && !preparing && (draft.isNotBlank() || attachments.isNotEmpty()) && attachmentsSettled
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentOnSend by rememberUpdatedState(onSend)
    val composerExpanded =
        composerFocused || attachments.isNotEmpty() || preparing || draft.contains('\n')

    fun submitDraft() {
        val text = currentDraft
        currentOnDraftChange("")
        focusManager.clearFocus()
        keyboardController?.hide()
        currentOnSend(text)
    }

    @Composable
    fun ComposerMenuControl() {
        Box {
            DsComposerAction(
                icon = FeatherIcons.Plus,
                contentDescription = stringResource(R.string.chat_composer_attach_file),
                onClick = { composerMenuOpen = true },
                enabled = enabled && !preparing,
                tint = colors.labelPrimary,
                containerColor = Color.Transparent,
            )
            DsPopupMenu(
                expanded = composerMenuOpen,
                onDismiss = { composerMenuOpen = false },
                focusable = false,
                items = listOf(
                    MenuItem(
                        text = stringResource(R.string.chat_composer_attach_file),
                        icon = FeatherIcons.FilePlus,
                        onClick = onOpenAttachments,
                    ),
                    MenuItem(
                        text = stringResource(R.string.chat_context_tools),
                        icon = FeatherIcons.Tool,
                        onClick = onOpenTools,
                    ),
                ),
            )
        }
    }

    @Composable
    fun SendControl() {
        DsComposerAction(
            icon = FeatherIcons.ArrowUp,
            contentDescription = stringResource(R.string.chat_composer_send),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                submitDraft()
            },
            enabled = canSend,
            tint = if (canSend) colors.onAccent else colors.labelTertiary,
            containerColor = if (canSend) colors.buttonInfoFill else colors.buttonPrimaryDimmed,
            visualSize = DsComposerMetrics.primaryActionVisualSize,
        )
    }

    @Composable
    fun StopControl() {
        DsComposerAction(
            icon = null,
            contentDescription = stringResource(R.string.chat_composer_stop),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStop()
            },
            tint = colors.bgBase,
            containerColor = colors.labelPrimary,
            visualSize = DsComposerMetrics.primaryActionVisualSize,
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
    }

    DsConversationComposer(modifier = modifier) {
        if (preparing) Text(stringResource(R.string.photos_preparing), style = DsType.caption11.withReadingWeight())
        if (attachments.isNotEmpty()) AttachmentStrip(attachments, onRemoveAttachment, onRetryAttachment)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            if (!composerExpanded) ComposerMenuControl()
            DsComposerField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = stringResource(R.string.chat_composer_hint),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                            if (event.type == KeyEventType.KeyUp && canSend) submitDraft()
                            true
                        } else false
                    },
                enabled = enabled,
                maxLines = 8,
                onFocusedChange = { composerFocused = it },
            )
            if (!composerExpanded) {
                SendControl()
                if (running) StopControl()
            }
        }

        if (composerExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                ComposerMenuControl()
                PermissionChip(
                    select = permissions,
                    pending = pendingPermission,
                    enabled = enabled,
                    onPick = onPermissionPick,
                )
                if (contextPressure != null) {
                    Box(
                        modifier = Modifier.width(24.dp).height(DsComposerMetrics.actionTouchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContextMeter(contextBreakdown, contextPressure, barWidth = 24)
                    }
                }
                Spacer(Modifier.weight(1f))
                SendControl()
                if (running) StopControl()
            }
        }
    }

}


/**
 * The permission preset chip.
 *
 * Renders nothing when the projection key is absent: that means the harness composes no permission
 * service at all, and a dead control would be worse than none. Labels come from the wire, because
 * the preset table is deployment-configurable — mapping ids to local strings would mislabel any
 * deployment that renamed one.
 */
@Composable
private fun PermissionChip(
    select: PermissionSelect?,
    pending: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val colors = DsTheme.colors
    if (select == null) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val effective = pending ?: select.currentValue
    val option = select.options.firstOrNull { it.value == effective }
    val label = if (option != null) {
        displayPermissionPreset(option.value, option.name)
    } else {
        stringResource(R.string.permission_custom)
    }

    Box {
        DsComposerAction(
            icon = FeatherIcons.Shield,
            contentDescription = label,
            onClick = { menuOpen = true },
            enabled = enabled && pending == null,
            tint = colors.labelPrimary,
            containerColor = if (effective == FULL_ACCESS_PRESET) colors.hoverSolid else Color.Transparent,
        )
        if (menuOpen) {
            PermissionMenu(
                select = select,
                current = effective,
                onDismiss = { menuOpen = false },
                onPick = { value ->
                    menuOpen = false
                    if (value == FULL_ACCESS_PRESET) confirming = value else onPick(value)
                },
            )
        }
    }

    confirming?.let { target ->
        FullAccessConfirmDialog(
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                onPick(target)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (Int) -> Unit,
    onRetry: (Int) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Box {
                when (attachment) {
                    is PendingAttachment.Image -> Box(
                        Modifier
                            .size(56.dp)
                            .clip(DsShapes.block)
                            .background(colors.bgModulePlatform),
                    ) {
                        attachment.preview?.let {
                            Image(
                                bitmap = it,
                                contentDescription = attachment.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    is PendingAttachment.File -> FileAttachmentChip(attachment, onRetry = { onRetry(index) })
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(DsSpacing.touchTarget)
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(colors.toastBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            FeatherIcons.X,
                            contentDescription = stringResource(
                                if (attachment is PendingAttachment.File) R.string.chat_composer_remove_file
                                else R.string.chat_composer_remove_image,
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One file in the strip: name, then either its size, its upload progress, or the failure.
 *
 * The chip is the same height as an image tile so the strip does not step. A failed upload is
 * retried by tapping the chip itself — the remove affordance stays where it is on every kind.
 */
@Composable
private fun FileAttachmentChip(file: PendingAttachment.File, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    val failed = file.state is FileUploadState.Failed
    Row(
        modifier = Modifier
            .height(56.dp)
            .widthIn(min = 120.dp, max = 200.dp)
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform)
            .border(1.dp, if (failed) colors.error else colors.borderL3, DsShapes.block)
            .clickable(enabled = failed, onClick = onRetry)
            .padding(start = 10.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = file.state) {
            is FileUploadState.Uploading -> CircularProgressIndicator(
                progress = {
                    if (file.size > 0) (state.sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.size(18.dp),
                color = colors.accent,
                trackColor = colors.borderL3,
                strokeWidth = 2.dp,
            )
            else -> Icon(
                FeatherIcons.FileText,
                contentDescription = null,
                tint = if (failed) colors.error else colors.labelSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column {
            Text(
                file.name,
                style = DsType.small13.withReadingWeight(),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (val state = file.state) {
                    is FileUploadState.Uploading -> stringResource(R.string.chat_attachment_uploading)
                    is FileUploadState.Ready -> fileSizeText(state.file.bytes)
                    is FileUploadState.Failed -> stringResource(R.string.chat_attachment_upload_failed)
                },
                style = DsType.caption11.withReadingWeight(),
                color = if (failed) colors.error else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
