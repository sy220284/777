package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.session.LocalSessionSummary
import com.shenyan.designsystem.components.SyIconButton
import com.shenyan.designsystem.components.SyIconBox
import com.labteto.dshmobile.ui.shenyan.LocalSyPalette
import com.shenyan.designsystem.components.SyCheckboxControl
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** The drawer uses only public persona-card fields; private dialogue/diaries are never searched here. */
internal fun localDrawerMatchingPersonas(
    gallery: List<PersonaGalleryEntry>,
    query: String,
): List<PersonaGalleryEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return gallery.filter { entry ->
        entry.persona.name.contains(needle, ignoreCase = true) ||
            entry.persona.coreIdentity.contains(needle, ignoreCase = true)
    }
}

/** Search the actual scheduler records and only user-visible workspace paths. */
internal fun localDrawerMatchingTasks(tasks: List<AutomationTask>, query: String): List<AutomationTask> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return tasks.filter {
        it.prompt.contains(needle, ignoreCase = true) ||
            it.actorName?.contains(needle, ignoreCase = true) == true ||
            it.id.contains(needle, ignoreCase = true)
    }
}

internal fun localDrawerMatchingFiles(files: List<LocalWorkspaceFile>, query: String): List<LocalWorkspaceFile> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return files.filter { file ->
        isShareableWorkspacePath(file.path) && file.path.contains(needle, ignoreCase = true)
    }.distinctBy(LocalWorkspaceFile::path).sortedBy(LocalWorkspaceFile::path)
}

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
    includeOtherModes: Boolean = false,
    preSortedByUpdatedAt: Boolean = false,
): LocalDrawerSessionSections {
    val normalizedQuery = query.trim()
    val filtered = sessions.asSequence()
        .filter { !it.blank && (includeOtherModes || it.usageMode == usageMode) }
        .filter { session ->
            normalizedQuery.isEmpty() ||
                (sessionTitleOverrides[session.id] ?: session.title)
                    .contains(normalizedQuery, ignoreCase = true)
        }
    // Sorting does not depend on typed search text. Callers with a cached ordering
    // avoid sorting all sessions again on every keystroke.
    val visible = if (preSortedByUpdatedAt) filtered.toList()
        else filtered.sortedByDescending(LocalSessionSummary::updatedAt).toList()
    return LocalDrawerSessionSections(
        pinned = visible.filter { it.id in pinnedSessionIds },
        recent = visible.filterNot { it.id in pinnedSessionIds },
    )
}

@Composable
internal fun DrawerQuickActions(
    usageMode: LocalUsageMode,
    onOpenGroupChat: () -> Unit,
    onOpenPersonaGallery: () -> Unit,
    onOpenDiary: () -> Unit,
    onTasks: () -> Unit,
    onWorkspaceFiles: () -> Unit,
    onTools: () -> Unit,
    onMiniMaxQuota: (() -> Unit)? = null,
    onUsage: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
            onUsage?.let { openUsage ->
                DrawerQuickAction(icon = SyEntryIcons.Usage,
                    title = stringResource(R.string.usage_calculation_title), onClick = openUsage)
            }
            if (usageMode == LocalUsageMode.CHAT) {
                DrawerQuickAction(
                    icon = SyEntryIcons.Gallery,
                    title = stringResource(R.string.persona_gallery_title),
                    onClick = onOpenPersonaGallery,
                )
                DrawerQuickAction(
                    icon = SyEntryIcons.GroupChat,
                    title = stringResource(R.string.local_group_chat_title),
                    onClick = onOpenGroupChat,
                )
                DrawerQuickAction(
                    icon = SyEntryIcons.Tasks,
                    title = stringResource(R.string.tasks_chat_title),
                    onClick = onTasks,
                )
                DrawerQuickAction(
                    icon = SyEntryIcons.Diary,
                    title = stringResource(R.string.chat_diary_title),
                    onClick = onOpenDiary,
                )
            } else {
                DrawerQuickAction(
                    icon = SyEntryIcons.WorkspaceFiles,
                    title = stringResource(R.string.chatlist_workspace_files),
                    onClick = onWorkspaceFiles,
                )
                DrawerQuickAction(
                    icon = SyEntryIcons.Tasks,
                    title = stringResource(R.string.tasks_title),
                    onClick = onTasks,
                )
                DrawerQuickAction(
                    icon = SyEntryIcons.Tools,
                    title = stringResource(R.string.tools_title),
                    onClick = onTools,
                )
            }
        onMiniMaxQuota?.let { openQuota ->
            DrawerQuickAction(
                icon = SyEntryIcons.Quota,
                title = stringResource(R.string.minimax_quota_title),
                onClick = openQuota,
            )
        }
    }
}

