package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric


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
import androidx.compose.foundation.layout.widthIn
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
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
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
    tuningEnabled: Boolean = true,
    modelLabel: String = "",
    onModelClick: () -> Unit = {},
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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
                    .widthIn(max = 188.dp)
                    .heightIn(min = SyMetric.touch)
                    .clip(DsShapes.row)
                    .clickable(enabled = !running, role = Role.Button, onClick = onContextClick)
                    .semantics(mergeDescendants = true) { }
                    .padding(horizontal = SyMetric.gapMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (!groupEnabled) {
                    LocalPersonaHeaderAvatar(name = personaName, portraitPath = portraitPath)
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (groupEnabled) stringResource(R.string.local_group_chat_title) else personaName,
                        style = DsType.base16Strong.withReadingWeight(),
                        color = colors.text,
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
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else if (secondary.isNotBlank()) {
                        Text(
                            secondary,
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (!groupEnabled && tuningEnabled) {
                SyIconButton(
                    icon = SyEntryIcons.Persona,
                    contentDescription = stringResource(R.string.local_character_tuning_open),
                    onClick = onOpenCharacterTuning,
                    enabled = !running,
                    tint = if (behaviorTuningCustomized) colors.primary else colors.textSecondary,
                    iconSize = 18.dp,
                    selected = behaviorTuningCustomized,
                )
            }
        }
        SyIconButton(
            icon = SyEntryIcons.Models,
            contentDescription = stringResource(R.string.models_title) + "：" + modelLabel,
            onClick = onModelClick,
            tint = colors.textSecondary,
        )
        if (groupEnabled) {
            SyButton(
                text = stringResource(R.string.local_group_chat_leave),
                onClick = onExitGroupChat,
                enabled = !running,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
        }
        SyIconButton(
            icon = SyEntryIcons.NewSession,
            contentDescription = stringResource(R.string.chatlist_new_session),
            onClick = onNewSession,
            tint = colors.textSecondary,
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
    modelLabel: String,
    configured: Boolean,
    running: Boolean,
    onModelClick: () -> Unit,
    onNewSession: () -> Unit,
    sessionPinned: Boolean,
    onTogglePin: () -> Unit,
    onRenameSession: () -> Unit,
    onDeleteSession: () -> Unit,
    onOpenWorkEvidence: (() -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = DsMetrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 168.dp)
                .heightIn(min = SyMetric.touch)
                .clip(DsShapes.row)
                .clickable(role = Role.Button, onClick = onModelClick)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = SyMetric.gapMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        ) {
            Text(
                if (configured) modelLabel else stringResource(R.string.local_model_setup),
                style = DsType.base16Strong.withReadingWeight(),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (running) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(SyMetric.gapSm))
        }
        SyIconButton(
            icon = SyEntryIcons.NewSession,
            contentDescription = stringResource(R.string.chatlist_new_session),
            onClick = onNewSession,
            tint = colors.textSecondary,
        )
        ConversationActionsMenu(
            pinned = sessionPinned,
            onTogglePin = onTogglePin,
            onRename = onRenameSession,
            onDelete = onDeleteSession,
            onOpenWorkEvidence = onOpenWorkEvidence,
        )
    }
}
