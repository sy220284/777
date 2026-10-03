package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsExpandableColumn
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
internal fun LocalModeDrawer(
    currentSessionId: String,
    sessions: List<LocalSessionSummary>,
    gallery: List<PersonaGalleryEntry>,
    usageMode: LocalUsageMode,
    modeSwitchEnabled: Boolean,
    pinnedSessionIds: Set<String>,
    sessionTitleOverrides: Map<String, String>,
    currentGalleryId: String?,
    currentGalleryStoryId: String?,
    currentPersonaName: String,
    currentPersonaIdentity: String,
    workModelLabel: String,
    groupChatEnabled: Boolean,
    running: Boolean,
    onUsageModeChange: (LocalUsageMode) -> Unit,
    onNewSession: () -> Unit,
    onRemote: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    onWorkspaceFiles: () -> Unit,
    onOpenRunCenter: () -> Unit,
    groupMemberCount: Int,
    onOpenGroupChat: () -> Unit,
    onOpenPersonaGallery: () -> Unit,
    onOpenDiary: () -> Unit,
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
    val currentGalleryEntry = remember(galleryById, currentGalleryId) {
        currentGalleryId?.let { galleryById[it] }
    }
    val currentStoryTitle = remember(currentGalleryEntry, currentGalleryStoryId) {
        currentGalleryStoryId?.let { id ->
            currentGalleryEntry?.stories?.firstOrNull { it.id == id }?.title
        }?.takeIf(String::isNotBlank)
    }
    val sessionSections = remember(
        sessions,
        usageMode,
        pinnedSessionIds,
        historyQuery,
        sessionTitleOverrides,
    ) {
        localDrawerSessionSections(
            sessions = sessions,
            usageMode = usageMode,
            pinnedSessionIds = pinnedSessionIds,
            query = historyQuery,
            sessionTitleOverrides = sessionTitleOverrides,
        )
    }
    val hasVisibleSessions = remember(sessions, usageMode) {
        sessions.any { !it.blank && it.usageMode == usageMode }
    }
    val currentSessionTitle = remember(
        sessions,
        currentSessionId,
        sessionTitleOverrides,
        usageMode,
    ) {
        val session = sessions.firstOrNull { it.id == currentSessionId && it.usageMode == usageMode }
        session?.let { sessionTitleOverrides[it.id] ?: it.title }.orEmpty()
    }

    ModalDrawerSheet(
        drawerContainerColor = colors.wallpaperSurface(
            WallpaperSurfaceLevel.DRAWER,
            base = colors.sidebar,
        ),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DsSpacing.medium, end = DsSpacing.medium, top = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        icon = FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.chatlist_new_session),
                        onClick = onNewSession,
                        tint = colors.labelPrimary,
                    )
                    DsIconButton(
                        icon = FeatherIcons.Search,
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

                LocalUsageModePill(
                    selected = usageMode,
                    enabled = modeSwitchEnabled,
                    onSelect = onUsageModeChange,
                )

                DsExpandableColumn(visible = searchOpen) {
                    OutlinedTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester),
                        placeholder = { Text(stringResource(R.string.chatlist_search_hint)) },
                        leadingIcon = {
                            Icon(
                                FeatherIcons.Search,
                                contentDescription = null,
                                tint = colors.labelTertiary,
                            )
                        },
                        singleLine = true,
                        shape = DsShapes.block,
                    )
                }

                DrawerContextCard(
                    usageMode = usageMode,
                    title = when {
                        usageMode == LocalUsageMode.CHAT && groupChatEnabled ->
                            stringResource(R.string.local_group_chat_title)
                        usageMode == LocalUsageMode.CHAT ->
                            currentPersonaName.ifBlank {
                                stringResource(R.string.local_chat_no_persona)
                            }
                        else -> currentSessionTitle.ifBlank {
                            stringResource(R.string.local_usage_work)
                        }
                    },
                    subtitle = when {
                        usageMode == LocalUsageMode.CHAT && groupChatEnabled ->
                            stringResource(R.string.local_group_chat_member_count, groupMemberCount)
                        usageMode == LocalUsageMode.CHAT ->
                            currentStoryTitle ?: currentPersonaIdentity.takeIf(String::isNotBlank)
                        running ->
                            workModelLabel + " · " + stringResource(R.string.local_drawer_running)
                        else -> workModelLabel
                    },
                    portraitPath = currentGalleryEntry?.portraitPath.orEmpty(),
                    group = usageMode == LocalUsageMode.CHAT && groupChatEnabled,
                    running = running,
                    onClick = when {
                        usageMode == LocalUsageMode.WORK -> onSettings
                        groupChatEnabled -> onOpenGroupChat
                        else -> onOpenPersonaGallery
                    },
                )

                DrawerQuickActions(
                    usageMode = usageMode,
                    groupMemberCount = groupMemberCount,
                    galleryCount = gallery.size,
                    onOpenGroupChat = onOpenGroupChat,
                    onOpenPersonaGallery = onOpenPersonaGallery,
                    onOpenDiary = onOpenDiary,
                    onTasks = onTasks,
                    onWorkspaceFiles = onWorkspaceFiles,
                    onOpenRunCenter = onOpenRunCenter,
                    onTools = onTools,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium),
                contentPadding = PaddingValues(bottom = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                if (sessionSections.pinned.isNotEmpty()) {
                    item(key = "drawer-pinned-heading") {
                        DrawerSectionTitle(
                            title = stringResource(R.string.local_drawer_pinned),
                            count = sessionSections.pinned.size,
                        )
                    }
                    items(sessionSections.pinned, key = { "pinned:" + it.id }) { session ->
                        LocalSessionDrawerRow(
                            session = session,
                            displayTitle = sessionTitleOverrides[session.id] ?: session.title,
                            galleryEntry = session.galleryId?.let { galleryById[it] },
                            current = session.id == currentSessionId,
                            pinned = true,
                            running = running && session.id == currentSessionId,
                            selected = session.id in selectedIds,
                            selectionOpen = selectionOpen,
                            onClick = {
                                if (selectionOpen) {
                                    if (session.id in selectedIds) selectedIds.remove(session.id)
                                    else selectedIds.add(session.id)
                                } else {
                                    onSwitchSession(session.id)
                                }
                            },
                            onLongClick = {
                                if (session.id !in selectedIds) selectedIds.add(session.id)
                                selectionOpen = true
                            },
                            onDelete = { onDeleteSessions(setOf(session.id)) },
                        )
                    }
                }

                if (sessionSections.recent.isNotEmpty()) {
                    item(key = "drawer-recent-heading") {
                        DrawerSectionTitle(
                            title = stringResource(R.string.local_drawer_recent),
                            count = sessionSections.recent.size,
                        )
                    }
                    items(sessionSections.recent, key = { "recent:" + it.id }) { session ->
                        LocalSessionDrawerRow(
                            session = session,
                            displayTitle = sessionTitleOverrides[session.id] ?: session.title,
                            galleryEntry = session.galleryId?.let { galleryById[it] },
                            current = session.id == currentSessionId,
                            pinned = false,
                            running = running && session.id == currentSessionId,
                            selected = session.id in selectedIds,
                            selectionOpen = selectionOpen,
                            onClick = {
                                if (selectionOpen) {
                                    if (session.id in selectedIds) selectedIds.remove(session.id)
                                    else selectedIds.add(session.id)
                                } else {
                                    onSwitchSession(session.id)
                                }
                            },
                            onLongClick = {
                                if (session.id !in selectedIds) selectedIds.add(session.id)
                                selectionOpen = true
                            },
                            onDelete = { onDeleteSessions(setOf(session.id)) },
                        )
                    }
                }

                if (hasVisibleSessions && sessionSections.pinned.isEmpty() && sessionSections.recent.isEmpty()) {
                    item(key = "drawer-no-sessions") {
                        Text(
                            stringResource(R.string.chatlist_search_empty),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(
                                horizontal = DsSpacing.small,
                                vertical = DsSpacing.medium,
                            ),
                        )
                    }
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
                                icon = FeatherIcons.X,
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
                    DrawerGlobalAction(
                        icon = FeatherIcons.Device,
                        title = stringResource(R.string.local_remote_control),
                        onClick = onRemote,
                    )
                    DrawerGlobalAction(
                        icon = FeatherIcons.Sliders,
                        title = stringResource(R.string.settings_title),
                        onClick = onSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContextCard(
    usageMode: LocalUsageMode,
    title: String,
    subtitle: String?,
    portraitPath: String,
    group: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
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
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DrawerQuickActions(
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        if (usageMode == LocalUsageMode.CHAT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                DrawerQuickAction(
                    icon = FeatherIcons.Users,
                    title = stringResource(R.string.local_group_chat_title),
                    badge = groupMemberCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenGroupChat,
                    modifier = Modifier.weight(1f),
                )
                DrawerQuickAction(
                    icon = FeatherIcons.Image,
                    title = stringResource(R.string.persona_gallery_title),
                    badge = galleryCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenPersonaGallery,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                DrawerQuickAction(
                    icon = FeatherIcons.BookOpen,
                    title = stringResource(R.string.chat_diary_title),
                    onClick = onOpenDiary,
                    modifier = Modifier.weight(1f),
                )
                DrawerQuickAction(
                    icon = FeatherIcons.Clock,
                    title = stringResource(R.string.tasks_chat_title),
                    onClick = onTasks,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                DrawerQuickAction(
                    icon = FeatherIcons.Folder,
                    title = stringResource(R.string.chatlist_workspace_files),
                    onClick = onWorkspaceFiles,
                    modifier = Modifier.weight(1f),
                )
                DrawerQuickAction(
                    icon = FeatherIcons.Activity,
                    title = stringResource(R.string.local_run_center),
                    onClick = onOpenRunCenter,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                DrawerQuickAction(
                    icon = FeatherIcons.Clock,
                    title = stringResource(R.string.tasks_title),
                    onClick = onTasks,
                    modifier = Modifier.weight(1f),
                )
                DrawerQuickAction(
                    icon = FeatherIcons.Tool,
                    title = stringResource(R.string.tools_title),
                    onClick = onTools,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DrawerQuickAction(
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
            else -> colors.wallpaperSurface(WallpaperSurfaceLevel.CARD)
        },
        animationSpec = DsAnimations.interactionColor,
        label = "drawerQuickActionFeedback",
    )
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
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
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let { DsPill(text = it) }
    }
}

@Composable
private fun DrawerGlobalAction(
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
                        targetValue = colors.wallpaperSurface(
                            level = WallpaperSurfaceLevel.CARD,
                            base = if (current || selected) colors.sidebarNavActive else colors.sidebar,
                        ),
                        animationSpec = DsAnimations.interactionColor,
                        label = "sessionRowBackground",
                    ).value,
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
