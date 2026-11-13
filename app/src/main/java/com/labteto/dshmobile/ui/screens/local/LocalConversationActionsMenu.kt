package com.labteto.dshmobile.ui.screens.local


import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.ConversationMenuIcons
import com.labteto.dshmobile.ui.shenyan.SyMenuItem

/** Session actions remain anchored beside the tapped icon, matching the context-menu surface. */
@Composable
internal fun ConversationActionsMenu(
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenWorkEvidence: (() -> Unit)? = null,
    onOpenDocuments: (() -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var expanded by remember { mutableStateOf(false) }
    // Capture the target action at the original tap, even if the active session changes
    // while the confirmation dialog is open.
    var pendingDeleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Box {
        SyIconButton(
            icon = FeatherIcons.MoreVertical,
            contentDescription = stringResource(R.string.local_session_actions),
            onClick = { expanded = true },
            tint = colors.textSecondary,
        )
        SyPopupMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            // Match the attachment + menu: paint the aligned chat scene, not an opaque L4 plate.
            seamlessBackground = true,
            items = onOpenWorkEvidence?.let { open -> listOf(SyMenuItem(
                text = stringResource(R.string.work_evidence_title), icon = ConversationMenuIcons.WorkEvidence, onClick = open,
            )) }.orEmpty() + onOpenDocuments?.let { open -> listOf(SyMenuItem(
                text = stringResource(R.string.local_session_documents),
                icon = ConversationMenuIcons.InteractiveDocument,
                onClick = open,
            )) }.orEmpty() + listOf(
                SyMenuItem(
                    text = stringResource(R.string.common_rename),
                    icon = ConversationMenuIcons.Rename,
                    onClick = onRename,
                ),
                SyMenuItem(
                    text = stringResource(if (pinned) R.string.local_unpin_session else R.string.advanced_pin),
                    icon = ConversationMenuIcons.Pin,
                    onClick = onTogglePin,
                ),
                SyMenuItem(
                    text = stringResource(R.string.local_delete_session),
                    icon = ConversationMenuIcons.Delete,
                    danger = true,
                    onClick = {
                        expanded = false
                        pendingDeleteAction = onDelete
                    },
                ),
            ),
        )
        pendingDeleteAction?.let { deleteAction ->
            ConfirmDialog(
                title = stringResource(R.string.local_delete_session),
                body = stringResource(R.string.local_delete_session_confirm_message),
                confirmLabel = stringResource(R.string.local_delete_session),
                onDismiss = { pendingDeleteAction = null },
                onConfirm = {
                    pendingDeleteAction = null
                    deleteAction()
                },
            )
        }
    }
}
