package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.labteto.dshmobile.ui.theme.DsMetrics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsExpandableColumn
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.main.RenameDialog
import com.labteto.dshmobile.ui.screens.main.ConfirmDialog
import com.labteto.dshmobile.ui.sidebar.SidebarAvatarPicker
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch

@Composable
internal fun LocalModeDrawer(
    currentSessionId: String,
    sessions: List<LocalSessionSummary>,
    gallery: List<PersonaGalleryEntry>,
    usageMode: LocalUsageMode,
    modeSwitchEnabled: Boolean,
    drawerOpen: Boolean,
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
    onDeleteSessions: suspend (Set<String>) -> Int,
    onRenameSession: (String, String) -> Boolean,
    onTogglePinSession: (String) -> Unit,
    onWorkspaceFiles: () -> Unit,
    onProjects: () -> Unit,
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
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var deleteCandidateId by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val selectedIds = remember { mutableStateListOf<String>() }
    val deleteScope = rememberCoroutineScope()
    var deleteInFlight by remember { mutableStateOf(false) }
    fun requestDelete(ids: Set<String>) {
        if (deleteInFlight || ids.isEmpty()) return
        deleteInFlight = true
        deleteScope.launch {
            try {
                if (onDeleteSessions(ids) > 0) {
                    selectedIds.removeAll(ids)
                    if (selectedIds.isEmpty()) selectionOpen = false
                }
            } finally {
                deleteInFlight = false
            }
        }
    }
    LaunchedEffect(usageMode) {
        selectionOpen = false
        selectedIds.clear()
    }

    BackHandler(enabled = drawerOpen && (selectionOpen || searchOpen)) {
        when {
            selectionOpen -> {
                selectionOpen = false
                selectedIds.clear()
            }
            searchOpen -> {
                searchOpen = false
                historyQuery = ""
                keyboardController?.hide()
            }
        }
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
        drawerShape = androidx.compose.ui.graphics.RectangleShape,
        drawerContainerColor = colors.wallpaperSurface(
            WallpaperSurfaceLevel.DRAWER,
            base = colors.sidebar,
        ),
        modifier = Modifier.fillMaxHeight().width(LocalConfiguration.current.screenWidthDp.dp * DsMetrics.drawerWidthFraction),
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
                    SidebarAvatarPicker()
                    Spacer(Modifier.width(DsSpacing.small))
                    Text(
                        stringResource(R.string.app_name),
                        style = DsType.title22.withReadingWeight(),
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DsIconButton(
                        icon = FeatherIcons.Search,
                        contentDescription = stringResource(R.string.common_search),
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) {
                                historyQuery = ""
                                keyboardController?.hide()
                            }
                        },
                        containerColor = colors.bgLayer1,
                        tint = colors.labelPrimary,
                    )
                    DsIconButton(
                        icon = FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.chatlist_new_session),
                        onClick = onNewSession,
                        containerColor = colors.bgLayer1,
                        tint = colors.labelPrimary,
                    )

                }

                LocalUsageModePill(
                    selected = usageMode,
                    enabled = modeSwitchEnabled,
                    onSelect = onUsageModeChange,
                )
                if (searchOpen) {
                    DsTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                        placeholder = { Text(stringResource(R.string.chatlist_search_hint)) },
                        leadingIcon = {
                            Icon(FeatherIcons.Search, contentDescription = null, tint = colors.labelTertiary)
                        },
                        singleLine = true,
                        shape = DsShapes.pillFull,
                        flat = true,
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
                item(key = "drawer-feature-actions") {
                    Column(
                        modifier = Modifier.padding(top = DsSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                    ) {
                        DrawerQuickActions(
                            usageMode = usageMode,
                            groupMemberCount = groupMemberCount,
                            galleryCount = gallery.size,
                            onOpenGroupChat = onOpenGroupChat,
                            onOpenPersonaGallery = onOpenPersonaGallery,
                            onOpenDiary = onOpenDiary,
                            onTasks = onTasks,
                            onWorkspaceFiles = onWorkspaceFiles,
                            onProjects = onProjects,
                            onOpenRunCenter = onOpenRunCenter,
                            onTools = onTools,
                        )
                    }
                }

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
                            onSelectMultiple = {
                                if (session.id !in selectedIds) selectedIds.add(session.id)
                                selectionOpen = true
                            },
                            onRename = { renameTargetId = session.id },
                            onTogglePinned = { onTogglePinSession(session.id) },
                            onDelete = { deleteCandidateId = session.id },
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
                            onSelectMultiple = {
                                if (session.id !in selectedIds) selectedIds.add(session.id)
                                selectionOpen = true
                            },
                            onRename = { renameTargetId = session.id },
                            onTogglePinned = { onTogglePinSession(session.id) },
                            onDelete = { deleteCandidateId = session.id },
                        )
                    }
                }

                if (hasVisibleSessions && sessionSections.pinned.isEmpty() && sessionSections.recent.isEmpty()) {
                    item(key = "drawer-no-sessions") {
                        Text(
                            stringResource(R.string.chatlist_search_empty),
                            style = DsType.navigationSupporting.withReadingWeight(),
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
                                style = DsType.navigationSupporting.withReadingWeight(),
                                color = colors.labelPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            DsButton(
                                text = stringResource(R.string.local_delete_session),
                                onClick = { requestDelete(selectedIds.toSet()) },
                                enabled = selectedIds.isNotEmpty() && !deleteInFlight,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DsIconButton(
                            icon = FeatherIcons.Device,
                            contentDescription = stringResource(R.string.local_remote_control),
                            onClick = onRemote,
                            containerColor = colors.bgLayer1,
                        )
                        Spacer(Modifier.width(DsSpacing.small))
                        DsIconButton(
                            icon = FeatherIcons.Gear,
                            contentDescription = stringResource(R.string.settings_title),
                            onClick = onSettings,
                            containerColor = colors.bgLayer1,
                        )
                    }
                }
            }
        }
        renameTargetId?.let { id ->
            RenameDialog(
                initial = sessionTitleOverrides[id]
                    ?: sessions.firstOrNull { it.id == id }?.title.orEmpty(),
                title = stringResource(R.string.chatlist_session_rename),
                onDismiss = { renameTargetId = null },
                onConfirm = { title ->
                    if (onRenameSession(id, title)) renameTargetId = null
                },
            )
        }
        deleteCandidateId?.let { id ->
            ConfirmDialog(
                title = stringResource(R.string.local_delete_session),
                body = stringResource(R.string.local_delete_session_confirm_message),
                confirmLabel = stringResource(R.string.local_delete_session),
                onDismiss = { deleteCandidateId = null },
                onConfirm = {
                    deleteCandidateId = null
                    requestDelete(setOf(id))
                },
            )
        }

    }
}

