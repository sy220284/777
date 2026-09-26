package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.labteto.dshmobile.local.chat.MAX_PERSONA_TRANSFER_BYTES
import com.labteto.dshmobile.local.chat.PERSONA_WORD_MIME
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.chat.galleryMessageArchiveKey
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERSONA_GALLERY_UI_PREFS = "persona_gallery_ui"
private const val HIDDEN_PERSONA_PRESET_IDS = "hidden_persona_preset_ids"

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
internal fun PersonaGalleryScreen(
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
    onExport: suspend (String, PersonaTransferFormat) -> Result<PersonaTransferDocument>,
    onImport: suspend (ByteArray, String?, String?) -> Result<PersonaGalleryEntry>,
    onInstallPreset: suspend (String) -> Result<PersonaGalleryEntry>,
    onSetPortrait: suspend (String, android.net.Uri) -> Result<PersonaGalleryEntry>,
    onRemovePortrait: suspend (String) -> Result<PersonaGalleryEntry>,
    onStart: (String, String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val galleryUiPrefs = remember(context) {
        context.getSharedPreferences(PERSONA_GALLERY_UI_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var hiddenPresetIds by remember(galleryUiPrefs) {
        mutableStateOf(
            galleryUiPrefs.getStringSet(HIDDEN_PERSONA_PRESET_IDS, emptySet())
                .orEmpty()
                .toSet(),
        )
    }
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
    var pendingPresetDeleteId by remember { mutableStateOf<String?>(null) }
    var visibleHistory by remember(selectedId, selectedStoryId) { mutableStateOf(8) }
    var showHistory by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var pendingHistoryDeleteKey by remember(selectedId, selectedStoryId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember(selectedId, selectedStoryId) { mutableStateOf<String?>(null) }
    var inspection by remember(selectedId, selectedStoryId) { mutableStateOf<PersonaInspectionResult?>(null) }
    var inspecting by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var showPersonaDetails by remember(selectedId) { mutableStateOf(false) }
    var editingStoryTitle by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var pendingExportDocument by remember { mutableStateOf<PersonaTransferDocument?>(null) }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var portraitTargetId by remember { mutableStateOf<String?>(null) }
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
    val portraitSaveFailedText = stringResource(R.string.persona_gallery_portrait_save_failed)

    fun writePendingExport(uri: android.net.Uri?) {
        val document = pendingExportDocument
        pendingExportDocument = null
        if (uri == null || document == null) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        output.write(document.bytes)
                    } ?: error("无法写入人物导出文件")
                }
            }.onFailure { error = exportFailedText }
        }
    }

    val exportJsonDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PersonaTransferFormat.JSON.mimeType),
        onResult = ::writePendingExport,
    )
    val exportMarkdownDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PersonaTransferFormat.MARKDOWN.mimeType),
        onResult = ::writePendingExport,
    )
    val exportWordDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PersonaTransferFormat.WORD.mimeType),
        onResult = ::writePendingExport,
    )

    fun requestExport(entry: PersonaGalleryEntry, format: PersonaTransferFormat) {
        showExportFormatDialog = false
        busy = true
        error = null
        scope.launch {
            onExport(entry.id, format)
                .onSuccess { document ->
                    pendingExportDocument = document
                    val fileName = personaExportFileName(entry.persona.name, format)
                    when (format) {
                        PersonaTransferFormat.JSON -> exportJsonDocument.launch(fileName)
                        PersonaTransferFormat.MARKDOWN -> exportMarkdownDocument.launch(fileName)
                        PersonaTransferFormat.WORD -> exportWordDocument.launch(fileName)
                    }
                }
                .onFailure { error = it.message ?: exportFailedText }
            busy = false
        }
    }

    val importDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                val document = runCatching {
                    withContext(Dispatchers.IO) { readPersonaShareDocument(context, uri) }
                }.getOrElse {
                    error = it.message ?: importFailedText
                    busy = false
                    return@launch
                }
                onImport(document.bytes, document.fileName, document.mimeType)
                    .onSuccess { imported ->
                        selectedId = imported.id
                        selectedStoryId = null
                    }
                    .onFailure { error = it.message ?: importFailedText }
                busy = false
            }
        }
    }

    val portraitPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val targetId = portraitTargetId
        portraitTargetId = null
        if (uri != null && targetId != null) {
            busy = true
            error = null
            scope.launch {
                onSetPortrait(targetId, uri)
                    .onFailure { error = it.message ?: portraitSaveFailedText }
                busy = false
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

    fun navigateBack() {
        if (selected == null) {
            onDismiss()
        } else if (hasLocalStoryEdits) {
            error = saveEditsBeforeSwitchText
        } else {
            selectedId = null
            selectedStoryId = null
            deletingCharacter = false
            error = null
            notice = null
        }
    }

    BackHandler(onBack = ::navigateBack)

    presets.firstOrNull { it.id == pendingPresetDeleteId }?.let { pending ->
        DeletePresetConfirmDialog(
            preset = pending,
            onCancel = { pendingPresetDeleteId = null },
            onConfirm = {
                val updated = hiddenPresetIds + pending.id
                hiddenPresetIds = updated
                galleryUiPrefs.edit()
                    .putStringSet(HIDDEN_PERSONA_PRESET_IDS, updated)
                    .apply()
                pendingPresetDeleteId = null
            },
        )
    }

    if (showExportFormatDialog && selected != null) {
        DsDialog(
            title = stringResource(R.string.persona_gallery_export_format_title),
            onDismiss = { showExportFormatDialog = false },
        ) {
            Text(
                stringResource(R.string.persona_gallery_export_format_hint),
                style = DsType.small13,
                color = DsTheme.colors.labelSecondary,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_export_json),
                onClick = { requestExport(selected, PersonaTransferFormat.JSON) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_export_markdown),
                onClick = { requestExport(selected, PersonaTransferFormat.MARKDOWN) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                variant = DsButtonVariant.Outline,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_export_word),
                onClick = { requestExport(selected, PersonaTransferFormat.WORD) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                variant = DsButtonVariant.Outline,
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DsTheme.colors.bgBase,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            PersonaGalleryTopBar(
                title = if (selected == null) stringResource(R.string.persona_gallery_title) else selected.persona.name,
                onBack = ::navigateBack,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                if (selected == null) {
            GalleryOverviewHeader(
                characterCount = entries.size,
                storyCount = entries.sumOf { it.stories.size },
                dialogueCount = entries.sumOf { it.totalDialogueCount() },
            )

            val installedPresetIds = entries
                .map { it.persona.presetId }
                .filter(String::isNotBlank)
                .toSet()
            val visiblePresets = presets.filter { preset ->
                preset.id !in installedPresetIds && preset.id !in hiddenPresetIds
            }
            if (visiblePresets.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.persona_gallery_presets_title),
                        style = DsType.std14,
                        color = DsTheme.colors.labelPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    GalleryPill(visiblePresets.size.toString())
                }
                Text(
                    stringResource(R.string.persona_gallery_presets_hint),
                    style = DsType.caption11,
                    color = DsTheme.colors.labelTertiary,
                )
                Text(
                    stringResource(R.string.persona_gallery_preset_long_press_delete_hint),
                    style = DsType.caption11,
                    color = DsTheme.colors.labelTertiary,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    visiblePresets.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            rowPresets.forEach { preset ->
                                PersonaPresetCard(
                                    preset = preset,
                                    busy = busy,
                                    modifier = Modifier.weight(1f),
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
                                    onLongClick = {
                                        pendingPresetDeleteId = preset.id
                                        error = null
                                    },
                                )
                            }
                            if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            DsButton(
                text = stringResource(R.string.persona_gallery_import_file),
                onClick = {
                    importDocument.launch(
                        arrayOf(
                            "application/json",
                            "text/plain",
                            "text/markdown",
                            PERSONA_WORD_MIME,
                        ),
                    )
                },
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.persona_gallery_my_characters),
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                GalleryPill(entries.size.toString())
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
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    filtered.chunked(2).forEach { rowEntries ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                        ) {
                            rowEntries.forEach { entry ->
                                GalleryPersonaCard(
                                    entry = entry,
                                    unsaved = entry.id == currentGalleryId && currentHasUnsavedChanges,
                                    modifier = Modifier.weight(1f),
                                    onChoosePortrait = {
                                        portraitTargetId = entry.id
                                        portraitPicker.launch(arrayOf("image/*"))
                                    },
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
                            if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                        }
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
            SpatialPortraitStandee(
                entry = selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(
                        if (selected.portraitPath.isBlank()) R.string.persona_gallery_portrait_add
                        else R.string.persona_gallery_portrait_replace,
                    ),
                    onClick = {
                        portraitTargetId = selected.id
                        portraitPicker.launch(arrayOf("image/*"))
                    },
                    variant = DsButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
                if (selected.portraitPath.isNotBlank()) {
                    DsButton(
                        text = stringResource(R.string.persona_gallery_portrait_remove),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                onRemovePortrait(selected.id)
                                    .onFailure { error = it.message ?: portraitSaveFailedText }
                                busy = false
                            }
                        },
                        variant = DsButtonVariant.Ghost,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
            }
            PersonaHero(persona = selected.persona, subtitle = relationSummary)
            selectedStory?.let { story ->
                PersonaRelationshipStatusCard(story)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    DsButton(
                        text = stringResource(R.string.persona_gallery_continue_story),
                        onClick = { onStart(selected.id, story.id, false) },
                        modifier = Modifier.weight(1f),
                        enabled = canSave && !busy && !hasLocalStoryEdits,
                    )
                    DsButton(
                        text = stringResource(R.string.persona_gallery_start_fresh_story),
                        onClick = { onStart(selected.id, null, true) },
                        modifier = Modifier.weight(1f),
                        enabled = canSave && !busy && !hasLocalStoryEdits,
                        variant = DsButtonVariant.Outline,
                    )
                }
            } ?: DsButton(
                text = stringResource(R.string.persona_gallery_start_fresh_story),
                onClick = { onStart(selected.id, null, true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy && !hasLocalStoryEdits,
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

            DsButton(
                text = stringResource(R.string.persona_gallery_export_file),
                onClick = {
                    error = null
                    showExportFormatDialog = true
                },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

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
    }
}

@Composable
private fun PersonaGalleryTopBar(
    title: String,
    onBack: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        color = colors.bgBase,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
            )
            Text(
                text = title,
                style = DsType.large20,
                color = colors.labelPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun personaExportFileName(
    name: String,
    format: PersonaTransferFormat,
): String {
    val safe = name.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .take(48)
        .ifBlank { "persona" }
    return "$safe.persona.${format.extension}"
}

private data class PersonaImportDocument(
    val bytes: ByteArray,
    val fileName: String?,
    val mimeType: String?,
)

private fun readPersonaShareDocument(
    context: android.content.Context,
    uri: android.net.Uri,
): PersonaImportDocument {
    val resolver = context.contentResolver
    val fileName = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
    val input = resolver.openInputStream(uri) ?: error("无法读取人物导入文件")
    val bytes = input.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_PERSONA_TRANSFER_BYTES) {
                "人物导入文件超过 16 MB"
            }
        }
        output.toByteArray()
    }
    return PersonaImportDocument(
        bytes = bytes,
        fileName = fileName,
        mimeType = resolver.getType(uri),
    )
}

@Composable
private fun DeletePresetConfirmDialog(
    preset: PersonaPreset,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    DsDialog(
        title = stringResource(R.string.persona_gallery_preset_delete_title),
        onDismiss = onCancel,
    ) {
        Text(
            stringResource(R.string.persona_gallery_preset_delete_confirm, preset.persona.name),
            style = DsType.small13,
            color = DsTheme.colors.labelPrimary,
        )
        Text(
            stringResource(R.string.persona_gallery_preset_delete_hint),
            style = DsType.caption11,
            color = DsTheme.colors.labelTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsButton(
                text = stringResource(R.string.persona_gallery_cancel),
                onClick = onCancel,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_confirm_delete),
                onClick = onConfirm,
                variant = DsButtonVariant.Danger,
                modifier = Modifier.weight(1f),
            )
        }
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
private fun GalleryOverviewHeader(
    characterCount: Int,
    storyCount: Int,
    dialogueCount: Int,
) {
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Text(
                    stringResource(R.string.persona_gallery_master),
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        R.string.persona_gallery_overview_stats,
                        characterCount,
                        storyCount,
                        dialogueCount,
                    ),
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
    busy: Boolean,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = DsTheme.colors.bgLayer1,
        shadowElevation = 2.dp,
        modifier = modifier
            .heightIn(min = 196.dp)
            .combinedClickable(
                enabled = !busy,
                onClick = onInstall,
                onLongClick = onLongClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            PersonaAvatar(preset.persona.name)
            Text(
                preset.persona.name,
                style = DsType.std14,
                color = DsTheme.colors.labelPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GalleryPill(preset.franchise)
            Text(
                preset.summary,
                style = DsType.caption11,
                color = DsTheme.colors.labelSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_preset_add),
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                variant = DsButtonVariant.Outline,
            )
        }
    }
}

@Composable
private fun GalleryPersonaCard(
    entry: PersonaGalleryEntry,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
    onChoosePortrait: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val waitingText = stringResource(R.string.persona_gallery_waiting)
    val subtitle = when {
        entry.persona.identity.isNotBlank() -> entry.persona.identity
        entry.persona.personality.isNotBlank() -> entry.persona.personality
        else -> waitingText
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = DsTheme.colors.bgLayer1,
        shadowElevation = 4.dp,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Box(Modifier.fillMaxWidth()) {
                SpatialPortraitStandee(
                    entry = entry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                )
                DsIconButton(
                    icon = Icons.Outlined.Image,
                    contentDescription = stringResource(
                        if (entry.portraitPath.isBlank()) R.string.persona_gallery_portrait_add
                        else R.string.persona_gallery_portrait_replace,
                    ),
                    onClick = onChoosePortrait,
                    modifier = Modifier.align(Alignment.TopEnd),
                    containerColor = DsTheme.colors.bgLayer2.copy(alpha = 0.88f),
                )
            }
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
                style = DsType.caption11,
                color = DsTheme.colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.persona_gallery_standee_meta,
                    entry.stories.size,
                    entry.totalDialogueCount(),
                ),
                style = DsType.caption11,
                color = DsTheme.colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unsaved) {
                GalleryPill(stringResource(R.string.persona_gallery_unsaved_badge))
            }
        }
    }
}

@Composable
private fun SpatialPortraitStandee(
    entry: PersonaGalleryEntry,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var rotationX by remember(entry.id) { mutableStateOf(0f) }
    var rotationY by remember(entry.id) { mutableStateOf(0f) }
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = entry.portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeGalleryPortrait(entry.portraitPath)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(entry.id, entry.portraitPath) {
                    detectDragGestures(
                        onDragEnd = {
                            rotationX = 0f
                            rotationY = 0f
                        },
                        onDragCancel = {
                            rotationX = 0f
                            rotationY = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        rotationY = (rotationY + dragAmount.x / 28f).coerceIn(-11f, 11f)
                        rotationX = (rotationX - dragAmount.y / 36f).coerceIn(-7f, 7f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.accent.copy(alpha = 0.07f),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .shadow(12.dp, RoundedCornerShape(28.dp))
                    .graphicsLayer {
                        this.rotationX = rotationX
                        this.rotationY = rotationY
                        cameraDistance = 24f * density
                    },
            ) {
                if (portrait != null) {
                    Image(
                        bitmap = portrait!!,
                        contentDescription = entry.persona.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.accent.copy(alpha = 0.04f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PersonaAvatar(entry.persona.name, large = true)
                            Spacer(Modifier.size(DsSpacing.small))
                            Text(
                                stringResource(R.string.persona_gallery_portrait_empty),
                                style = DsType.caption11,
                                color = colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = colors.labelTertiary.copy(alpha = 0.16f),
            modifier = Modifier
                .fillMaxWidth(0.56f)
                .height(10.dp)
                .graphicsLayer {
                    rotationX = 65f
                    cameraDistance = 18f * density
                },
        ) {}
    }
}

private fun decodeGalleryPortrait(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 1_600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
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
private fun PersonaRelationshipStatusCard(story: PersonaGalleryStory) {
    val colors = DsTheme.colors
    val state = story.chatState
    DsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    story.title.ifBlank { stringResource(R.string.persona_gallery_untitled_story) },
                    style = DsType.std14,
                    color = colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.persona_gallery_story_relation),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            GalleryPill(state.relationshipState)
            state.mood.takeIf(String::isNotBlank)?.let { GalleryPill(it) }
        }
        state.currentFocus.takeIf(String::isNotBlank)?.let { focus ->
            Text(
                focus,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.dynamics.sharedMoments.lastOrNull()?.takeIf(String::isNotBlank)?.let { moment ->
            Text(
                moment,
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
