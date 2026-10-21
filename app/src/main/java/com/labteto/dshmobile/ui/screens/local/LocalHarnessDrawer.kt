package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric




import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.labteto.dshmobile.ui.shenyan.LocalSyPalette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.labteto.dshmobile.ui.shenyan.SyDimensions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.components.SySceneBackgroundSurface
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyIconButton
import com.shenyan.designsystem.components.SyCategoryRow
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.sidebar.SidebarAvatarPicker
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch

/** Narrow rail uses the same mode-dependent destination as the expanded drawer. */
internal fun localCollapsedRailPrimaryEntry(mode: LocalUsageMode): LocalFeatureDrawerEntry =
    if (mode == LocalUsageMode.CHAT) LocalFeatureDrawerEntry.PERSONA_GALLERY
    else LocalFeatureDrawerEntry.WORKSPACE

internal fun localCollapsedRailTaskTitle(mode: LocalUsageMode): Int =
    if (mode == LocalUsageMode.CHAT) R.string.tasks_chat_title else R.string.tasks_title

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
    onSwitchSession: (String) -> Unit,
    onDeleteSessions: suspend (Set<String>) -> Int,
    onRenameSession: (String, String) -> Boolean,
    onTogglePinSession: (String) -> Unit,
    onWorkspaceFiles: () -> Unit,
    searchTasks: List<AutomationTask>,
    loadSearchFiles: suspend () -> List<LocalWorkspaceFile>,
    onSearchTask: (AutomationTask) -> Unit,
    onSearchFile: (String) -> Unit,
    onOpenGroupChat: () -> Unit,
    onOpenPersonaGallery: () -> Unit,
    onOpenPersonaSearchResult: (String) -> Unit,
    onOpenDiary: () -> Unit,
    onTasks: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
    onMiniMaxQuota: (() -> Unit)? = null,
    onUsage: (() -> Unit)? = null,
    globalSearchOpenRequest: Int = 0,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val sy = LocalSyPalette.current
    // The drawer has its own visual backing: raw L2 on the scene looked fully transparent.
    // Wallpaper mode retains a faint view of the user's image without sacrificing legibility.
    val expandLabel = stringResource(R.string.sy_v5_nav_expand)
    val collapseLabel = stringResource(R.string.sy_v5_nav_collapse)
    val docked = LocalConfiguration.current.screenWidthDp >= 1024
    var syRailCollapsed by rememberSaveable { mutableStateOf(false) }
    if (docked && syRailCollapsed) {
        // Compact rail shares the exact scene without drawing a solid vertical strip.
        SySceneBackgroundSurface(
            modifier = Modifier.width(SyDimensions.navigationCollapsed).fillMaxHeight(),
            shape = androidx.compose.ui.graphics.RectangleShape,
        ) {
        Column(
            Modifier.fillMaxHeight().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyIconButton(
                icon = FeatherIcons.ChevronRight,
                contentDescription = expandLabel,
                onClick = { syRailCollapsed = false }, tint = sy.text,
            )
            SyIconButton(icon = SyEntryIcons.NewSession, contentDescription = stringResource(R.string.sy_v5_new_session),
                onClick = onNewSession, tint = sy.text)
            when (localCollapsedRailPrimaryEntry(usageMode)) {
                LocalFeatureDrawerEntry.PERSONA_GALLERY ->
                    SyIconButton(icon = SyEntryIcons.Persona, contentDescription = stringResource(R.string.persona_gallery_title),
                        onClick = onOpenPersonaGallery, tint = sy.text)
                LocalFeatureDrawerEntry.WORKSPACE ->
                    SyIconButton(icon = SyEntryIcons.WorkspaceFiles, contentDescription = stringResource(R.string.chatlist_workspace_files),
                        onClick = onWorkspaceFiles, tint = sy.text)
                else -> error("Unexpected collapsed rail primary destination")
            }
            SyIconButton(icon = SyEntryIcons.Tasks, contentDescription = stringResource(localCollapsedRailTaskTitle(usageMode)),
                onClick = onTasks, tint = sy.text)
            SyIconButton(icon = SyEntryIcons.Settings, contentDescription = stringResource(R.string.sy_v5_settings),
                onClick = onSettings, tint = sy.text)
        }
        }
        return
    }
    var historyQuery by rememberSaveable(usageMode) { mutableStateOf("") }
    var searchOpen by rememberSaveable(usageMode) { mutableStateOf(false) }
    LaunchedEffect(globalSearchOpenRequest) {
        if (globalSearchOpenRequest > 0) searchOpen = true
    }
    var selectionOpen by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var deleteCandidateIds by remember { mutableStateOf<Set<String>>(emptySet()) }
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
    LaunchedEffect(sessions) {
        val availableIds = sessions.mapTo(hashSetOf()) { it.id }
        selectedIds.removeAll { it !in availableIds }
        if (renameTargetId != null && renameTargetId !in availableIds) renameTargetId = null
        if (deleteCandidateIds.any { it !in availableIds }) deleteCandidateIds = emptySet()
        if (selectedIds.isEmpty()) selectionOpen = false
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
    val crossModeSearch = searchOpen && historyQuery.isNotBlank()
    var searchableFiles by remember { mutableStateOf<List<LocalWorkspaceFile>>(emptyList()) }
    var fileSearchFailed by remember { mutableStateOf(false) }
    // Refresh the file snapshot when the user refines a query; do not keep a stale list from
    // the moment the drawer was opened. Debounce disk enumeration while the user is typing.
    LaunchedEffect(searchOpen, drawerOpen, historyQuery) {
        if (searchOpen && drawerOpen) {
            if (historyQuery.isNotBlank()) delay(300)
            fileSearchFailed = false
            try {
                searchableFiles = withContext(Dispatchers.IO) { loadSearchFiles() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                searchableFiles = emptyList()
                fileSearchFailed = true
            }
        } else if (!searchOpen) {
            searchableFiles = emptyList()
        }
    }
    val matchedTasks = remember(searchTasks, historyQuery, crossModeSearch) {
        if (crossModeSearch) localDrawerMatchingTasks(searchTasks, historyQuery) else emptyList()
    }
    val sortedSearchableFiles = remember(searchableFiles) {
        searchableFiles.asSequence().filter { isShareableWorkspacePath(it.path) }
            .distinctBy(LocalWorkspaceFile::path).sortedBy(LocalWorkspaceFile::path).toList()
    }
    val matchedFiles = remember(sortedSearchableFiles, historyQuery, crossModeSearch) {
        if (crossModeSearch) sortedSearchableFiles.filter {
            it.path.contains(historyQuery.trim(), ignoreCase = true)
        } else emptyList()
    }
    val sortedSessions = remember(sessions) {
        sessions.sortedByDescending(LocalSessionSummary::updatedAt)
    }
    val sessionSections = remember(
        sortedSessions,
        usageMode,
        pinnedSessionIds,
        historyQuery,
        sessionTitleOverrides,
        crossModeSearch,
    ) {
        localDrawerSessionSections(
            sessions = sortedSessions,
            usageMode = usageMode,
            pinnedSessionIds = pinnedSessionIds,
            query = historyQuery,
            sessionTitleOverrides = sessionTitleOverrides,
            includeOtherModes = crossModeSearch,
            preSortedByUpdatedAt = true,
        )
    }
    val matchedPersonas = remember(gallery, historyQuery, crossModeSearch) {
        if (crossModeSearch) localDrawerMatchingPersonas(gallery, historyQuery) else emptyList()
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
        drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        windowInsets = WindowInsets(0, 0, 0, 0), // Edge-to-edge backdrop, inset content only.
        modifier = Modifier.fillMaxHeight().width(
            if (docked) SyDimensions.navigationExpanded
            else LocalConfiguration.current.screenWidthDp.dp * DsMetrics.drawerWidthFraction
        ),
    ) {
        // The drawer continues the original background without a full-screen slab.
        SySceneBackgroundSurface(
            modifier = Modifier.fillMaxHeight(),
            shape = androidx.compose.ui.graphics.RectangleShape,
        ) {
        Column(Modifier.fillMaxHeight().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SyMetric.gapLg, end = SyMetric.gapLg, top = SyMetric.gapLg),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (docked) {
                        SyIconButton(
                            icon = FeatherIcons.ChevronLeft,
                            contentDescription = collapseLabel,
                            onClick = { syRailCollapsed = true }, tint = sy.text,
                        )
                    }
                    SidebarAvatarPicker()
                    Spacer(Modifier.width(SyMetric.gapMd))
                    Text(
                        stringResource(R.string.app_name),
                        style = DsType.title22.withReadingWeight(),
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SyIconButton(
                        icon = SyEntryIcons.Search,
                        contentDescription = stringResource(R.string.common_search),
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) {
                                historyQuery = ""
                                keyboardController?.hide()
                            }
                        },
                        tint = sy.text,
                    )
                    SyIconButton(
                        icon = SyEntryIcons.NewSession,
                        contentDescription = stringResource(R.string.chatlist_new_session),
                        onClick = onNewSession,
                        tint = sy.text,
                    )

                }

                LocalUsageModePill(
                    selected = usageMode,
                    enabled = modeSwitchEnabled,
                    onSelect = onUsageModeChange,
                )
                if (searchOpen) {
                    SyTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                        placeholder = { Text(stringResource(R.string.local_drawer_search_people_sessions)) },
                        leadingIcon = {
                            Icon(SyEntryIcons.Search, contentDescription = null, tint = colors.textTertiary)
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
                    .padding(horizontal = SyMetric.gapLg),
                contentPadding = PaddingValues(bottom = SyMetric.gapLg),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (!searchOpen || historyQuery.isBlank()) item(key = "drawer-feature-actions") {
                    Column(
                        modifier = Modifier.padding(top = SyMetric.gapLg),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                    ) {
                        DrawerQuickActions(
                            usageMode = usageMode,
                            onOpenGroupChat = onOpenGroupChat,
                            onOpenPersonaGallery = onOpenPersonaGallery,
                            onOpenDiary = onOpenDiary,
                            onTasks = onTasks,
                            onWorkspaceFiles = onWorkspaceFiles,
                            onTools = onTools,
                            onMiniMaxQuota = onMiniMaxQuota,
                            onUsage = onUsage,
                        )
                    }
                }

                if (matchedPersonas.isNotEmpty()) {
                    item(key = "drawer-persona-heading") {
                        DrawerSectionTitle(
                            title = stringResource(R.string.local_drawer_search_people_heading),
                            count = matchedPersonas.size,
                        )
                    }
                    items(matchedPersonas, key = { "persona:" + it.id }) { entry ->
                        SyCategoryRow(
                            icon = SyEntryIcons.Persona,
                            title = entry.persona.name,
                            subtitle = entry.persona.coreIdentity.ifBlank {
                                stringResource(R.string.local_drawer_search_open_gallery)
                            },
                            value = stringResource(R.string.local_drawer_search_open_gallery),
                            onClick = { onOpenPersonaSearchResult(entry.id) },
                        )
                    }
                }

                if (matchedTasks.isNotEmpty()) {
                    item(key = "drawer-task-search-heading") {
                        DrawerSectionTitle(stringResource(R.string.tasks_title), matchedTasks.size)
                    }
                    items(matchedTasks, key = { "task-search:" + it.id }) { task ->
                        SyCategoryRow(
                            icon = SyEntryIcons.Tasks,
                            title = task.prompt,
                            subtitle = task.actorName ?: task.id,
                            onClick = { onSearchTask(task) },
                        )
                    }
                }
                if (matchedFiles.isNotEmpty()) {
                    item(key = "drawer-file-search-heading") {
                        DrawerSectionTitle(stringResource(R.string.chatlist_workspace_files), matchedFiles.size)
                    }
                    items(matchedFiles, key = { "file-search:" + it.path }) { file ->
                        SyCategoryRow(
                            icon = SyEntryIcons.WorkspaceFiles,
                            title = file.path.substringAfterLast('/'),
                            subtitle = file.path,
                            onClick = { onSearchFile(file.path) },
                        )
                    }
                }
                if (crossModeSearch && fileSearchFailed) {
                    item(key = "drawer-file-search-error") {
                        Text(stringResource(R.string.local_files_read_failed),
                            color = colors.textTertiary,
                            style = DsType.navigationSupporting.withReadingWeight())
                    }
                }

                if (sessionSections.pinned.isNotEmpty()) {
                    item(key = "drawer-pinned-heading") {
                        DrawerSectionTitle(
                            title = stringResource(R.string.local_drawer_pinned),
                            count = sessionSections.pinned.size,
                        )
                    }
                    itemsIndexed(sessionSections.pinned, key = { _, item -> "pinned:" + item.id }) { _, session ->
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
                            onDelete = { deleteCandidateIds = setOf(session.id) },
                            searchModeLabel = if (crossModeSearch) stringResource(
                                if (session.usageMode == LocalUsageMode.CHAT)
                                    R.string.usage_mode_chat else R.string.usage_mode_work,
                            ) else null,
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
                    itemsIndexed(sessionSections.recent, key = { _, item -> "recent:" + item.id }) { _, session ->
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
                            onDelete = { deleteCandidateIds = setOf(session.id) },
                            searchModeLabel = if (crossModeSearch) stringResource(
                                if (session.usageMode == LocalUsageMode.CHAT)
                                    R.string.usage_mode_chat else R.string.usage_mode_work,
                            ) else null,
                        )
                    }
                }

                if (crossModeSearch && matchedPersonas.isEmpty() && matchedTasks.isEmpty() &&
                    matchedFiles.isEmpty() && sessionSections.pinned.isEmpty() && sessionSections.recent.isEmpty() &&
                    !fileSearchFailed) {
                    item(key = "drawer-no-sessions") {
                        Text(
                            stringResource(R.string.local_drawer_search_empty_both),
                            style = DsType.navigationSupporting.withReadingWeight(),
                            color = colors.textTertiary,
                            modifier = Modifier.padding(
                                horizontal = SyMetric.gapMd,
                                vertical = SyMetric.gapLg,
                            ),
                        )
                    }
                }


            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (selectionOpen) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        ) {
                            Text(
                                stringResource(R.string.local_selected_count, selectedIds.size),
                                style = DsType.navigationSupporting.withReadingWeight(),
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            SyButton(
                                text = stringResource(R.string.local_delete_session),
                                onClick = { deleteCandidateIds = selectedIds.toSet() },
                                enabled = selectedIds.isNotEmpty() && !deleteInFlight,
                                variant = SyButtonVariant.Danger,
                                size = SyButtonSize.Small,
                            )
                            SyIconButton(
                                icon = FeatherIcons.X,
                                contentDescription = stringResource(R.string.common_close),
                                onClick = {
                                    selectionOpen = false
                                    selectedIds.clear()
                                },
                                tint = colors.textSecondary,
                            )
                        }
                    }
                } else {
                    DrawerQuickAction(
                        icon = SyEntryIcons.Settings,
                        title = stringResource(R.string.settings_title),
                        onClick = onSettings,
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
        if (deleteCandidateIds.isNotEmpty()) {
            val ids = deleteCandidateIds
            ConfirmDialog(
                title = stringResource(R.string.local_delete_session),
                body = if (ids.size > 1) {
                    stringResource(R.string.local_delete_sessions_confirm_multiple, ids.size)
                } else stringResource(R.string.local_delete_session_confirm_message),
                confirmLabel = stringResource(R.string.local_delete_session),
                onDismiss = { deleteCandidateIds = emptySet() },
                onConfirm = {
                    deleteCandidateIds = emptySet()
                    requestDelete(ids)
                },
            )
        }

    }
}
