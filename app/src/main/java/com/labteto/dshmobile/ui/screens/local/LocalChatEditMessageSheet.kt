package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.presentation.chatMessageHasAttachmentContext
import com.labteto.dshmobile.local.presentation.editableChatUserText
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch

@Composable
internal fun LocalChatEditMessageSheet(
    message: LocalHarnessMessage,
    actionsEnabled: Boolean,
    onEditAndResend: suspend (String, String) -> LocalChatUserEditResult,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(message.id) { mutableStateOf(editableChatUserText(message)) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    var submitting by remember(message.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val busyMessage = stringResource(R.string.local_edit_user_message_busy)
    val missingMessage = stringResource(R.string.local_edit_user_message_missing)
    val unavailableMessage = stringResource(R.string.local_edit_user_message_unavailable)
    val emptyMessage = stringResource(R.string.local_edit_user_message_empty)
    val unchangedMessage = stringResource(R.string.local_edit_user_message_unchanged)

    DsBottomSheet(
        title = stringResource(R.string.local_edit_user_message),
        onDismiss = { if (!submitting) onDismiss() },
    ) {
        Text(
            stringResource(R.string.local_edit_user_message_hint),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
        if (chatMessageHasAttachmentContext(message)) {
            val retainedNames = message.blocks.mapNotNull { block ->
                when (block) {
                    is com.labteto.dshmobile.local.session.LocalMessageBlock.Image -> block.name
                    is com.labteto.dshmobile.local.session.LocalMessageBlock.File -> block.name
                    else -> null
                }
            }.joinToString("、").ifBlank {
                stringResource(R.string.local_edit_user_message_attachment_legacy)
            }
            Text(
                stringResource(R.string.local_edit_user_message_keeps_attachments, retainedNames),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 8,
            enabled = !submitting,
        )
        error?.let { messageText ->
            Text(messageText, style = DsType.small13.withReadingWeight(), color = colors.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                enabled = !submitting,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
            DsButton(
                text = stringResource(R.string.local_edit_user_message_resend),
                onClick = {
                    if (submitting) return@DsButton
                    submitting = true
                    error = null
                    scope.launch {
                        try {
                            when (onEditAndResend(message.id, text)) {
                                LocalChatUserEditResult.SENT -> onDismiss()
                                LocalChatUserEditResult.BUSY -> error = busyMessage
                                LocalChatUserEditResult.MESSAGE_MISSING -> error = missingMessage
                                LocalChatUserEditResult.UNAVAILABLE -> error = unavailableMessage
                                LocalChatUserEditResult.EMPTY -> error = emptyMessage
                                LocalChatUserEditResult.UNCHANGED -> error = unchangedMessage
                            }
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting &&
                    actionsEnabled &&
                    (text.trim().isNotEmpty() || chatMessageHasAttachmentContext(message)) &&
                    text.trim() != editableChatUserText(message).trim(),
                size = DsButtonSize.Small,
            )
        }
    }
}
