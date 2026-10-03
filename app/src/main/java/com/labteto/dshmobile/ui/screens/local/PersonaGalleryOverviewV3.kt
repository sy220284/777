package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val PERSONA_GALLERY_V3_PREFS = "persona_gallery_ui"
private const val PERSONA_GALLERY_ORDER = "persona_gallery_order_ids"
private const val PERSONA_GALLERY_PINNED = "pinned_persona_ids"
private const val PERSONA_ORDER_SEPARATOR = "\n"

internal fun reconcilePersonaGalleryOrder(
    currentIds: List<String>,
    savedIds: List<String>,
): List<String> {
    val available = currentIds.toHashSet()
    val kept = savedIds.filter { it in available }.distinct()
    return kept + currentIds.filterNot { it in kept }
}

internal fun movePersonaGalleryEntry(
    order: List<String>,
    id: String,
    direction: Int,
    movableIds: Set<String>,
): List<String> {
    if (direction == 0 || id !in movableIds) return order
    val visible = order.filter { it in movableIds }
    val sourceIndex = visible.indexOf(id)
    if (sourceIndex < 0) return order
    val targetIndex = (sourceIndex + direction.coerceIn(-1, 1)).coerceIn(0, visible.lastIndex)
    if (targetIndex == sourceIndex) return order
    val targetId = visible[targetIndex]
    val mutable = order.toMutableList()
    val sourceGlobal = mutable.indexOf(id)
    val targetGlobal = mutable.indexOf(targetId)
    if (sourceGlobal < 0 || targetGlobal < 0) return order
    mutable[sourceGlobal] = targetId
    mutable[targetGlobal] = id
    return mutable
}