@Composable
internal fun DrawerQuickAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val sy = LocalSyPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val feedbackColor by animateColorAsState(
        targetValue = when {
            pressed -> sy.peach.copy(alpha = 0.17f)
            hovered -> sy.l3
            else -> Color.Transparent
        },
        animationSpec = tween(
                            com.shenyan.designsystem.foundation.SyMotion.duration(
                                com.shenyan.designsystem.foundation.SyMotion.interactionColorMs,
                                com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
                                com.shenyan.designsystem.foundation.LocalSyMotionTempo.current,
                            ), easing = com.shenyan.designsystem.foundation.SyMotion.ease,
                        ),
        label = "drawerQuickActionFeedback",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SyMetric.touch)
            .clip(DsShapes.row)
            .background(feedbackColor)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = SyMetric.gapMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        SyIconBox(icon = icon)
        Text(
            title,
            style = DsType.drawerItem.withReadingWeight(),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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
            .padding(start = SyMetric.gapMd, top = SyMetric.gapLg, bottom = SyMetric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = DsType.drawerSection.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            modifier = Modifier.weight(1f),
        )
        count?.let {
            Text(
                it.toString(),
                style = DsType.navigationSupporting.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
        }
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
    onSelectMultiple: () -> Unit,
    onRename: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit,
    searchModeLabel: String? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().clip(DsShapes.row)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SyMetric.touch)
                .clip(DsShapes.row)
                .background(
                    animateColorAsState(
                        targetValue = when {
                            selected -> colors.selected.copy(alpha = .32f)
                            current -> colors.selected.copy(alpha = .18f)
                            else -> Color.Transparent
                        },
                        animationSpec = tween(
                            com.shenyan.designsystem.foundation.SyMotion.duration(
                                com.shenyan.designsystem.foundation.SyMotion.interactionColorMs,
                                com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
                                com.shenyan.designsystem.foundation.LocalSyMotionTempo.current,
                            ), easing = com.shenyan.designsystem.foundation.SyMotion.ease,
                        ),
                        label = "sessionRowBackground",
                    ).value,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionOpen) {
                SyCheckboxControl(checked = selected, onCheckedChange = { onClick() })
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                ) {
                    Text(
                        displayTitle,
                        style = DsType.drawerSession.copy(fontWeight = if (current) DsType.drawerItem.fontWeight else DsType.drawerSession.fontWeight).withReadingWeight(),
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinned) {
                        Icon(
                            FeatherIcons.Pin,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                searchModeLabel?.let { mode ->
                    Text(
                        mode,
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        SyPopupMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                SyMenuItem(
                    text = stringResource(R.string.common_rename),
                    icon = FeatherIcons.Edit3,
                    onClick = onRename,
                ),
                SyMenuItem(
                    text = stringResource(R.string.local_drawer_multi_select),
                    icon = FeatherIcons.List,
                    onClick = onSelectMultiple,
                ),
                SyMenuItem(
                    text = stringResource(if (pinned) R.string.local_unpin_session else R.string.advanced_pin),
                    icon = FeatherIcons.Pin,
                    onClick = onTogglePinned,
                ),
                SyMenuItem(
                    text = stringResource(R.string.local_delete_session),
                    icon = FeatherIcons.Trash2,
                    danger = true,
                    onClick = onDelete,
                ),
            ),
        )
    }
}
