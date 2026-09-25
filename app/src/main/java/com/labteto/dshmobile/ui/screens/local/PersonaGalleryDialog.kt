package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.galleryMessageArchiveKey
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PersonaGallerySavePromptDialog(
    persona: PersonaProfile,
    isUpdate: Boolean,
    canSave: Boolean,
    onSaveCurrent: suspend () -> Result<PersonaGalleryEntry>,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)

    DsDialog(
        title = stringResource(
            if (isUpdate) R.string.persona_gallery_unsaved_update_title
            else R.string.persona_gallery_new_character_title,
        ),
        onDismiss = onDismiss,
    ) {
        PersonaHero(
            persona = persona,
            subtitle = stringResource(
                if (isUpdate) R.string.persona_gallery_unsaved_update_subtitle
                else R.string.persona_gallery_new_character_subtitle,
            ),
        )
        Text(
            stringResource(
                if (isUpdate) R.string.persona_gallery_unsaved_update_hint
                else R.string.persona_gallery_new_character_hint,
            ),
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )
        DsButton(
            text = if (busy) {
                stringResource(R.string.persona_gallery_saving)
            } else {
                stringResource(
                    if (isUpdate) R.string.persona_gallery_save_update_enter
                    else R.string.persona_gallery_save_enter,
                )
            },
            onClick = {
                busy = true
                error = null
                scope.launch {
                    onSaveCurrent()
                        .onSuccess { onContinue() }
                        .onFailure { error = it.message ?: saveFailedText }
                    busy = false
                }
            },
            enabled = canSave && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            text = stringResource(R.string.persona_gallery_skip_save),
            onClick = onContinue,
            variant = DsButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        error?.let {
            Text(it, style = DsType.small13, color = DsTheme.colors.error)
        }
    }
}