@Composable
internal fun PersonaGalleryOverviewV3(
    entries: List<PersonaGalleryEntry>,
    presets: List<PersonaPreset>,
    hiddenPresetIds: Set<String>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
    currentHasUnsavedChanges: Boolean,
    canSave: Boolean,
    busy: Boolean,
    errorMessage: String?,
    onSelectEntry: (String) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onInstallPreset: (String) -> Unit,
    onRequestHidePreset: (String) -> Unit,
    onSyncCurrent: () -> Unit,
    onDeleteSelected: suspend (Set<String>) -> Result<Unit>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PERSONA_GALLERY_V3_PREFS, Context.MODE_PRIVATE)
    }
    val currentIds = remember(entries) { entries.map(PersonaGalleryEntry::id) }
    var orderIds by remember(prefs) {
        mutableStateOf(
            prefs.getString(PERSONA_GALLERY_ORDER, "")
                .orEmpty()
                .split(PERSONA_ORDER_SEPARATOR)
                .filter(String::isNotBlank),
        )
    }
    var pinnedIds by remember(prefs) {
        mutableStateOf(
            prefs.getStringSet(PERSONA_GALLERY_PINNED, emptySet())
                .orEmpty()
                .toSet(),
        )
    }
    var query by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    fun persistOrder(next: List<String>) {
        orderIds = next
        prefs.edit().putString(PERSONA_GALLERY_ORDER, next.joinToString(PERSONA_ORDER_SEPARATOR)).apply()
    }

    fun persistPinned(next: Set<String>) {
        pinnedIds = next
        prefs.edit().putStringSet(PERSONA_GALLERY_PINNED, next).apply()
    }

    LaunchedEffect(currentIds) {
        val reconciled = reconcilePersonaGalleryOrder(currentIds, orderIds)
        if (reconciled != orderIds) persistOrder(reconciled)
        val validPinned = pinnedIds.intersect(currentIds.toSet())
        if (validPinned != pinnedIds) persistPinned(validPinned)
        selectedIds.removeAll { it !in currentIds }
        if (entries.isEmpty()) managing = false
    }

    val entriesById = remember(entries) { entries.associateBy(PersonaGalleryEntry::id) }
    val orderedEntries = remember(entriesById, orderIds) {
        reconcilePersonaGalleryOrder(currentIds, orderIds).mapNotNull(entriesById::get)
    }
    val normalizedQuery = query.trim()
    val filteredEntries = remember(orderedEntries, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            orderedEntries
        } else {
            orderedEntries.filter { entry ->
                entry.persona.name.contains(normalizedQuery, ignoreCase = true) ||
                    entry.persona.identity.contains(normalizedQuery, ignoreCase = true) ||
                    entry.persona.franchise.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }
    val pinnedEntries = filteredEntries.filter { it.id in pinnedIds }
    val regularEntries = filteredEntries.filterNot { it.id in pinnedIds }

    if (confirmingDelete) {
        DsDialog(
            title = stringResource(R.string.persona_gallery_delete_selected_title),
            onDismiss = { if (!deleting) confirmingDelete = false },
        ) {
            Text(
                stringResource(R.string.persona_gallery_delete_selected_body, selectedIds.size),
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmingDelete = false },
                    variant = DsButtonVariant.Ghost,
                    enabled = !deleting,
                    modifier = Modifier.weight(1f),
                )
                DsButton(
                    text = stringResource(R.string.persona_gallery_delete_selected, selectedIds.size),
                    onClick = {
                        deleting = true
                        deleteError = null
                        val snapshot = selectedIds.toSet()
                        scope.launch {
                            onDeleteSelected(snapshot)
                                .onSuccess {
                                    selectedIds.clear()
                                    confirmingDelete = false
                                    managing = false
                                }
                                .onFailure {
                                    deleteError = it.message
                                        ?: context.getString(R.string.persona_gallery_delete_selected_failed)
                                }
                            deleting = false
                        }
                    },
                    variant = DsButtonVariant.Danger,
                    enabled = !deleting && selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (adding) {
        PersonaGalleryAddPanel(
            presets = presets.filter { preset ->
                preset.id !in hiddenPresetIds &&
                    entries.none { it.persona.presetId == preset.id }
            },
            busy = busy,
            onBack = { adding = false },
            onCreate = onCreate,
            onImport = onImport,
            onInstallPreset = onInstallPreset,
            onRequestHidePreset = onRequestHidePreset,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (currentGalleryId == null || currentHasUnsavedChanges) {
            Surface(
                shape = DsShapes.block,
                color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    LocalPersonaHeaderAvatar(currentPersona.name, "")
                    Column(Modifier.weight(1f)) {
                        Text(
                            currentPersona.name,
                            style = DsType.std14Strong.withReadingWeight(),
                            color = DsTheme.colors.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                if (currentGalleryId == null) R.string.persona_gallery_current_unsaved
                                else R.string.persona_gallery_current_changed,
                            ),
                            style = DsType.caption11.withReadingWeight(),
                            color = DsTheme.colors.labelTertiary,
                            maxLines = 1,
                        )
                    }
                    DsButton(
                        text = stringResource(R.string.persona_gallery_save_current_short),
                        onClick = onSyncCurrent,
                        enabled = canSave && !busy,
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (managing) {
                    stringResource(R.string.persona_gallery_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.persona_gallery_my_characters)
                },
                style = DsType.base16Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_add),
                onClick = { adding = true },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
                enabled = !busy && !managing,
            )
            DsButton(
                text = stringResource(
                    if (managing) R.string.persona_gallery_manage_done else R.string.persona_gallery_manage,
                ),
                onClick = {
                    managing = !managing
                    if (!managing) selectedIds.clear()
                },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
                enabled = entries.isNotEmpty() && !busy,
            )
        }

        if (entries.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.persona_gallery_search)) },
                leadingIcon = {
                    Icon(
                        FeatherIcons.Search,
                        contentDescription = null,
                        tint = DsTheme.colors.labelTertiary,
                    )
                },
                singleLine = true,
                shape = DsShapes.block,
            )
        }

        if (managing) {
            Text(
                stringResource(R.string.persona_gallery_manage_hint),
                style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelTertiary,
            )
        }

        errorMessage?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.error,
            )
        }
        deleteError?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.error,
            )
        }

        when {
            entries.isEmpty() -> {
                PersonaGalleryEmptyState(
                    title = stringResource(R.string.persona_gallery_empty_title),
                    body = stringResource(R.string.persona_gallery_empty_body),
                    primary = stringResource(R.string.local_persona_picker_new),
                    secondary = stringResource(R.string.persona_gallery_import_file),
                    onPrimary = onCreate,
                    onSecondary = onImport,
                    modifier = Modifier.weight(1f),
                )
            }
            filteredEntries.isEmpty() -> {
                PersonaGalleryEmptyState(
                    title = stringResource(R.string.persona_gallery_search_empty_title),
                    body = stringResource(R.string.persona_gallery_search_empty_body),
                    primary = stringResource(R.string.persona_gallery_clear_search),
                    secondary = null,
                    onPrimary = { query = "" },
                    onSecondary = {},
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    if (pinnedEntries.isNotEmpty()) {
                        item(key = "pinned-heading") {
                            PersonaGallerySectionLabel(stringResource(R.string.persona_gallery_pinned))
                        }
                        items(pinnedEntries, key = PersonaGalleryEntry::id) { entry ->
                            CompactPersonaRow(
                                entry = entry,
                                pinned = true,
                                managing = managing,
                                selected = entry.id in selectedIds,
                                dragEnabled = managing && normalizedQuery.isBlank(),
                                onClick = {
                                    if (managing) {
                                        if (entry.id in selectedIds) selectedIds.remove(entry.id)
                                        else selectedIds.add(entry.id)
                                    } else {
                                        onSelectEntry(entry.id)
                                    }
                                },
                                onLongClick = {
                                    if (!managing) managing = true
                                    if (entry.id !in selectedIds) selectedIds.add(entry.id)
                                },
                                onTogglePin = { persistPinned(pinnedIds - entry.id) },
                                onMove = { direction ->
                                    persistOrder(
                                        movePersonaGalleryEntry(
                                            orderIds,
                                            entry.id,
                                            direction,
                                            pinnedEntries.mapTo(linkedSetOf(), PersonaGalleryEntry::id),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    if (regularEntries.isNotEmpty()) {
                        if (pinnedEntries.isNotEmpty()) {
                            item(key = "regular-heading") {
                                PersonaGallerySectionLabel(stringResource(R.string.persona_gallery_others))
                            }
                        }
                        items(regularEntries, key = PersonaGalleryEntry::id) { entry ->
                            CompactPersonaRow(
                                entry = entry,
                                pinned = false,
                                managing = managing,
                                selected = entry.id in selectedIds,
                                dragEnabled = managing && normalizedQuery.isBlank(),
                                onClick = {
                                    if (managing) {
                                        if (entry.id in selectedIds) selectedIds.remove(entry.id)
                                        else selectedIds.add(entry.id)
                                    } else {
                                        onSelectEntry(entry.id)
                                    }
                                },
                                onLongClick = {
                                    if (!managing) managing = true
                                    if (entry.id !in selectedIds) selectedIds.add(entry.id)
                                },
                                onTogglePin = { persistPinned(pinnedIds + entry.id) },
                                onMove = { direction ->
                                    persistOrder(
                                        movePersonaGalleryEntry(
                                            orderIds,
                                            entry.id,
                                            direction,
                                            regularEntries.mapTo(linkedSetOf(), PersonaGalleryEntry::id),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (managing && selectedIds.isNotEmpty()) {
            DsButton(
                text = stringResource(R.string.persona_gallery_delete_selected, selectedIds.size),
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Danger,
                enabled = !busy && !deleting,
            )
        }
    }
}
