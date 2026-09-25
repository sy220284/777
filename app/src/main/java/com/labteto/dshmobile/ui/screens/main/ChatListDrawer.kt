package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.labteto.dshmobile.ui.components.AppBrandIcon
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
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
    var workspacePanelKey by remember { mutableStateOf<ComposerKey?>(null) }

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
    val archivedSessions = sessions.filter { it.sessionId in archivedIds && !it.blank }
    val hasVisibleSessions =
        currentListSession != null || historySessions.isNotEmpty() || archivedSessions.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.wallpaperSurface(WallpaperSurfaceLevel.DIALOG, BackgroundRegion.ALL, colors.sidebar))
            .safeDrawingPadding()
            .padding(horizontal = DsSpacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.medium, bottom = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppBrandIcon(Modifier.size(44.dp))
            Spacer(Modifier.width(DsSpacing.medium))
            Text(stringResource(R.string.app_name), style = DsType.large20, color = colors.labelPrimary, modifier = Modifier.weight(1f))
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
            placeholder = { Text(stringResource(R.string.chatlist_search_hint), style = DsType.std14) },
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
                style = DsType.caption11,
                color = colors.labelCaption,
                modifier = Modifier.padding(bottom = DsSpacing.small),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = DsSpacing.comfortable),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SectionHeader("会话")
            }
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
                            style = DsType.std14,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = DsSpacing.small),
                        )
                    }
                }
                items(searchHits.items, key = { it.session.sessionId }) { hit ->
                    SearchResultRow(hit, store, scope, onClose)
                }
                if (searchHits.hasMore) {
                    item(key = "search-more") {
                        Text(
                            stringResource(R.string.chatlist_search_refine),
                            style = DsType.caption11,
                            color = colors.labelCaption,
                            modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                        )
                    }
                }
                return@LazyColumn
            }

            currentListSession?.let { current ->
                item(key = "current-session-" + current.sessionId) {
                    SessionRowItem(
                        session = current,
                        isCurrent = true,
                        store = store,
                        scope = scope,
                        onClose = onClose,
                    )
                }
            }

            if (historySessions.isNotEmpty()) {
                items(historySessions, key = { it.sessionId }) { session ->
                    Box(Modifier.animateItem()) {
                        SessionRowItem(
                            session = session,
                            isCurrent = false,
                            store = store,
                            scope = scope,
                            onClose = onClose,
                        )
                    }
                }
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
                            SessionRowItem(session, false, store, scope, onClose)
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
                .clip(DsShapes.row)
                .clickable { newWorkspaceOpen = true }
                .padding(vertical = DsSpacing.small),
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
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            DsButton(
                text = stringResource(R.string.chatlist_workspace_files),
                onClick = {
                    val sessionId = currentSessionId
                    val hostKey = connection.host?.let { "${it.baseUrl}|${it.id}" }
                    if (sessionId != null && hostKey != null) {
                        val key = ComposerKey(hostKey, sessionId)
                        store.panels.get(key).section = 0
                        workspacePanelKey = key
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
                icon = FeatherIcons.FileText,
                enabled = currentSessionId != null && connection.host != null,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = "定时任务",
                    onClick = {
                        onClose()
                        onOpenTasks()
                    },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Ghost,
                    icon = Icons.Outlined.Schedule,
                )
                DsButton(
                    text = "工具与连接",
                    onClick = {
                        onClose()
                        onOpenTools()
                    },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Ghost,
                    icon = Icons.Outlined.Extension,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = "退出远程控制",
                    onClick = {
                        onClose()
                        onOpenLocalHarness()
                    },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Ghost,
                    icon = Icons.Outlined.PhoneAndroid,
                )
                DsButton(
                    text = stringResource(R.string.settings_title),
                    onClick = {
                        onClose()
                        onOpenSettings()
                    },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Ghost,
                    icon = Icons.Filled.Settings,
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

/**
 * The session-order control.
 *
 * It used to be a bare ⇅ icon whose only label was a content description, which told a sighted user
 * nothing: two arrows over a chat list could as easily mean sync, move, or reorder. Naming the
 * current order and offering the other one is the whole fix — and the strings for both modes were
 * kept in both maintained languages.
 */
@Composable
private fun SortChip(byRecency: Boolean, onPick: (byRecency: Boolean) -> Unit) {
    val colors = DsTheme.colors
    val updated = stringResource(R.string.chatlist_sort_updated)
    val manual = stringResource(R.string.chatlist_sort_manual)
    DsMenu(
        anchor = {
            Row(
                modifier = Modifier
                    .clip(DsShapes.cube)
                    .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Icon(
                    Icons.Filled.SwapVert,
                    contentDescription = stringResource(R.string.chatlist_sort_title),
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (byRecency) updated else manual,
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        items = listOf(
            MenuItem(text = manual) { onPick(false) },
            MenuItem(text = updated) { onPick(true) },
        ),
    )
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/**
 * A workspace header that collapses its group and carries the workspace verbs.
 *
 * Rename and remove exist on the wire and had no UI at all; a long-press menu is where a
 * phone user expects to find them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceHeader(
    workspace: WorkspaceRow,
    collapsed: Boolean,
    sessionCount: Int,
    onToggle: () -> Unit,
    store: SessionStore,
    scope: CoroutineScope,
    onNewSession: () -> Unit,
) {
    val colors = DsTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = DsAnimations.chevron,
        label = "workspaceChevron",
    )
    val label = workspace.title.ifBlank { basename(workspace.path) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .combinedClickable(onClick = onToggle, onLongClick = { menuOpen = true })
                .padding(vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
            Spacer(Modifier.width(DsSpacing.tiny))
            Text(
                label,
                style = DsType.std14Strong,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(sessionCount.toString(), style = DsType.caption11, color = colors.labelCaption)
        }
        if (menuOpen) {
            WorkspaceMenu(
                onDismiss = { menuOpen = false },
                onNewSession = {
                    menuOpen = false
                    onNewSession()
                },
                onRename = {
                    menuOpen = false
                    renaming = true
                },
                onDelete = {
                    menuOpen = false
                    deleting = true
                },
            )
        }
    }

    if (renaming) {
        RenameDialog(
            initial = workspace.title,
            title = stringResource(R.string.chatlist_workspace_rename),
            onDismiss = { renaming = false },
            onConfirm = {
                scope.launch { store.renameWorkspace(workspace.workspaceId, it) }
                renaming = false
            },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.chatlist_workspace_delete),
            body = stringResource(R.string.chatlist_workspace_delete_confirm),
            confirmLabel = stringResource(R.string.common_remove),
            onDismiss = { deleting = false },
            onConfirm = {
                scope.launch { store.deleteWorkspace(workspace.workspaceId) }
                deleting = false
            },
        )
    }
}

@Composable
private fun WorkspaceMenu(
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    DsDialog(title = null, onDismiss = onDismiss) {
        SheetRow(title = stringResource(R.string.chatlist_workspace_new_session), onClick = onNewSession)
        SheetRow(title = stringResource(R.string.chatlist_workspace_rename), onClick = onRename)
        SheetRow(title = stringResource(R.string.chatlist_workspace_delete), onClick = onDelete)
    }
}

/**
 * One session row: status, title, relative time, and the session verbs on long-press.
 *
 * [depth] indents the row under whatever spawned it, and a row with [childCount] subagents grows a
 * disclosure chevron that opens them in place. Subagents used to be dumped into one flat
 * "Subagents" heading per workspace, which said nothing about which run produced which — with a
 * dozen of them from three sessions it was a wall of near-identical rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowItem(
    session: SessionRow,
    isCurrent: Boolean,
    store: SessionStore,
    scope: CoroutineScope,
    onClose: () -> Unit,
    depth: Int = 0,
    childCount: Int = 0,
    childrenExpanded: Boolean = false,
    onToggleChildren: () -> Unit = {},
) {
    val colors = DsTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var archiveConfirmOpen by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (childrenExpanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "sessionChevron",
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
                .heightIn(min = DsSpacing.touchTarget)
                .clip(DsShapes.row)
                .background(if (isCurrent) colors.sidebarNavActive else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = {
                        scope.launch {
                            store.openSession(session.sessionId)
                            onClose()
                        }
                    },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sessionTitle(session),
                style = DsType.std14,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.pendingInteraction != null) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = stringResource(R.string.chatlist_needs_action), warn = true)
            } else if (session.running) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(stringResource(R.string.subagents_running), style = DsType.caption11, color = colors.accent)
            } else {
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(relativeTime(session.updatedAt), style = DsType.caption11, color = colors.labelCaption)
            }
            // The count replaces the old "Subagents" pill on parents: with the children indented
            // underneath, what is worth saying is how many are down there when the row is closed.
            if (childCount > 0) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = childCount.toString())
            } else if (session.origin == "subagent" && depth == 0) {
                // Only reached by an orphan — its whole ancestry is archived or blank — where the
                // indent cannot say what the row is.
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = stringResource(R.string.chatlist_subagents))
            }
            // Expansion stays available without placing an icon before the session title.
            if (childCount > 0) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.chatlist_subagents),
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                        .clickable(onClick = onToggleChildren),
                )
            }
        }

        if (menuOpen) {
            DsDialog(title = null, onDismiss = { menuOpen = false }) {
                SheetRow(title = stringResource(R.string.chatlist_session_rename)) {
                    menuOpen = false
                    renameOpen = true
                }
                SheetRow(title = stringResource(R.string.chatlist_session_fork)) {
                    menuOpen = false
                    scope.launch { store.forkSession(session.sessionId) }
                }
                SheetRow(title = stringResource(R.string.chatlist_session_archive)) {
                    menuOpen = false
                    archiveConfirmOpen = true
                }
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            initial = session.title.orEmpty(),
            title = stringResource(R.string.chatlist_session_rename),
            onDismiss = { renameOpen = false },
            onConfirm = {
                scope.launch { store.renameSession(session.sessionId, it) }
                renameOpen = false
            },
        )
    }

    if (archiveConfirmOpen) {
        ConfirmDialog(
            title = stringResource(R.string.chatlist_session_archive),
            body = sessionTitle(session),
            confirmLabel = stringResource(R.string.common_archive),
            onDismiss = { archiveConfirmOpen = false },
            onConfirm = {
                scope.launch { store.archiveSession(session.sessionId) }
                archiveConfirmOpen = false
            },
        )
    }
}

/**
 * One search result: the session's own name first, then where it lives, then the matching excerpt
 * if the host had one.
 *
 * The row used to lead with the excerpt and label itself with a raw session id, which is neither
 * something anyone searched for nor something they can recognise. A result should name the thing
 * you are about to open.
 */
@Composable
private fun SearchResultRow(
    hit: SearchHit,
    store: SessionStore,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable {
                scope.launch {
                    store.openSession(hit.session.sessionId)
                    onClose()
                }
            }
            .padding(horizontal = DsSpacing.tiny, vertical = DsSpacing.xsmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sessionTitle(hit.session),
                style = DsType.rowText,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hit.session.origin == "subagent") {
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = stringResource(R.string.chatlist_subagents))
            }
        }
        hit.workspaceLabel.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        hit.snippet?.let {
            Text(
                text = it,
                style = DsType.caption11,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun NewSessionDialog(
    workspaces: List<WorkspaceRow>,
    homeCwd: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = stringResource(R.string.chatlist_new_session_in), onDismiss = onDismiss) {
        if (workspaces.isEmpty()) {
            Text(
                stringResource(R.string.chatlist_no_workspaces),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }
        workspaces.forEach { workspace ->
            SheetRow(
                title = workspace.title.ifBlank { basename(workspace.path) },
                subtitle = workspace.path,
                onClick = { onPick(workspace.workspaceId) },
            )
        }
        SheetRow(
            title = stringResource(R.string.chatlist_home_directory),
            subtitle = homeCwd,
            onClick = { onPick(null) },
        )
    }
}

@Composable
private fun NewWorkspaceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var pathText by remember { mutableStateOf("") }
    DsDialog(title = stringResource(R.string.chatlist_new_workspace), onDismiss = onDismiss) {
        TextField(
            value = pathText,
            onValueChange = { pathText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.chatlist_workspace_path), style = DsType.std14) },
            singleLine = true,
            colors = dialogTextFieldColors(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.common_save),
                onClick = { onCreate(pathText.trim()) },
                variant = DsButtonVariant.Info,
                enabled = pathText.isNotBlank(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Walk a (possibly nested) subagent session's parent chain up to the session directly registered in
 * a workspace, returning that workspace id — or null for an orphan.
 */
/**
 * Group subagent sessions under the visible session that spawned them.
 *
 * Keyed by immediate parent, so a subagent that spawned its own subagents nests as deeply as the
 * run actually went. Free function so the rules that are easy to get wrong — forks staying at the
 * top level, orphans re-attaching, cycles not hanging — can be tested without a device.
 */
internal fun indexSubagents(
    listable: List<SessionRow>,
    sessionsById: Map<String, SessionRow>,
): Map<String, List<SessionRow>> {
    val listableIds = listable.mapTo(HashSet()) { it.sessionId }

    /**
     * The nearest ancestor that is actually on screen.
     *
     * Usually the immediate parent. The walk exists for the case that used to lose rows entirely:
     * archiving a session, or the harness reusing a blank one, removes it from the list while its
     * subagents remain — those attach to the next ancestor up rather than vanishing with it. The
     * visited set guards against a lineage cycle, which would otherwise hang the drawer.
     */
    fun attachPoint(child: SessionRow): String? {
        // Seeded with the child so a lineage cycle cannot walk back around and make the row its own
        // parent — which would nest it inside itself and render nothing at all.
        val visited = hashSetOf(child.sessionId)
        var cursor = child.parentSessionId
        while (cursor != null && visited.add(cursor)) {
            if (cursor in listableIds) return cursor
            cursor = sessionsById[cursor]?.parentSessionId
        }
        return null
    }

    return listable
        .filter { it.origin == "subagent" }
        .mapNotNull { child -> attachPoint(child)?.let { it to child } }
        .groupBy({ it.first }, { it.second })
}

/** Display title: an explicit title, else the working directory's folder, else the id. */
internal fun sessionTitle(session: SessionRow): String {
    val title = session.title?.takeIf { it.isNotBlank() }
    val folder = session.cwd?.takeIf { it.isNotBlank() }?.let { basename(it) }?.takeIf { it.isNotBlank() }
    return title ?: folder ?: session.sessionId
}