@Composable
internal fun PersonaGalleryDialog(
    entries: List<PersonaGalleryEntry>,
    presets: List<PersonaPreset>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
    currentGalleryStoryId: String?,
    currentHasUnsavedChanges: Boolean,
    currentSessionId: String,
    canSave: Boolean,
    onSaveCurrent: suspend (String, String?, String?, Boolean) -> Result<PersonaGalleryEntry>,
    onEditNotes: suspend (String, String, String) -> Result<Unit>,
    onRenameStory: suspend (String, String, String) -> Result<Unit>,
    onInspect: suspend (String, String?) -> Result<PersonaInspectionResult>,
    onApplySuggestions: suspend (String, List<PersonaAppendSuggestion>) -> Result<PersonaGalleryEntry>,
    onDelete: suspend (String) -> Result<Unit>,
    onDeleteStory: suspend (String, String) -> Result<Unit>,
    onDeleteHistoryMessage: suspend (String, String, String) -> Result<Unit>,
    onExport: suspend (String, Boolean) -> Result<String>,
    onImport: suspend (String) -> Result<PersonaGalleryEntry>,
    onInstallPreset: suspend (String) -> Result<PersonaGalleryEntry>,
    onStart: (String, String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    var selectedStoryId by remember(selectedId) { mutableStateOf<String?>(null) }
    val selectedStory = selected?.stories?.firstOrNull { it.id == selectedStoryId }
    var search by remember { mutableStateOf("") }
    var notes by remember(selectedId, selectedStoryId, selectedStory?.notes) {
        mutableStateOf(selectedStory?.notes.orEmpty())
    }
    var storyTitle by remember(selectedId, selectedStoryId, selectedStory?.title) {
        mutableStateOf(selectedStory?.title.orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var deletingCharacter by remember { mutableStateOf(false) }
    var deletingStory by remember(selectedStoryId) { mutableStateOf(false) }
    var pendingEntryDeleteId by remember { mutableStateOf<String?>(null) }
    var visibleHistory by remember(selectedId, selectedStoryId) { mutableStateOf(8) }
    var showHistory by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var pendingHistoryDeleteKey by remember(selectedId, selectedStoryId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember(selectedId, selectedStoryId) { mutableStateOf<String?>(null) }
    var inspection by remember(selectedId, selectedStoryId) { mutableStateOf<PersonaInspectionResult?>(null) }
    var inspecting by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var showPersonaDetails by remember(selectedId) { mutableStateOf(false) }
    var editingStoryTitle by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var pendingExportPayload by remember { mutableStateOf<String?>(null) }
    val hasLocalStoryEdits = selectedStory?.let { story ->
        notes != story.notes || (editingStoryTitle && storyTitle.trim() != story.title)
    } == true
    val selectedSuggestionKeys = remember(selectedId, selectedStoryId) { mutableStateListOf<String>() }

    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)
    val inspectFailedText = stringResource(R.string.persona_gallery_inspect_failed)
    val appendFailedText = stringResource(R.string.persona_gallery_append_failed)
    val updateFailedText = stringResource(R.string.persona_gallery_update_failed)
    val deleteFailedText = stringResource(R.string.persona_gallery_delete_failed)
    val mergedNoticeText = stringResource(R.string.persona_gallery_merged_notice)
    val appendDoneText = stringResource(R.string.persona_gallery_append_done)
    val storySavedText = stringResource(R.string.persona_gallery_story_saved)
    val storyRenamedText = stringResource(R.string.persona_gallery_story_renamed)
    val mergeDoneText = stringResource(R.string.persona_gallery_merge_done)
    val archiveDeletedText = stringResource(R.string.persona_gallery_archive_deleted)
    val saveEditsBeforeSwitchText = stringResource(R.string.persona_gallery_save_edits_before_switch)
    val exportFailedText = stringResource(R.string.persona_gallery_export_failed)
    val importFailedText = stringResource(R.string.persona_gallery_import_failed)
    val presetInstallFailedText = stringResource(R.string.persona_gallery_preset_install_failed)
    val presetInstalledText = stringResource(R.string.persona_gallery_preset_installed)

    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingExportPayload
        pendingExportPayload = null
        if (uri != null && payload != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(payload)
                        } ?: error("无法写入目标文件")
                    }
                }.onFailure { error = it.message ?: exportFailedText }
            }
        }
    }

    fun importPayload(payload: String) {
        busy = true
        error = null
        scope.launch {
            onImport(payload)
                .onSuccess { imported ->
                    selectedId = imported.id
                    selectedStoryId = null
                }
                .onFailure { error = it.message ?: importFailedText }
            busy = false
        }
    }

    val importDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val payload = runCatching {
                    withContext(Dispatchers.IO) { readPersonaShareText(context, uri) }
                }.getOrElse {
                    error = it.message ?: importFailedText
                    return@launch
                }
                importPayload(payload)
            }
        }
    }

    LaunchedEffect(selectedId, selected?.stories, currentGalleryId, currentGalleryStoryId) {
        val entry = selected ?: return@LaunchedEffect
        if (entry.stories.none { it.id == selectedStoryId }) {
            selectedStoryId = if (currentGalleryId == entry.id) {
                currentGalleryStoryId?.takeIf { id -> entry.stories.any { it.id == id } }
                    ?: entry.stories.maxByOrNull(PersonaGalleryStory::updatedAt)?.id
            } else {
                entry.stories.maxByOrNull(PersonaGalleryStory::updatedAt)?.id
            }
        }
    }

    DsDialog(
        title = if (selected == null) stringResource(R.string.persona_gallery_title) else selected.persona.name,
        onDismiss = onDismiss,
    ) {
        if (selected == null) {
            GalleryOverviewHeader(entries.size)

            if (presets.isNotEmpty()) {
                Text(
                    stringResource(R.string.persona_gallery_presets_title),
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.persona_gallery_presets_hint),
                    style = DsType.caption11,
                    color = DsTheme.colors.labelTertiary,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    presets.forEach { preset ->
                        val installed = entries.any { entry -> entry.persona.presetId == preset.id }
                        PersonaPresetCard(
                            preset = preset,
                            installed = installed,
                            busy = busy,
                            onInstall = {
                                busy = true
                                error = null
                                notice = null
                                scope.launch {
                                    onInstallPreset(preset.id)
                                        .onSuccess { entry ->
                                            notice = presetInstalledText
                                            selectedId = entry.id
                                            selectedStoryId = null
                                        }
                                        .onFailure { error = it.message ?: presetInstallFailedText }
                                    busy = false
                                }
                            },
                        )
                    }
                }
            }

            DsButton(
                text = stringResource(R.string.persona_gallery_import_file),
                onClick = { importDocument.launch(arrayOf("application/json", "text/plain")) },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Text(
                stringResource(R.string.persona_gallery_long_press_delete_hint),
                style = DsType.caption11,
                color = DsTheme.colors.labelTertiary,
            )

            entries.firstOrNull { it.id == pendingEntryDeleteId }?.let { pending ->
                DeleteCharacterConfirm(
                    entry = pending,
                    busy = busy,
                    onCancel = { pendingEntryDeleteId = null },
                    onConfirm = {
                        busy = true
                        error = null
                        scope.launch {
                            onDelete(pending.id)
                                .onSuccess { pendingEntryDeleteId = null }
                                .onFailure { error = it.message ?: deleteFailedText }
                            busy = false
                        }
                    },
                )
            }

            if (currentGalleryId == null || currentHasUnsavedChanges) {
                DsButton(
                    text = if (currentGalleryId == null) {
                        stringResource(R.string.persona_gallery_sync_current, currentPersona.name)
                    } else {
                        stringResource(R.string.persona_gallery_update_current, currentPersona.name)
                    },
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onSaveCurrent("", currentGalleryId, currentGalleryStoryId, false)
                                .onSuccess {
                                    selectedId = it.id
                                    selectedStoryId = it.stories.maxByOrNull(PersonaGalleryStory::updatedAt)?.id
                                    notice = mergedNoticeText
                                }
                                .onFailure { error = it.message ?: saveFailedText }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSave && !busy,
                    variant = DsButtonVariant.Outline,
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DsTheme.colors.accent.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.persona_gallery_current_synced),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                        modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    )
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.persona_gallery_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val query = search.trim()
            val filtered = entries.filter { entry ->
                query.isBlank() ||
                    entry.persona.name.contains(query, ignoreCase = true) ||
                    entry.persona.identity.contains(query, ignoreCase = true) ||
                    entry.persona.worldSetting.contains(query, ignoreCase = true) ||
                    entry.stories.any { story ->
                        story.title.contains(query, ignoreCase = true) ||
                            story.notes.contains(query, ignoreCase = true)
                    }
            }
            if (filtered.isEmpty()) {
                DsCard {
                    Text(
                        if (entries.isEmpty()) stringResource(R.string.persona_gallery_empty)
                        else stringResource(R.string.persona_gallery_no_match),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    filtered.forEach { entry ->
                        GalleryPersonaCard(
                            entry = entry,
                            unsaved = entry.id == currentGalleryId && currentHasUnsavedChanges,
                            onClick = {
                                selectedId = entry.id
                                selectedStoryId = null
                                error = null
                                notice = null
                            },
                            onLongClick = {
                                pendingEntryDeleteId = entry.id
                                error = null
                            },
                        )
                    }
                }
            }
        } else {
            val totalDialogue = selected.totalDialogueCount()
            val relationSummary = stringResource(
                R.string.persona_gallery_character_summary,
                selected.stories.size,
                totalDialogue,
            )
            PersonaHero(persona = selected.persona, subtitle = relationSummary)

            DsButton(
                text = stringResource(R.string.persona_gallery_export_file),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        onExport(selected.id, false)
                            .onSuccess { payload ->
                                pendingExportPayload = payload
                                exportDocument.launch(personaExportFileName(selected.persona.name))
                            }
                            .onFailure { error = it.message ?: exportFailedText }
                        busy = false
                    }
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

            notice?.let {
                Surface(
                    color = DsTheme.colors.accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = DsType.small13,
                        color = DsTheme.colors.labelPrimary,
                        modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    )
                }
            }

            DsButton(
                text = stringResource(
                    if (showPersonaDetails) R.string.persona_gallery_hide_fixed_persona
                    else R.string.persona_gallery_show_fixed_persona,
                ),
                onClick = { showPersonaDetails = !showPersonaDetails },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showPersonaDetails) {
                PersonaDetails(selected.persona)
            }

            Text(
                stringResource(R.string.persona_gallery_storylines_title),
                style = DsType.std14,
                color = DsTheme.colors.labelPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected.stories.isEmpty()) {
                DsCard {
                    Text(
                        stringResource(R.string.persona_gallery_storylines_empty),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    selected.stories.sortedByDescending(PersonaGalleryStory::updatedAt).forEach { story ->
                        GalleryStoryCard(
                            story = story,
                            selected = story.id == selectedStoryId,
                            unsaved = selected.id == currentGalleryId &&
                                story.id == currentGalleryStoryId &&
                                currentHasUnsavedChanges,
                            onClick = {
                                if (hasLocalStoryEdits && story.id != selectedStoryId) {
                                    error = saveEditsBeforeSwitchText
                                } else {
                                    selectedStoryId = story.id
                                    error = null
                                    notice = null
                                }
                            },
                        )
                    }
                }
            }

            if (
                currentGalleryId == selected.id &&
                currentGalleryStoryId == null &&
                currentHasUnsavedChanges &&
                canSave
            ) {
                DsButton(
                    text = stringResource(R.string.persona_gallery_save_current_new_story),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onSaveCurrent("", selected.id, null, true)
                                .onSuccess {
                                    selectedStoryId = it.stories.maxByOrNull(PersonaGalleryStory::updatedAt)?.id
                                    notice = mergeDoneText
                                }
                                .onFailure { error = it.message ?: saveFailedText }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }

            selectedStory?.let { story ->
                DsButton(
                    text = stringResource(R.string.persona_gallery_continue_story),
                    onClick = { onStart(selected.id, story.id, false) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSave && !busy && !hasLocalStoryEdits,
                )
            }
            DsButton(
                text = stringResource(R.string.persona_gallery_start_fresh_story),
                onClick = { onStart(selected.id, null, true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy && !hasLocalStoryEdits,
                variant = DsButtonVariant.Outline,
            )

            selectedStory?.let { story ->
                if (editingStoryTitle) {
                    OutlinedTextField(
                        value = storyTitle,
                        onValueChange = { storyTitle = it },
                        label = { Text(stringResource(R.string.persona_gallery_story_title_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DsButton(
                            text = stringResource(R.string.persona_gallery_cancel),
                            onClick = {
                                storyTitle = story.title
                                editingStoryTitle = false
                            },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                        DsButton(
                            text = stringResource(R.string.persona_gallery_save_story_title),
                            onClick = {
                                busy = true
                                scope.launch {
                                    onRenameStory(selected.id, story.id, storyTitle)
                                        .onSuccess {
                                            notice = storyRenamedText
                                            editingStoryTitle = false
                                        }
                                        .onFailure { error = it.message ?: updateFailedText }
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy &&
                                storyTitle.trim().isNotBlank() &&
                                storyTitle.trim() != story.title,
                        )
                    }
                } else {
                    DsButton(
                        text = stringResource(R.string.persona_gallery_rename_story),
                        onClick = { editingStoryTitle = true },
                        variant = DsButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }

                StoryDetailSection(
                    entry = selected,
                    story = story,
                    notes = notes,
                    onNotesChange = { notes = it },
                    showHistory = showHistory,
                    onToggleHistory = { showHistory = !showHistory },
                    visibleHistory = visibleHistory,
                    onMoreHistory = { visibleHistory += 12 },
                    pendingHistoryDeleteKey = pendingHistoryDeleteKey,
                    onPendingHistoryDelete = { pendingHistoryDeleteKey = it },
                    busy = busy,
                    onDeleteHistoryMessage = { messageKey ->
                        busy = true
                        error = null
                        scope.launch {
                            onDeleteHistoryMessage(selected.id, story.id, messageKey)
                                .onSuccess {
                                    pendingHistoryDeleteKey = null
                                    notice = archiveDeletedText
                                }
                                .onFailure { error = deleteFailedText }
                            busy = false
                        }
                    },
                )

                if (notes != story.notes) {
                    DsButton(
                        text = stringResource(R.string.persona_gallery_save_story),
                        onClick = {
                            busy = true
                            scope.launch {
                                onEditNotes(selected.id, story.id, notes)
                                    .onSuccess { notice = storySavedText }
                                    .onFailure { error = it.message ?: saveFailedText }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }

                DsButton(
                    text = if (inspecting) {
                        stringResource(R.string.persona_gallery_inspecting)
                    } else {
                        stringResource(R.string.persona_gallery_inspect)
                    },
                    onClick = {
                        inspecting = true
                        error = null
                        notice = null
                        selectedSuggestionKeys.clear()
                        scope.launch {
                            onInspect(selected.id, story.id)
                                .onSuccess { inspection = it }
                                .onFailure { error = it.message ?: inspectFailedText }
                            inspecting = false
                        }
                    },
                    variant = DsButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !inspecting,
                )

                inspection?.let { result ->
                    PersonaInspectionPanel(
                        result = result,
                        selectedKeys = selectedSuggestionKeys,
                        onToggle = { suggestion ->
                            val key = suggestionKey(suggestion)
                            if (key in selectedSuggestionKeys) selectedSuggestionKeys.remove(key)
                            else selectedSuggestionKeys.add(key)
                        },
                    )
                    if (result.suggestions.isNotEmpty()) {
                        val chosen = result.suggestions.filter { suggestionKey(it) in selectedSuggestionKeys }
                        DsButton(
                            text = stringResource(R.string.persona_gallery_append_selected, chosen.size),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    onApplySuggestions(selected.id, chosen)
                                        .onSuccess {
                                            inspection = null
                                            selectedSuggestionKeys.clear()
                                            notice = appendDoneText
                                        }
                                        .onFailure { error = it.message ?: appendFailedText }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = chosen.isNotEmpty() && !busy,
                        )
                    }
                }

                if (
                    canSave &&
                    currentGalleryId == selected.id &&
                    currentGalleryStoryId == story.id
                ) {
                    DsButton(
                        text = stringResource(R.string.persona_gallery_merge_current),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                onSaveCurrent(notes, selected.id, story.id, false)
                                    .onSuccess { notice = mergeDoneText }
                                    .onFailure { error = it.message ?: updateFailedText }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }

                if (deletingStory) {
                    DsCard {
                        Text(
                            stringResource(R.string.persona_gallery_delete_story_confirm, story.title),
                            style = DsType.small13,
                            color = DsTheme.colors.error,
                        )
                        Text(
                            stringResource(R.string.persona_gallery_delete_story_hint),
                            style = DsType.caption11,
                            color = DsTheme.colors.labelTertiary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DsButton(
                                text = stringResource(R.string.persona_gallery_cancel),
                                onClick = { deletingStory = false },
                                variant = DsButtonVariant.Ghost,
                                modifier = Modifier.weight(1f),
                            )
                            DsButton(
                                text = stringResource(R.string.persona_gallery_confirm_delete),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        onDeleteStory(selected.id, story.id)
                                            .onSuccess {
                                                selectedStoryId = null
                                                deletingStory = false
                                            }
                                            .onFailure { error = it.message ?: deleteFailedText }
                                        busy = false
                                    }
                                },
                                variant = DsButtonVariant.Danger,
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            )
                        }
                    }
                } else {
                    DsButton(
                        text = stringResource(R.string.persona_gallery_delete_story),
                        onClick = { deletingStory = true },
                        variant = DsButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
            }

            if (deletingCharacter) {
                DeleteCharacterConfirm(
                    entry = selected,
                    busy = busy,
                    onCancel = { deletingCharacter = false },
                    onConfirm = {
                        busy = true
                        scope.launch {
                            onDelete(selected.id)
                                .onSuccess {
                                    selectedId = null
                                    selectedStoryId = null
                                    deletingCharacter = false
                                }
                                .onFailure { error = it.message ?: deleteFailedText }
                            busy = false
                        }
                    },
                )
            } else {
                DsButton(
                    text = stringResource(R.string.persona_gallery_delete),
                    onClick = { deletingCharacter = true },
                    variant = DsButtonVariant.Ghost,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            DsButton(
                text = stringResource(R.string.persona_gallery_back),
                onClick = {
                    if (hasLocalStoryEdits) {
                        error = saveEditsBeforeSwitchText
                    } else {
                        selectedId = null
                        selectedStoryId = null
                        deletingCharacter = false
                        error = null
                        notice = null
                    }
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let {
            Text(
                it,
                style = DsType.small13,
                color = DsTheme.colors.error,
                modifier = Modifier.padding(top = DsSpacing.small),
            )
        }
    }

}

private fun personaExportFileName(name: String): String {
    val safe = name.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .take(48)
        .ifBlank { "persona" }
    return "$safe.persona.json"
}

private fun readPersonaShareText(context: android.content.Context, uri: android.net.Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取人物文件")
    return input.bufferedReader().use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(4_096)
        while (result.length <= 64_000) {
            val count = reader.read(buffer)
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        require(result.length <= 64_000) { "人物文件过大" }
        result.toString()
    }
}

@Composable
private fun DeleteCharacterConfirm(
    entry: PersonaGalleryEntry,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    DsCard {
        Text(
            stringResource(
                R.string.persona_gallery_delete_saved_confirm,
                entry.persona.name,
                entry.totalDialogueCount(),
            ),
            style = DsType.small13,
            color = DsTheme.colors.error,
        )
        Text(
            stringResource(R.string.persona_gallery_delete_saved_hint),
            style = DsType.caption11,
            color = DsTheme.colors.labelTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsButton(
                text = stringResource(R.string.persona_gallery_cancel),
                onClick = onCancel,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_confirm_delete),
                onClick = onConfirm,
                variant = DsButtonVariant.Danger,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun StoryDetailSection(
    entry: PersonaGalleryEntry,
    story: PersonaGalleryStory,
    notes: String,
    onNotesChange: (String) -> Unit,
    showHistory: Boolean,
    onToggleHistory: () -> Unit,
    visibleHistory: Int,
    onMoreHistory: () -> Unit,
    pendingHistoryDeleteKey: String?,
    onPendingHistoryDelete: (String?) -> Unit,
    busy: Boolean,
    onDeleteHistoryMessage: (String) -> Unit,
) {
    if (story.chatState.updatedAt > 0L) {
        DsCard {
            Text(
                stringResource(R.string.persona_gallery_story_relation),
                style = DsType.std14,
                color = DsTheme.colors.labelPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.persona_gallery_current_relation, story.chatState.relationshipState),
                style = DsType.small13,
                color = DsTheme.colors.labelSecondary,
            )
            story.chatState.dynamics.sharedMoments.takeLast(4).takeIf { it.isNotEmpty() }?.let { moments ->
                Text(
                    stringResource(R.string.persona_gallery_shared_moments, moments.joinToString("；")),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
            story.chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let { threads ->
                Text(
                    stringResource(R.string.persona_gallery_unresolved, threads.joinToString("；")),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
    }

    DsButton(
        text = if (showHistory) {
            stringResource(R.string.persona_gallery_history_hide)
        } else {
            stringResource(R.string.persona_gallery_history_show)
        },
        onClick = onToggleHistory,
        variant = DsButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth(),
    )
    if (showHistory) {
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = DsTheme.colors.labelSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_archived_dialogue),
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                )
            }
            Text(
                stringResource(R.string.persona_gallery_history_long_press_hint),
                style = DsType.caption11,
                color = DsTheme.colors.labelTertiary,
            )
            val history = story.history.filter { it.role == "user" || it.role == "assistant" }
            history.takeLast(visibleHistory).forEach { line ->
                val lineKey = galleryMessageArchiveKey(line)
                ArchivedDialogueRow(
                    text = "${if (line.role == "user") stringResource(R.string.persona_gallery_user) else entry.persona.name}：${line.content}",
                    onLongClick = { onPendingHistoryDelete(lineKey) },
                )
            }
            val pendingHistory = pendingHistoryDeleteKey?.let { key ->
                history.firstOrNull { galleryMessageArchiveKey(it) == key }
            }
            pendingHistory?.let { line ->
                DsCard {
                    Text(
                        stringResource(R.string.persona_gallery_delete_dialogue_confirm),
                        style = DsType.small13,
                        color = DsTheme.colors.error,
                    )
                    Text(
                        "${if (line.role == "user") stringResource(R.string.persona_gallery_user) else entry.persona.name}：${line.content}",
                        style = DsType.caption11,
                        color = DsTheme.colors.labelSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.persona_gallery_delete_dialogue_hint),
                        style = DsType.caption11,
                        color = DsTheme.colors.labelTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DsButton(
                            text = stringResource(R.string.persona_gallery_cancel),
                            onClick = { onPendingHistoryDelete(null) },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                        DsButton(
                            text = stringResource(R.string.persona_gallery_confirm_delete),
                            onClick = { onDeleteHistoryMessage(galleryMessageArchiveKey(line)) },
                            variant = DsButtonVariant.Danger,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                    }
                }
            }
            if (history.size > visibleHistory) {
                DsButton(
                    text = stringResource(R.string.persona_gallery_older_dialogue),
                    onClick = onMoreHistory,
                    modifier = Modifier.fillMaxWidth(),
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }

    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text(stringResource(R.string.persona_gallery_story_notes)) },
        placeholder = { Text(stringResource(R.string.persona_gallery_story_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 8,
        enabled = !busy,
    )
}

@Composable
private fun GalleryOverviewHeader(count: Int) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = DsTheme.colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = DsTheme.colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.persona_gallery_master),
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.persona_gallery_count, count),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun PersonaPresetCard(
    preset: PersonaPreset,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(preset.persona.name)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        preset.persona.name,
                        style = DsType.std14,
                        color = DsTheme.colors.labelPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GalleryPill(preset.franchise)
                }
                Text(
                    preset.summary,
                    style = DsType.caption11,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DsButton(
            text = stringResource(
                if (installed) R.string.persona_gallery_preset_added
                else R.string.persona_gallery_preset_add
            ),
            onClick = onInstall,
            modifier = Modifier.fillMaxWidth(),
            enabled = !installed && !busy,
            variant = if (installed) DsButtonVariant.Ghost else DsButtonVariant.Outline,
        )
    }
}

@Composable
private fun GalleryPersonaCard(
    entry: PersonaGalleryEntry,
    unsaved: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val waitingText = stringResource(R.string.persona_gallery_waiting)
    val subtitle = when {
        entry.persona.identity.isNotBlank() -> entry.persona.identity
        entry.persona.personality.isNotBlank() -> entry.persona.personality
        else -> waitingText
    }
    DsCard(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(entry.persona.name)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.persona.name,
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (unsaved) {
                GalleryPill(stringResource(R.string.persona_gallery_unsaved_badge))
            }
            GalleryPill(stringResource(R.string.persona_gallery_story_count, entry.stories.size))
            GalleryPill(stringResource(R.string.persona_gallery_dialogue_count, entry.totalDialogueCount()))
            if (entry.persona.corrections.isNotEmpty()) {
                GalleryPill(stringResource(R.string.persona_gallery_corrections_count, entry.persona.corrections.size))
            }
        }
    }
}

@Composable
private fun GalleryStoryCard(
    story: PersonaGalleryStory,
    selected: Boolean,
    unsaved: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) DsTheme.colors.accent.copy(alpha = 0.10f) else DsTheme.colors.bgLayer1,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    story.title.ifBlank { stringResource(R.string.persona_gallery_untitled_story) },
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = story.notes.ifBlank { story.chatState.relationshipState }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = DsType.caption11,
                        color = DsTheme.colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (unsaved) {
                GalleryPill(stringResource(R.string.persona_gallery_unsaved_badge))
                Spacer(Modifier.width(6.dp))
            }
            GalleryPill(
                stringResource(
                    R.string.persona_gallery_dialogue_count,
                    story.history.count { it.role == "user" || it.role == "assistant" },
                ),
            )
        }
    }
}

@Composable
private fun ArchivedDialogueRow(
    text: String,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = DsTheme.colors.bgLayer2,
    ) {
        Text(
            text,
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        )
    }
}

@Composable
private fun PersonaHero(persona: PersonaProfile, subtitle: String) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(persona.name, large = true)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    persona.name,
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    persona.identity.ifBlank { subtitle },
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.identity.isNotBlank()) {
                    Text(subtitle, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
                }
            }
        }
    }
}

@Composable
private fun PersonaAvatar(name: String, large: Boolean = false) {
    val size = if (large) 56.dp else 44.dp
    val fallback = stringResource(R.string.persona_gallery_avatar_fallback)
    val initial = name.trim().firstOrNull()?.toString().orEmpty().ifBlank { fallback }
    Surface(
        shape = CircleShape,
        color = DsTheme.colors.accent.copy(alpha = 0.13f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initial,
                style = if (large) DsType.large20 else DsType.std14,
                color = DsTheme.colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GalleryPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = DsTheme.colors.bgLayer1,
    ) {
        Text(
            text,
            style = DsType.caption11,
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PersonaDetails(persona: PersonaProfile) {
    val scalarSections = listOf(
        stringResource(R.string.persona_field_identity) to persona.identity,
        stringResource(R.string.persona_field_background) to persona.background,
        stringResource(R.string.persona_field_personality) to persona.personality,
        stringResource(R.string.persona_field_speech_style) to persona.speechStyle,
        stringResource(R.string.persona_field_relationship) to persona.relationship,
        stringResource(R.string.persona_field_world_setting) to persona.worldSetting,
        stringResource(R.string.persona_field_franchise) to persona.franchise,
        stringResource(R.string.persona_field_timeline) to persona.timelinePosition,
    ).filter { it.second.isNotBlank() }
    val listSections = listOf(
        stringResource(R.string.persona_field_motivations) to persona.coreMotivations,
        stringResource(R.string.persona_field_values) to persona.valuePriorities,
        stringResource(R.string.persona_field_behavior_patterns) to persona.behaviorPatterns,
        stringResource(R.string.persona_field_internal_contradictions) to persona.internalContradictions,
        stringResource(R.string.persona_field_knowledge_boundary) to persona.knowledgeBoundary,
        stringResource(R.string.persona_field_lore) to persona.loreEntries.map { entry ->
            entry.title.ifBlank { entry.content.take(80) }
        },
        stringResource(R.string.persona_field_constraints) to persona.hardConstraints,
        stringResource(R.string.persona_field_dialogues) to persona.exampleDialogues,
        stringResource(R.string.persona_field_banned) to persona.bannedPhrases,
        stringResource(R.string.persona_field_signature) to persona.signaturePhrases,
        stringResource(R.string.persona_field_corrections) to persona.corrections,
    ).filter { it.second.isNotEmpty() }

    if (scalarSections.isEmpty() && listSections.isEmpty()) return
    DsCard {
        Text(
            stringResource(R.string.persona_gallery_fixed_persona),
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        scalarSections.forEach { (title, value) ->
            PersonaDetailRow(title, value)
        }
        listSections.forEach { (title, values) ->
            PersonaDetailRow(title, values.joinToString("；"))
        }
    }
}

@Composable
private fun PersonaDetailRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        Text(value, style = DsType.small13, color = DsTheme.colors.labelSecondary)
    }
}

@Composable
private fun PersonaInspectionPanel(
    result: PersonaInspectionResult,
    selectedKeys: List<String>,
    onToggle: (PersonaAppendSuggestion) -> Unit,
) {
    if (result.conflicts.isEmpty() && result.suggestions.isEmpty()) {
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PersonSearch,
                    contentDescription = null,
                    tint = DsTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_inspection_clean),
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
        return
    }

    if (result.conflicts.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_conflict_count, result.conflicts.size),
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        result.conflicts.forEach { conflict ->
            DsCard {
                Text(
                    personaFieldLabel(conflict.field),
                    style = DsType.caption11,
                    color = DsTheme.colors.error,
                )
                if (conflict.fixedValue.isNotBlank()) {
                    Text(
                        stringResource(R.string.persona_gallery_fixed_value, conflict.fixedValue),
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
                Text(
                    stringResource(R.string.persona_gallery_observed_value, conflict.observedValue),
                    style = DsType.small13,
                    color = DsTheme.colors.labelPrimary,
                )
                if (conflict.reason.isNotBlank()) {
                    Text(
                        conflict.reason,
                        style = DsType.caption11,
                        color = DsTheme.colors.labelTertiary,
                    )
                }
            }
        }
    }

    if (result.suggestions.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_suggestions_title),
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        result.suggestions.forEach { suggestion ->
            val key = suggestionKey(suggestion)
            DsCard(onClick = { onToggle(suggestion) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = key in selectedKeys,
                        onCheckedChange = { onToggle(suggestion) },
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            personaFieldLabel(suggestion.field),
                            style = DsType.caption11,
                            color = DsTheme.colors.accent,
                        )
                        Text(
                            suggestion.value,
                            style = DsType.small13,
                            color = DsTheme.colors.labelPrimary,
                        )
                        if (suggestion.evidence.isNotBlank()) {
                            Text(
                                stringResource(R.string.persona_gallery_evidence, suggestion.evidence),
                                style = DsType.caption11,
                                color = DsTheme.colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun suggestionKey(suggestion: PersonaAppendSuggestion): String =
    "${suggestion.field}|${suggestion.value.trim()}"

@Composable
private fun personaFieldLabel(field: String): String = when (field) {
    "identity" -> stringResource(R.string.persona_field_identity)
    "background" -> stringResource(R.string.persona_field_background)
    "personality" -> stringResource(R.string.persona_field_personality)
    "speechStyle" -> stringResource(R.string.persona_field_speech_style)
    "relationship" -> stringResource(R.string.persona_field_relationship)
    "worldSetting" -> stringResource(R.string.persona_field_world_setting)
    "franchise" -> stringResource(R.string.persona_field_franchise)
    "timelinePosition" -> stringResource(R.string.persona_field_timeline)
    "coreMotivations" -> stringResource(R.string.persona_field_motivations)
    "valuePriorities" -> stringResource(R.string.persona_field_values)
    "behaviorPatterns" -> stringResource(R.string.persona_field_behavior_patterns)
    "internalContradictions" -> stringResource(R.string.persona_field_internal_contradictions)
    "knowledgeBoundary" -> stringResource(R.string.persona_field_knowledge_boundary)
    "hardConstraints" -> stringResource(R.string.persona_field_constraints)
    "exampleDialogues" -> stringResource(R.string.persona_field_dialogues)
    "bannedPhrases" -> stringResource(R.string.persona_field_banned)
    "signaturePhrases" -> stringResource(R.string.persona_field_signature)
    "corrections" -> stringResource(R.string.persona_field_corrections)
    else -> field
}
