package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyActionEntry
import com.shenyan.designsystem.components.SyActionSheet
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.ConversationMenuIcons

/** Real conversation actions use the same bottom-sheet geometry as other action surfaces. */
@Composable
internal fun ConversationActionsMenu(
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenWorkEvidence: (() -> Unit)? = null,
    onOpenDocuments: (() -> Unit)? = null,
    onOpenSample: (() -> Unit)? = null,
    onSearchMessages: (() -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var expanded by remember { mutableStateOf(false) }
    // The original confirmation keeps the exact session deletion callback captured on selection.
    var pendingDeleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    SyIconButton(
        icon = FeatherIcons.MoreVertical,
        contentDescription = stringResource(R.string.local_session_actions),
        onClick = { expanded = true },
        tint = colors.textSecondary,
    )
    if (expanded) {
        val entries = buildList {
            if (onSearchMessages != null) add(SyActionEntry("search",
                stringResource(R.string.sy_v6_search_messages), icon = FeatherIcons.Search))
            if (onOpenWorkEvidence != null) add(SyActionEntry("evidence",
                stringResource(R.string.work_evidence_title), icon = ConversationMenuIcons.WorkEvidence))
            if (onOpenDocuments != null) add(SyActionEntry("documents",
                stringResource(R.string.local_session_documents), icon = ConversationMenuIcons.InteractiveDocument))
            if (onOpenSample != null) add(SyActionEntry("sample",
                stringResource(R.string.local_session_sample_title), icon = FeatherIcons.Sparkles))
            add(SyActionEntry("rename", stringResource(R.string.common_rename),
                icon = ConversationMenuIcons.Rename))
            add(SyActionEntry("pin", stringResource(
                if (pinned) R.string.local_unpin_session else R.string.advanced_pin),
                icon = ConversationMenuIcons.Pin))
            add(SyActionEntry("delete", stringResource(R.string.local_delete_session),
                icon = ConversationMenuIcons.Delete, destructive = true))
        }
        SyActionSheet(
            title = stringResource(R.string.local_session_actions),
            entries = entries,
            onDismiss = { expanded = false },
            confirmDestructive = false,
            onChoose = { id -> when (id) {
                "search" -> onSearchMessages?.invoke()
                "evidence" -> onOpenWorkEvidence?.invoke()
                "documents" -> onOpenDocuments?.invoke()
                "sample" -> onOpenSample?.invoke()
                "rename" -> onRename()
                "pin" -> onTogglePin()
                "delete" -> pendingDeleteAction = onDelete
            } },
        )
    }
    pendingDeleteAction?.let { action ->
        ConfirmDialog(
            title = stringResource(R.string.local_delete_session),
            body = stringResource(R.string.local_delete_session_confirm_message),
            confirmLabel = stringResource(R.string.local_delete_session),
            onDismiss = { pendingDeleteAction = null },
            onConfirm = { pendingDeleteAction = null; action() },
        )
    }
}
