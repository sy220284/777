package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.WorkspaceRow
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconFamily
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.rememberHostsStore
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


internal data class DrawerSessionSections(
    val current: SessionRow?,
    val history: List<SessionRow>,
)

/**
 * Split the harness's existing session-list records for the drawer.
 *
 * The current row is the exact record selected by [currentSessionId]. Historical rows are the
 * remaining durable, non-archived records; no workspace grouping or synthetic row decides whether
 * a record is visible. Blank rows stay out of both current and history sections so the sidebar only
 * shows sessions that contain actual conversation content.
 */
internal fun drawerSessionSections(
    sessions: List<SessionRow>,
    archivedIds: Set<String>,
    currentSessionId: String?,
    sortByRecency: Boolean,
): DrawerSessionSections {
    val current = currentSessionId?.let { id ->
        sessions.firstOrNull {
            it.sessionId == id && it.sessionId !in archivedIds && !it.blank
        }
    }
    val history = sessions.filter {
        it.sessionId !in archivedIds &&
            it.sessionId != currentSessionId &&
            !it.blank
    }.let { rows ->
        if (sortByRecency) rows.sortedByDescending(SessionRow::updatedAt) else rows
    }
    return DrawerSessionSections(current = current, history = history)
}

/** [com.labteto.dshmobile.connection.HostsStore.sessionSort]: the workspace's own row order. */
private const val SORT_MANUAL = "manual"

/** [com.labteto.dshmobile.connection.HostsStore.sessionSort]: most recently updated first. */
private const val SORT_UPDATED = "updated"

/**
 * The chat history: workspaces, their sessions, and search.
 *
 * Two rules keep it readable. Blank sessions are hidden — the harness treats a session with no turn
 * as scratch space and reuses it, so listing them just accumulates empty rows. And times are
 * relative, because a clock time cannot distinguish "an hour ago" from "last Tuesday".
 */
