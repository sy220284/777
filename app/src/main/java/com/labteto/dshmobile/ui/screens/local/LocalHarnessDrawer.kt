package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsExpandableColumn
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.DsIconFamily
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

@Composable
internal fun LocalModeDrawer(
    currentSessionId: String,
    sessions: List<LocalSessionSummary>,
    gallery: List<PersonaGalleryEntry>,
    usageMode: LocalUsageMode,
    modeSwitchEnabled: Boolean,
    pinnedSessionIds: Set<String>,
    sessionTitleOverrides: Map<String, String>,
    onUsageModeChange: (LocalUsageMode) -> Unit,
    onNewSession: () -> Unit,
    onRemote: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    onWorkspaceFiles: () -> Unit,
    onOpenRunCenter: () -> Unit,
    galleryCount: Int,
    groupMemberCount: Int,
    onOpenGroupChat: () -> Unit,
    onOpenPersonaGallery: () -> Unit,
    onTasks: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = DsTheme.colors
    var historyQuery by rememberSaveable(usageMode) { mutableStateOf("") }
    var searchOpen by rememberSaveable(usageMode) { mutableStateOf(false) }
    var selectionOpen by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    val selectedIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(usageMode) {
        selectionOpen = false
        selectedIds.clear()
    }

    val galleryById = remember(gallery) { gallery.associateBy { it.id } }
    val visibleSessions = remember(sessions, usageMode, pinnedSessionIds) {
        sessions.filter { !it.blank && it.usageMode == usageMode }
            .sortedWith(
                compareByDescending<LocalSessionSummary> { it.id in pinnedSessionIds }
                    .thenByDescending(LocalSessionSummary::updatedAt),
            )
    }
    val filteredSessions = remember(visibleSessions, historyQuery, sessionTitleOverrides) {
        val query = historyQuery.trim()
        if (query.isEmpty()) visibleSessions else visibleSessions.filter { session ->
            (sessionTitleOverrides[session.id] ?: session.title).contains(query, ignoreCase = true)
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.DRAWER, base = colors.sidebar),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DsSpacing.medium, end = DsSpacing.medium, top = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = DsType.large20.withReadingWeight(),
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DsIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chatlist_new_session),
                        onClick = onNewSession,
                        tint = colors.labelPrimary,
                    )
                    DsIconButton(
                        icon = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.chatlist_search_hint),
                        onClick = {
                            if (searchOpen) {
                                searchOpen = false
                                historyQuery = ""
                                keyboardController?.hide()
                            } else {
                                searchOpen = true
                            }
                        },
                        tint = if (searchOpen) colors.accent else colors.labelPrimary,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LocalUsageModePill(
                        selected = usageMode,
                        enabled = modeSwitchEnabled,
                        onSelect = onUsageModeChange,
                    )
                }

                DsExpandableColumn(visible = searchOpen) {
                    OutlinedTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .padding(bottom = DsSpacing.small),
                        placeholder = { Text(stringResource(R.string.chatlist_search_hint)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = colors.labelTertiary,
                            )
                        },
                        singleLine = true,
                        shape = DsShapes.block,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium),
                contentPadding = PaddingValues(bottom = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                item(key = "drawer-actions") {
                    DsGroupCard {
                        if (usageMode == LocalUsageMode.CHAT) {
                            DrawerPrimaryAction(
                                icon = Icons.Outlined.PersonSearch,
                                title = stringResource(R.string.local_group_chat),
                                trailing = groupMemberCount.takeIf { it > 0 }?.toString(),
                                onClick = onOpenGroupChat,
                            )
                            DrawerPrimaryAction(
                                icon = Icons.Outlined.Image,
                                title = stringResource(R.string.persona_gallery_title),
                                trailing = galleryCount.toString(),
                                onClick = onOpenPersonaGallery,
                            )
                            DrawerPrimaryAction(
                                icon = Icons.Outlined.Schedule,
                                title = stringResource(R.string.tasks_chat_title),
                                onClick = onTasks,
                            )
                        } else {
                            DrawerPrimaryAction(
                                icon = FeatherIcons.FileText,
                                title = stringResource(R.string.chatlist_workspace_files),
                                onClick = onWorkspaceFiles,
                            )
                            DrawerPrimaryAction(
                                icon = FeatherIcons.CheckSquare,
                                title = stringResource(R.string.local_run_center),
                                onClick = onOpenRunCenter,
                            )
                        }
                    }
                }
                item(key = "drawer-history-heading") {
                    DrawerSectionTitle(
                        title = stringResource(
                            if (usageMode == LocalUsageMode.CHAT) R.string.chatlist_title
                            else R.string.local_work_history_title,
                        ),
                        count = filteredSessions.size,
                    )
                }
                if (visibleSessions.isNotEmpty() && filteredSessions.isEmpty()) {
                    item(key = "drawer-no-sessions") {
                        Text(
                            stringResource(R.string.chatlist_search_empty),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
                        )
                    }
                }
                items(filteredSessions, key = { "session:${it.id}" }) { session ->
                    val galleryEntry = session.galleryId?.let { galleryById[it] }
                    val displayTitle = sessionTitleOverrides[session.id] ?: session.title
                    LocalSessionDrawerRow(
                        title = displayTitle,
                        summaryPreview = session.summaryPreview,
                        updatedAt = session.updatedAt,
                        avatarName = galleryEntry?.persona?.name ?: displayTitle,
                        portraitPath = galleryEntry?.portraitPath.orEmpty(),
                        groupChat = session.chatMode == LocalChatMode.GROUP,
                        current = session.id == currentSessionId,
                        selected = session.id in selectedIds,
                        selectionOpen = selectionOpen,
                        onClick = {
                            if (selectionOpen) {
                                if (session.id in selectedIds) selectedIds.remove(session.id)
                                else selectedIds.add(session.id)
                            } else onSwitchSession(session.id)
                        },
                        onLongClick = {
                            if (session.id !in selectedIds) selectedIds.add(session.id)
                            selectionOpen = true
                        },
                        onDelete = { onDeleteSessions(setOf(session.id)) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                if (selectionOpen) {
                    Surface(
                        shape = DsShapes.block,
                        color = colors.wallpaperSurface(WallpaperSurfaceLevel.MENU),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            Text(
                                stringResource(R.string.local_selected_count, selectedIds.size),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            DsButton(
                                text = stringResource(R.string.local_delete_session),
                                onClick = {
                                    val ids = selectedIds.toSet()
                                    selectionOpen = false
                                    selectedIds.clear()
                                    if (ids.isNotEmpty()) onDeleteSessions(ids)
                                },
                                enabled = selectedIds.isNotEmpty(),
                                variant = DsButtonVariant.Danger,
                                size = DsButtonSize.Small,
                            )
                            DsIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_close),
                                onClick = {
                                    selectionOpen = false
                                    selectedIds.clear()
                                },
                                tint = colors.labelSecondary,
                            )
                        }
                    }
                } else {
                    DsGroupCard {
                        if (usageMode == LocalUsageMode.WORK) {
                            DrawerPrimaryAction(
                                icon = Icons.Outlined.Schedule,
                                title = stringResource(R.string.tasks_title),
                                onClick = onTasks,
                            )
                            DrawerPrimaryAction(
                                icon = Icons.Outlined.Extension,
                                title = stringResource(R.string.tools_title),
                                onClick = onTools,
                            )
                            DrawerPrimaryAction(
                                icon = Icons.Outlined.QrCodeScanner,
                                title = stringResource(R.string.local_remote_control),
                                onClick = onRemote,
                            )
                        }
                        DrawerPrimaryAction(
                            icon = Icons.Outlined.Settings,
                            title = stringResource(R.string.settings_title),
                            onClick = onSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerPrimaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: String? = null,
    iconFamily: DsIconFamily = DsIconFamily.Neutral,
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
        label = "drawerActionFeedback",
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
    ) {
        DsIconBox(icon = icon, family = iconFamily)
        Spacer(Modifier.width(DsSpacing.small))
        Text(
            title,
            style = DsType.base16Strong.withReadingWeight(),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            DsPill(
                text = it,
                modifier = Modifier.padding(start = DsSpacing.small),
            )
        }
    }
}

@Composable
private fun DrawerSectionTitle(
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
private fun LocalSessionDrawerRow(
    title: String,
    summaryPreview: String?,
    updatedAt: Long,
    avatarName: String,
    portraitPath: String,
    groupChat: Boolean,
    current: Boolean,
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
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.local_delete_session),
            onClick = { swipe = 0f; onDelete() },
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
                    targetValue = colors.wallpaperSurface(
                        level = WallpaperSurfaceLevel.CARD,
                        base = if (current || selected) colors.sidebarNavActive else colors.sidebar,
                    ),
                    animationSpec = DsAnimations.interactionColor,
                    label = "sessionRowBackground",
                ).value,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionOpen) Checkbox(checked = selected, onCheckedChange = { onClick() })
        LocalPersonaHeaderAvatar(
            name = avatarName,
            portraitPath = portraitPath,
        )
        Spacer(Modifier.width(DsSpacing.small))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = if (current) DsType.std14Strong else DsType.std14,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summaryPreview?.let { preview ->
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
                    relativeTime(updatedAt),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelCaption,
                    maxLines = 1,
                )
                if (groupChat) {
                    DsPill(text = stringResource(R.string.local_group_chat_title))
                }
                if (current) {
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
