package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlin.math.roundToInt

internal data class LocalDrawerSessionSections(
    val pinned: List<LocalSessionSummary>,
    val recent: List<LocalSessionSummary>,
)

internal fun localDrawerSessionSections(
    sessions: List<LocalSessionSummary>,
    usageMode: LocalUsageMode,
    pinnedSessionIds: Set<String>,
    query: String,
    sessionTitleOverrides: Map<String, String>,
): LocalDrawerSessionSections {
    val normalizedQuery = query.trim()
    val visible = sessions.asSequence()
        .filter { !it.blank && it.usageMode == usageMode }
        .filter { session ->
            normalizedQuery.isEmpty() ||
                (sessionTitleOverrides[session.id] ?: session.title)
                    .contains(normalizedQuery, ignoreCase = true)
        }
        .sortedByDescending(LocalSessionSummary::updatedAt)
        .toList()
    return LocalDrawerSessionSections(
        pinned = visible.filter { it.id in pinnedSessionIds },
        recent = visible.filterNot { it.id in pinnedSessionIds },
    )
}

@Composable
internal fun DrawerContextCard(
    usageMode: LocalUsageMode,
    title: String,
    subtitle: String?,
    portraitPath: String,
    group: Boolean,
    running: Boolean,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING),
        border = BorderStroke(1.dp, colors.borderL1),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            when {
                usageMode == LocalUsageMode.CHAT && !group -> {
                    LocalPersonaHeaderAvatar(name = title, portraitPath = portraitPath)
                }
                usageMode == LocalUsageMode.CHAT -> {
                    Icon(
                        FeatherIcons.Users,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                else -> {
                    Icon(
                        FeatherIcons.Activity,
                        contentDescription = null,
                        tint = if (running) colors.accent else colors.labelSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = DsType.base16Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawerQuickActions(
    usageMode: LocalUsageMode,
    groupMemberCount: Int,
    galleryCount: Int,
    onOpenGroupChat: () -> Unit,
    onOpenPersonaGallery: () -> Unit,
    onOpenDiary: () -> Unit,
    onTasks: () -> Unit,
    onWorkspaceFiles: () -> Unit,
    onOpenRunCenter: () -> Unit,
    onTools: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column {
            if (usageMode == LocalUsageMode.CHAT) {
                DrawerQuickAction(
                    icon = FeatherIcons.Users,
                    title = stringResource(R.string.local_group_chat_title),
                    badge = groupMemberCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenGroupChat,
                )
                DrawerQuickDivider()
                DrawerQuickAction(
                    icon = FeatherIcons.Image,
                    title = stringResource(R.string.persona_gallery_title),
                    badge = galleryCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenPersonaGallery,
                )
                DrawerQuickDivider()
                DrawerQuickAction(
                    icon = FeatherIcons.BookOpen,
                    title = stringResource(R.string.chat_diary_title),
                    onClick = onOpenDiary,
                )
                DrawerQuickDivider()
                DrawerQuickAction(
                    icon = FeatherIcons.Clock,
                    title = stringResource(R.string.tasks_chat_title),
                    onClick = onTasks,
                )
            } else {
                DrawerQuickAction(
                    icon = FeatherIcons.Folder,
                    title = stringResource(R.string.chatlist_workspace_files),
                    onClick = onWorkspaceFiles,
                )
                DrawerQuickDivider()
                DrawerQuickAction(
                    icon = FeatherIcons.Activity,
                    title = stringResource(R.string.local_run_center),
                    onClick = onOpenRunCenter,
                )
                DrawerQuickDivider()
                DrawerQuickAction(
                    icon = FeatherIcons.Clock,
                    title = stringResource(R.string.tasks_title),
                    onClick = onTasks,
                )
                DrawerQuickDivider()
                DrawerQuickAction(
                    icon = FeatherIcons.Tool,
                    title = stringResource(R.string.tools_title),
                    onClick = onTools,
                )
            }
        }
    }
}

@Composable
private fun DrawerQuickDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp),
        color = DsTheme.colors.borderL1,
    )
}

@Composable
internal fun DrawerQuickAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val feedbackColor by animateColorAsState(
        targetValue = when {
            pressed -> colors.hoverAccent
            hovered -> colors.hover
            else -> Color.Transparent
        },
        animationSpec = DsAnimations.interactionColor,
        label = "drawerQuickActionFeedback",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .background(feedbackColor)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            title,
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let { DsPill(text = it) }
        Icon(
            FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun DrawerGlobalAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val feedbackColor by animateColorAsState(
        targetValue = when {
            pressed -> colors.hoverAccent
            hovered -> colors.hover
            else -> Color.Transparent
        },
        animationSpec = DsAnimations.interactionColor,
        label = "drawerGlobalActionFeedback",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .background(feedbackColor)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            title,
            style = DsType.std14.withReadingWeight(),
            color = colors.labelPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun DrawerSectionTitle(
    title: String,
    count: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.small, top = DsSpacing.medium, bottom = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = DsType.std14.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
            modifier = Modifier.weight(1f),
        )
        count?.let { DsPill(text = it.toString()) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun LocalSessionDrawerRow(
    session: LocalSessionSummary,
    displayTitle: String,
    galleryEntry: PersonaGalleryEntry?,
    current: Boolean,
    pinned: Boolean,
    running: Boolean,
    selected: Boolean,
    selectionOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    val reveal = with(density) { 76.dp.toPx() }
    var swipe by remember { mutableStateOf(0f) }

    Box(Modifier.fillMaxWidth()) {
        DsIconButton(
            icon = FeatherIcons.Trash2,
            contentDescription = stringResource(R.string.local_delete_session),
            onClick = {
                swipe = 0f
                onDelete()
            },
            tint = colors.error,
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipe.roundToInt(), 0) }
                .pointerInput(reveal, selectionOpen) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            swipe = (swipe + amount).coerceIn(-reveal, 0f)
                        },
                        onDragEnd = { swipe = if (swipe < -reveal / 2) -reveal else 0f },
                    )
                }
                .heightIn(min = DsSpacing.touchTarget)
                .clip(DsShapes.row)
                .background(
                    animateColorAsState(
                        targetValue = when {
                            selected -> colors.accentTertiary
                            current -> colors.sidebarNavActive
                            else -> Color.Transparent
                        },
                        animationSpec = DsAnimations.interactionColor,
                        label = "sessionRowBackground",
                    ).value,
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current && !selectionOpen) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 24.dp)
                        .clip(DsShapes.pillFull)
                        .background(colors.accent),
                )
                Spacer(Modifier.width(DsSpacing.xsmall))
            }
            if (selectionOpen) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }

            if (session.usageMode == LocalUsageMode.CHAT) {
                LocalPersonaHeaderAvatar(
                    name = galleryEntry?.persona?.name ?: displayTitle,
                    portraitPath = galleryEntry?.portraitPath.orEmpty(),
                )
            } else {
                Icon(
                    FeatherIcons.Activity,
                    contentDescription = null,
                    tint = if (running) colors.accent else colors.labelTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(DsSpacing.small))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    Text(
                        displayTitle,
                        style = if (current) DsType.std14Strong else DsType.std14,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinned) {
                        Icon(
                            FeatherIcons.Pin,
                            contentDescription = null,
                            tint = colors.labelCaption,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                session.summaryPreview?.takeIf(String::isNotBlank)?.let { preview ->
                    Text(
                        preview,
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    Text(
                        relativeTime(session.updatedAt),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelCaption,
                        maxLines = 1,
                    )
                    if (session.chatMode == LocalChatMode.GROUP) {
                        DsPill(text = stringResource(R.string.local_group_chat_title))
                    }
                    if (running) {
                        DsPill(
                            text = stringResource(R.string.local_drawer_running),
                            selected = true,
                        )
                    } else if (current) {
                        DsPill(
                            text = stringResource(R.string.local_current_session),
                            selected = true,
                        )
                    }
                }
            }
        }
    }
}
