package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalGroupChatMember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatSurfaceHeader(
    personaName: String,
    portraitPath: String,
    secondary: String,
    groupEnabled: Boolean,
    groupMembers: List<LocalGroupChatMember>,
    activeSpeakerName: String?,
    running: Boolean,
    onOpenMenu: () -> Unit,
    onContextClick: () -> Unit,
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
                    style = DsType.base16Strong,
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
                            style = DsType.caption11,
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (secondary.isNotBlank()) {
                    Text(
                        secondary,
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
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
            icon = FeatherIcons.Menu,
            contentDescription = stringResource(R.string.local_open_menu),
            onClick = onOpenMenu,
            tint = colors.labelSecondary,
        )
        DsIconButton(
            icon = Icons.Filled.Add,
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
    onOpenMenu: () -> Unit,
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
                Icons.Outlined.Tune,
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
                    style = DsType.base16Strong,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (configured) modelLabel else stringResource(R.string.local_model_setup),
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
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
            icon = FeatherIcons.Menu,
            contentDescription = stringResource(R.string.local_open_menu),
            onClick = onOpenMenu,
            tint = colors.labelSecondary,
        )
        DsIconButton(
            icon = Icons.Filled.Add,
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
private fun GroupChatMemberAvatar(
    member: LocalGroupChatMember,
    active: Boolean,
) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = member.portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeLocalPersonaHeaderPortrait(member.portraitPath)
        }
    }

    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent,
        border = if (active) BorderStroke(1.dp, colors.accent) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = member.displayName,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    member.displayName.trim().take(1).ifBlank { "·" },
                    style = DsType.caption11,
                    color = if (active) colors.accent else colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun LocalPersonaHeaderAvatar(
    name: String,
    portraitPath: String,
) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeLocalPersonaHeaderPortrait(portraitPath)
        }
    }
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = colors.accentTertiary,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (portrait != null) {
                Image(
                    bitmap = portrait!!,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    name.trim().take(1).ifBlank { "·" },
                    style = DsType.small13Strong,
                    color = colors.accent,
                )
            }
        }
    }
}

private fun decodeLocalPersonaHeaderPortrait(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}
