package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric


import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMotion
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.labteto.dshmobile.ui.theme.SyHostMetrics
import com.labteto.dshmobile.ui.theme.SyShapes
import com.labteto.dshmobile.ui.theme.SyType
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
    onContextClick: () -> Unit,
    onExitGroupChat: () -> Unit,
    onNewSession: () -> Unit,
    sessionPinned: Boolean,
    onTogglePin: () -> Unit,
    onRenameSession: () -> Unit,
    onDeleteSession: () -> Unit,
    onOpenSample: (() -> Unit)? = null,
    onSearchMessages: (() -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = SyHostMetrics.topBarHeight),
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
                    .clip(SyShapes.row)
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
                        personaName,
                        style = SyType.base16Strong.withReadingWeight(),
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
                                style = SyType.caption11.withReadingWeight(),
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else if (secondary.isNotBlank()) {
                        Text(
                            secondary,
                            style = SyType.caption11.withReadingWeight(),
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
        }
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
            bare = true,
        )
        ConversationActionsMenu(
            pinned = sessionPinned,
            onTogglePin = onTogglePin,
            onRename = onRenameSession,
            onDelete = onDeleteSession,
            onOpenSample = onOpenSample,
            onSearchMessages = onSearchMessages,
        )
    }
}

@Composable
internal fun WorkSurfaceHeader(
    sessionTitle: String,
    statusText: String,
    onNewSession: () -> Unit,
    sessionPinned: Boolean,
    onTogglePin: () -> Unit,
    onRenameSession: () -> Unit,
    onDeleteSession: () -> Unit,
    onOpenWorkEvidence: (() -> Unit)? = null,
    onOpenDocuments: (() -> Unit)? = null,
    onOpenSample: (() -> Unit)? = null,
    onSearchMessages: (() -> Unit)? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = SyHostMetrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).heightIn(min = SyMetric.touch)
                .testTag("work-status-summary")
                .then(if (onOpenWorkEvidence != null) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.work_open_execution_record),
                    onClick = onOpenWorkEvidence,
                ) else Modifier)
                .padding(horizontal = SyMetric.gapMd),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                sessionTitle.ifBlank { "工作会话" },
                style = SyType.base16Strong.withReadingWeight(),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Crossfade(
                    targetState = statusText,
                    modifier = Modifier.weight(1f, fill = false),
                    animationSpec = tween(
                        SyMotion.duration(SyMotion.composerFadeMs, LocalSyReduceMotion.current,
                            LocalSyMotionTempo.current),
                        easing = SyMotion.ease,
                    ),
                    label = "workHeaderStatus",
                ) { status ->
                    Text(
                        status,
                        style = SyType.small13.withReadingWeight(),
                        color = if (status.startsWith("执行异常") ||
                            status.startsWith("执行状态读取异常") ||
                            status.startsWith("任务步骤失败")) colors.dangerInk else colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onOpenWorkEvidence != null) {
                    Icon(FeatherIcons.ChevronRight, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = colors.textSecondary)
                }
            }
        }
        SyIconButton(
            icon = SyEntryIcons.NewSession,
            contentDescription = stringResource(R.string.chatlist_new_session),
            onClick = onNewSession,
            tint = colors.textSecondary,
            bare = true,
        )
        ConversationActionsMenu(
            pinned = sessionPinned,
            onTogglePin = onTogglePin,
            onRename = onRenameSession,
            onDelete = onDeleteSession,
            onOpenWorkEvidence = onOpenWorkEvidence,
            onOpenDocuments = onOpenDocuments,
            onOpenSample = onOpenSample,
            onSearchMessages = onSearchMessages,
        )
    }
}
