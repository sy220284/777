package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.ui.components.FeatherIcons

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsSheetChoiceRow
import com.labteto.dshmobile.ui.theme.DsTheme

@Composable
internal fun ConversationActionsMenu(
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        DsIconButton(
            icon = FeatherIcons.MoreVertical,
            contentDescription = stringResource(R.string.local_session_actions),
            onClick = { expanded = true },
            tint = colors.labelSecondary,
        )
        if (expanded) {
            DsBottomSheet(
                title = stringResource(R.string.local_session_actions),
                onDismiss = { expanded = false },
            ) {
                DsSheetChoiceRow(
                    title = stringResource(
                        if (pinned) R.string.local_unpin_session else R.string.advanced_pin,
                    ),
                    icon = FeatherIcons.Pin,
                    onClick = {
                        expanded = false
                        onTogglePin()
                    },
                )
                DsSheetChoiceRow(
                    title = stringResource(R.string.common_rename),
                    icon = FeatherIcons.Edit3,
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
                DsSheetChoiceRow(
                    title = stringResource(R.string.local_delete_session),
                    icon = FeatherIcons.Trash2,
                    danger = true,
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
