package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


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
import androidx.compose.material3.Icon
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
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
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

internal data class PersonaPresetAddUiState(
    val visible: Boolean,
    val installed: Boolean,
)

internal fun personaPresetAddUiState(
    presetId: String,
    hiddenPresetIds: Set<String>,
    installedPresetIds: Set<String>,
): PersonaPresetAddUiState = PersonaPresetAddUiState(
    visible = presetId !in hiddenPresetIds,
    installed = presetId in installedPresetIds,
)

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
    animateCards: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PERSONA_GALLERY_V3_PREFS, Context.MODE_PRIVATE)
    }
    val currentIds = remember(entries) { entries.map(PersonaGalleryEntry::id) }
    val installedPresetIds = remember(entries) {
        entries.mapNotNull { it.persona.presetId }.toSet()
    }
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
    val deleteSelectedFailed = stringResource(R.string.persona_gallery_delete_selected_failed)

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
                    entry.persona.coreIdentity.contains(normalizedQuery, ignoreCase = true) ||
                    entry.persona.facts.any { fact ->
                        fact.content.contains(normalizedQuery, ignoreCase = true)
                    } ||
                    entry.persona.franchise.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }
    val pinnedEntries = filteredEntries.filter { it.id in pinnedIds }
    val regularEntries = filteredEntries.filterNot { it.id in pinnedIds }

    if (confirmingDelete) {
        SyDialog(
            title = stringResource(R.string.persona_gallery_delete_selected_title),
            onDismiss = { if (!deleting) confirmingDelete = false },
        ) {
            Text(
                stringResource(R.string.persona_gallery_delete_selected_body, selectedIds.size),
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmingDelete = false },
                    variant = SyButtonVariant.Ghost,
                    enabled = !deleting,
                    modifier = Modifier.weight(1f),
                )
                SyButton(
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
                                    deleteError = it.message ?: deleteSelectedFailed
                                }
                            deleting = false
                        }
                    },
                    variant = SyButtonVariant.Danger,
                    enabled = !deleting && selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (adding) {
        PersonaGalleryAddPanel(
            presets = presets.filter { preset ->
                personaPresetAddUiState(
                    presetId = preset.id,
                    hiddenPresetIds = hiddenPresetIds,
                    installedPresetIds = installedPresetIds,
                ).visible
            },
            installedPresetIds = installedPresetIds,
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
        modifier = modifier.fillMaxSize().padding(horizontal = SyMetric.gapLg),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        if (currentGalleryId == null || currentHasUnsavedChanges) {
            SyMaterialSurface(
                shape = DsShapes.block,
                tier = SyMaterialTier.L3_CARD,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                ) {
                    LocalPersonaHeaderAvatar(currentPersona.name, "")
                    Column(Modifier.weight(1f)) {
                        Text(
                            currentPersona.name,
                            style = DsType.std14Strong.withReadingWeight().copy(fontFamily = DsType.contentFont),
                            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                if (currentGalleryId == null) R.string.persona_gallery_current_unsaved
                                else R.string.persona_gallery_current_changed,
                            ),
                            style = DsType.caption11.withReadingWeight(),
                            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                            maxLines = 1,
                        )
                    }
                    SyButton(
                        text = stringResource(R.string.persona_gallery_save_current_short),
                        onClick = onSyncCurrent,
                        enabled = canSave && !busy,
                        size = SyButtonSize.Small,
                        variant = SyButtonVariant.Ghost,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (managing) {
                    stringResource(R.string.persona_gallery_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.persona_gallery_my_characters)
                },
                style = DsType.base16Strong.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                modifier = Modifier.weight(1f),
            )
            SyButton(
                text = stringResource(R.string.persona_gallery_add),
                onClick = { adding = true },
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
                enabled = !busy && !managing,
            )
            SyButton(
                text = stringResource(
                    if (managing) R.string.persona_gallery_manage_done else R.string.persona_gallery_manage,
                ),
                onClick = {
                    managing = !managing
                    if (!managing) selectedIds.clear()
                },
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
                enabled = entries.isNotEmpty() && !busy,
            )
        }

        if (entries.isNotEmpty()) {
            SyTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.persona_gallery_search)) },
                leadingIcon = {
                    Icon(
                        FeatherIcons.Search,
                        contentDescription = null,
                        tint = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
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
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
            )
        }

        errorMessage?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
            )
        }
        deleteError?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
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
                if (!managing) {
                    FluidPersonaGalleryGrid(pinnedEntries + regularEntries, onSelectEntry,
                        onLongClick = { id -> managing = true; if (id !in selectedIds) selectedIds.add(id) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        animateOnEntrance = animateCards)
                } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
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
                                    val moved = movePersonaGalleryEntry(
                                        orderIds, entry.id, direction,
                                        pinnedEntries.mapTo(linkedSetOf(), PersonaGalleryEntry::id),
                                    )
                                    if (moved == orderIds) false else {
                                        persistOrder(moved)
                                        true
                                    }
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
                                    val moved = movePersonaGalleryEntry(
                                        orderIds, entry.id, direction,
                                        regularEntries.mapTo(linkedSetOf(), PersonaGalleryEntry::id),
                                    )
                                    if (moved == orderIds) false else {
                                        persistOrder(moved)
                                        true
                                    }
                                },
                            )
                        }
                    }
                }
                }
            }
        }

        if (managing && selectedIds.isNotEmpty()) {
            SyButton(
                text = stringResource(R.string.persona_gallery_delete_selected, selectedIds.size),
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Danger,
                enabled = !busy && !deleting,
            )
        }
    }
}