@Composable
fun ChatListDrawer(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLocalHarness: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenTools: () -> Unit,
) {
    val colors = DsTheme.colors
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    val hostsStore = rememberHostsStore()
    val sessionRowActions = remember(store) {
        SessionRowActions(
            open = { sessionId -> store.openSession(sessionId) },
            unarchive = { sessionId -> store.unarchiveSession(sessionId) },
            fork = { sessionId -> store.forkSession(sessionId) },
            rename = { sessionId, title -> store.renameSession(sessionId, title) },
            archive = { sessionId -> store.archiveSession(sessionId) },
        )
    }

    val sessions by store.sessions.collectAsStateWithLifecycle()
    val workspaces by store.workspaces.collectAsStateWithLifecycle()
    val archivedIds by store.archivedSessionIds.collectAsStateWithLifecycle()
    val searchResults by store.searchResults.collectAsStateWithLifecycle()
    val contentSearchAvailable by store.contentSearchAvailable.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val hostInfo by store.hostInfo.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    // Persisted, not remembered: the order you read your sessions in is a preference, and it used
    // to reset every time the drawer was closed.
    val sessionSort by hostsStore.sessionSort.collectAsStateWithLifecycle(initialValue = SORT_MANUAL)
    val sortByRecency = sessionSort == SORT_UPDATED
    var newWorkspaceOpen by remember { mutableStateOf(false) }
    var newSessionOpen by remember { mutableStateOf(false) }
    var manageWorkspacesOpen by remember { mutableStateOf(false) }
    var workspaceMenuTarget by remember { mutableStateOf<WorkspaceRow?>(null) }
    var workspaceRenameTarget by remember { mutableStateOf<WorkspaceRow?>(null) }
    var workspaceDeleteTarget by remember { mutableStateOf<WorkspaceRow?>(null) }
    var workspacePanelKey by remember { mutableStateOf<ComposerKey?>(null) }
    var expandedSubagentParents by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(query) {
        delay(250)
        store.search(query.trim())
    }

    // Local matching is not debounced: it is a string comparison over a list already in memory, and
    // making someone wait a quarter second for it is what made search feel like it did nothing.
    val searchHits = remember(sessions, workspaces, archivedIds, query, searchResults) {
        deriveSearchResults(
            sessions = sessions,
            workspaces = workspaces,
            archivedIds = archivedIds,
            query = query,
            contentHits = searchResults,
        )
    }

    // Current/history are projections of the existing session-list records. The drawer must never
    // manufacture a second source of truth or hide a durable row behind workspace expansion.
    val sections = remember(sessions, archivedIds, currentSessionId, sortByRecency) {
        drawerSessionSections(
            sessions = sessions,
            archivedIds = archivedIds,
            currentSessionId = currentSessionId,
            sortByRecency = sortByRecency,
        )
    }
    val currentListSession = sections.current
    val historySessions = sections.history
    val visibleSessionRows = remember(currentListSession, historySessions) {
        buildList {
            currentListSession?.let { add(it) }
            addAll(historySessions)
        }
    }
    val subagentTree = remember(visibleSessionRows, sessions, currentSessionId) {
        indexSubagents(
            listable = visibleSessionRows,
            sessionsById = sessions.associateBy(SessionRow::sessionId),
        ).mapValues { (_, children) ->
            children.filterNot { it.sessionId == currentSessionId }
        }.filterValues { it.isNotEmpty() }
    }
    val nestedSubagentIds = remember(subagentTree) {
        subagentTree.values.flatten().mapTo(hashSetOf(), SessionRow::sessionId)
    }
    val historyRootSessions = remember(historySessions, nestedSubagentIds) {
        historySessions.filterNot { it.sessionId in nestedSubagentIds }
    }
    val archivedSessions = sessions.filter { it.sessionId in archivedIds && !it.blank }
    val hasVisibleSessions =
        currentListSession != null || historySessions.isNotEmpty() || archivedSessions.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.wallpaperSurface(WallpaperSurfaceLevel.DRAWER, BackgroundRegion.ALL, colors.sidebar))
            .safeDrawingPadding()
            .padding(horizontal = DsSpacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.medium, bottom = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_name), style = DsType.large20.withReadingWeight(), color = colors.labelPrimary, modifier = Modifier.weight(1f))
            DsIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chatlist_new_session),
                onClick = { newSessionOpen = true },
                tint = colors.labelPrimary,
                containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, BackgroundRegion.TOP),
                shadowElevation = 2.dp,
            )
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = DsSpacing.small),
            placeholder = { Text(stringResource(R.string.chatlist_search_hint), style = DsType.std14.withReadingWeight()) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(20.dp),
                )
            },
            singleLine = true,
            shape = DsShapes.pillFull,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.INPUT, BackgroundRegion.TOP),
                unfocusedContainerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.INPUT, BackgroundRegion.TOP),
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = colors.accent,
            ),
        )
        if (!contentSearchAvailable && query.isNotBlank()) {
            Text(
                stringResource(R.string.chatlist_search_content_off),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelCaption,
                modifier = Modifier.padding(bottom = DsSpacing.small),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = DsSpacing.comfortable),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SectionHeader(stringResource(R.string.chatlist_title))
            }
            DsPill(
                text = (historySessions.size + if (currentListSession != null) 1 else 0).toString(),
            )
            Spacer(Modifier.width(DsSpacing.xsmall))
            SortChip(sortByRecency) { next ->
                scope.launch { hostsStore.setSessionSort(if (next) SORT_UPDATED else SORT_MANUAL) }
            }
        }

        Spacer(Modifier.height(DsSpacing.small))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (query.isNotBlank()) {
                item(key = "search-header") { SectionHeader(stringResource(R.string.common_search)) }
                if (searchHits.items.isEmpty()) {
                    item(key = "search-empty") {
                        Text(
                            stringResource(R.string.chatlist_search_empty),
                            style = DsType.std14.withReadingWeight(),
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = DsSpacing.small),
                        )
                    }
                }
                items(searchHits.items, key = { it.session.sessionId }) { hit ->
                    SearchResultRow(hit, sessionRowActions, scope, onClose)
                }
                if (searchHits.hasMore) {
                    item(key = "search-more") {
                        Text(
                            stringResource(R.string.chatlist_search_refine),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelCaption,
                            modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                        )
                    }
                }
                return@LazyColumn
            }

            currentListSession?.let { current ->
                sessionTreeItem(
                    session = current,
                    currentSessionId = currentSessionId,
                    tree = subagentTree,
                    expandedIds = expandedSubagentParents,
                    onToggle = { id ->
                        expandedSubagentParents = if (id in expandedSubagentParents) {
                            expandedSubagentParents - id
                        } else {
                            expandedSubagentParents + id
                        }
                    },
                    actions = sessionRowActions,
                    scope = scope,
                    onClose = onClose,
                )
            }

            historyRootSessions.forEach { session ->
                sessionTreeItem(
                    session = session,
                    currentSessionId = currentSessionId,
                    tree = subagentTree,
                    expandedIds = expandedSubagentParents,
                    onToggle = { id ->
                        expandedSubagentParents = if (id in expandedSubagentParents) {
                            expandedSubagentParents - id
                        } else {
                            expandedSubagentParents + id
                        }
                    },
                    actions = sessionRowActions,
                    scope = scope,
                    onClose = onClose,
                )
            }

            if (archivedSessions.isNotEmpty()) {
                item(key = "archived") {
                    var archivedExpanded by remember { mutableStateOf(false) }
                    DisclosureRow(
                        title = stringResource(R.string.chatlist_archived),
                        summary = archivedSessions.size.toString(),
                        expanded = archivedExpanded,
                        onToggle = { archivedExpanded = !archivedExpanded },
                    ) {
                        archivedSessions.forEach { session ->
                            SessionRowItem(session, false, sessionRowActions, scope, onClose, archived = true)
                        }
                    }
                }
            }

            if (!hasVisibleSessions) {
                item(key = "empty") {
                    EmptyHero(
                        headline = stringResource(R.string.chatlist_empty),
                        subtitle = stringResource(R.string.chatlist_empty_hint),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DsSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DsSpacing.touchTarget)
                    .clip(DsShapes.row)
                    .clickable { newWorkspaceOpen = true }
                    .padding(horizontal = DsSpacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(DsSpacing.small))
                Text(
                    stringResource(R.string.chatlist_new_workspace),
                    style = DsType.std14.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }
            if (workspaces.isNotEmpty()) {
                DsButton(
                    text = stringResource(R.string.chatlist_manage_workspaces),
                    onClick = { manageWorkspacesOpen = true },
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            DsGroupCard {
                DsCategoryRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.chatlist_workspace_files),
                    iconFamily = DsIconFamily.Cyan,
                    onClick = if (currentSessionId != null && connection.host != null) {
                        {
                            val sessionId = currentSessionId
                            val hostKey = connection.host?.let { "${it.baseUrl}|${it.id}" }
                            if (sessionId != null && hostKey != null) {
                                val key = ComposerKey(hostKey, sessionId)
                                store.panels.get(key).section = 0
                                workspacePanelKey = key
                                onClose()
                            }
                        }
                    } else {
                        null
                    },
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.tasks_title),
                    iconFamily = DsIconFamily.Amber,
                    onClick = {
                        onClose()
                        onOpenTasks()
                    },
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Extension,
                    title = stringResource(R.string.tools_title),
                    iconFamily = DsIconFamily.Neutral,
                    onClick = {
                        onClose()
                        onOpenTools()
                    },
                )
                DsCategoryRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = stringResource(R.string.chatlist_exit_remote_control),
                    iconFamily = DsIconFamily.Cyan,
                    onClick = {
                        onClose()
                        onOpenLocalHarness()
                    },
                )
                DsCategoryRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.settings_title),
                    iconFamily = DsIconFamily.Neutral,
                    onClick = {
                        onClose()
                        onOpenSettings()
                    },
                )
            }
        }

    }

    workspacePanelKey?.let { key ->
        WorkspacePanels(
            store = store,
            state = store.panels.get(key),
            mode = WorkspacePanelMode.WORKSPACE,
            onDismiss = { workspacePanelKey = null },
        )
    }

    if (manageWorkspacesOpen) {
        ManageWorkspacesDialog(
            workspaces = workspaces,
            onDismiss = { manageWorkspacesOpen = false },
            onPick = { workspace ->
                manageWorkspacesOpen = false
                workspaceMenuTarget = workspace
            },
        )
    }
    workspaceMenuTarget?.let { workspace ->
        WorkspaceMenu(
            onDismiss = { workspaceMenuTarget = null },
            onNewSession = {
                workspaceMenuTarget = null
                scope.launch {
                    store.createSession(workspaceId = workspace.workspaceId)
                    onClose()
                }
            },
            onRename = {
                workspaceMenuTarget = null
                workspaceRenameTarget = workspace
            },
            onDelete = {
                workspaceMenuTarget = null
                workspaceDeleteTarget = workspace
            },
        )
    }
    workspaceRenameTarget?.let { workspace ->
        RenameDialog(
            initial = workspace.title,
            title = stringResource(R.string.chatlist_workspace_rename),
            onDismiss = { workspaceRenameTarget = null },
            onConfirm = {
                scope.launch { store.renameWorkspace(workspace.workspaceId, it) }
                workspaceRenameTarget = null
            },
        )
    }
    workspaceDeleteTarget?.let { workspace ->
        ConfirmDialog(
            title = stringResource(R.string.chatlist_workspace_delete),
            body = stringResource(R.string.chatlist_workspace_delete_confirm),
            confirmLabel = stringResource(R.string.common_remove),
            onDismiss = { workspaceDeleteTarget = null },
            onConfirm = {
                scope.launch { store.deleteWorkspace(workspace.workspaceId) }
                workspaceDeleteTarget = null
            },
        )
    }

    if (newSessionOpen) {
        NewSessionDialog(
            workspaces = workspaces,
            homeCwd = hostInfo?.home,
            onPick = { workspaceId ->
                newSessionOpen = false
                scope.launch {
                    store.createSession(workspaceId = workspaceId)
                    onClose()
                }
            },
            onDismiss = { newSessionOpen = false },
        )
    }

    if (newWorkspaceOpen) {
        NewWorkspaceDialog(
            onDismiss = { newWorkspaceOpen = false },
            onCreate = { path ->
                scope.launch {
                    store.createWorkspace(path)
                    newWorkspaceOpen = false
                }
            },
        )
    }
}
