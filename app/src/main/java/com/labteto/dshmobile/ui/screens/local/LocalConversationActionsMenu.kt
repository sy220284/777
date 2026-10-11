package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.theme.DsTheme

/** Session actions remain anchored beside the tapped icon, matching the context-menu surface. */
@Composable
internal fun ConversationActionsMenu(
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    // Capture the target action at the original tap, even if the active session changes
    // while the confirmation dialog is open.
    var pendingDeleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Box {
        DsIconButton(
            icon = FeatherIcons.MoreVertical,
            contentDescription = stringResource(R.string.local_session_actions),
            onClick = { expanded = true },
            tint = colors.labelSecondary,
        )
        DsPopupMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            items = listOf(
                MenuItem(
                    text = stringResource(R.string.common_rename),
                    icon = FeatherIcons.Edit3,
                    onClick = onRename,
                ),
                MenuItem(
                    text = stringResource(if (pinned) R.string.local_unpin_session else R.string.advanced_pin),
                    icon = FeatherIcons.Pin,
                    onClick = onTogglePin,
                ),
                MenuItem(
                    text = stringResource(R.string.local_delete_session),
                    icon = FeatherIcons.Trash2,
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
