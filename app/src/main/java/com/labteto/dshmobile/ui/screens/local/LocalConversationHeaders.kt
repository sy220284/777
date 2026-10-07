package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun ChatSurfaceHeader(
    personaName: String,
    portraitPath: String,
    secondary: String,
    groupEnabled: Boolean,
    groupMembers: List<LocalGroupChatMember>,
    activeSpeakerName: String?,
    running: Boolean,
    behaviorTuningCustomized: Boolean,
    onContextClick: () -> Unit,
    onOpenCharacterTuning: () -> Unit,
    onExitGroupChat: () -> Unit,
    onNewSession: () -> Unit,
    sessionPinned: Boolean,
    onTogglePin: () -> Unit,
    onRenameSession: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = DsMetrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DsSpacing.touchTarget)
                    .clip(DsShapes.row)
                    .clickable(enabled = !running, role = Role.Button, onClick = onContextClick)
                    .semantics(mergeDescendants = true) { }
                    .padding(horizontal = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                if (!groupEnabled) {
                    LocalPersonaHeaderAvatar(name = personaName, portraitPath = portraitPath)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (groupEnabled) stringResource(R.string.local_group_chat_title) else personaName,
                        style = DsType.base16Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (groupEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            groupMembers.take(6).forEach { member ->
                                GroupChatMemberAvatar(
                                    member = member,
                                    active = activeSpeakerName == member.displayName,
                                )
                            }
                            Text(
                                activeSpeakerName?.let { speaker ->
                                    stringResource(R.string.local_group_chat_active_speaker, speaker)
                                } ?: stringResource(R.string.local_group_chat_member_count, groupMembers.size),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else if (secondary.isNotBlank()) {
                        Text(
                            secondary,
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (!groupEnabled) {
                DsIconButton(
                    icon = FeatherIcons.User,
                    contentDescription = stringResource(R.string.local_character_tuning_open),
                    onClick = onOpenCharacterTuning,
                    enabled = !running,
                    tint = if (behaviorTuningCustomized) colors.accent else colors.labelSecondary,
                    iconSize = 18.dp,
                    selected = behaviorTuningCustomized,
                )
            }
        }
        if (groupEnabled) {
            DsButton(
                text = stringResource(R.string.local_group_chat_leave),
                onClick = onExitGroupChat,
                enabled = !running,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
        DsIconButton(
            icon = FeatherIcons.Plus,
            contentDescription = stringResource(R.string.chatlist_new_session),
            onClick = onNewSession,
            tint = colors.labelSecondary,
        )
        ConversationActionsMenu(
            pinned = sessionPinned,
            onTogglePin = onTogglePin,
            onRename = onRenameSession,
            onDelete = onDeleteSession,
        )
    }
}

@Composable
internal fun WorkSurfaceHeader(
    sessionTitle: String,
    modelLabel: String,
    configured: Boolean,
    running: Boolean,
    onModelClick: () -> Unit,
    onOpenRunCenter: () -> Unit,
    onNewSession: () -> Unit,
    sessionPinned: Boolean,
    onTogglePin: () -> Unit,
    onRenameSession: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = DsMetrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DsSpacing.touchTarget)
                .clip(DsShapes.row)
                .clickable(enabled = !running, role = Role.Button, onClick = onModelClick)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Icon(
                FeatherIcons.Sliders,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    sessionTitle,
                    style = DsType.base16Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (configured) modelLabel else stringResource(R.string.local_model_setup),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (running) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.xsmall))
        }
        DsIconButton(
            icon = FeatherIcons.CheckSquare,
            contentDescription = stringResource(R.string.local_execution_console),
            onClick = onOpenRunCenter,
            tint = colors.labelSecondary,
        )
        DsIconButton(
            icon = FeatherIcons.Plus,
            contentDescription = stringResource(R.string.chatlist_new_session),
            onClick = onNewSession,
            tint = colors.labelSecondary,
        )
        ConversationActionsMenu(
            pinned = sessionPinned,
            onTogglePin = onTogglePin,
            onRename = onRenameSession,
            onDelete = onDeleteSession,
        )
    }
}
