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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.MAX_PERSONA_TRANSFER_BYTES
import com.labteto.dshmobile.local.presentation.PERSONA_WORD_MIME
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.presentation.galleryMessageArchiveKey
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsSheetChoiceRow
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
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

    DsBottomSheet(
        title = stringResource(
            if (isUpdate) R.string.persona_gallery_unsaved_update_title
            else R.string.persona_gallery_new_character_title,
        ),
        onDismiss = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        scrollable = true,
        footer = {
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
                size = DsButtonSize.Large,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_skip_save),
                onClick = onContinue,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                size = DsButtonSize.Large,
                enabled = !busy,
            )
            error?.let {
                Text(it, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.error)
            }
        },
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
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
        )

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
    onCreate: () -> Unit,
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
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    var selectedStoryId by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
    val selectedStory = selected?.stories?.firstOrNull { it.id == selectedStoryId }
    var search by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable(selectedId, selectedStoryId, selectedStory?.notes) {
        mutableStateOf(selectedStory?.notes.orEmpty())
    }
    var storyTitle by rememberSaveable(selectedId, selectedStoryId, selectedStory?.title) {
        mutableStateOf(selectedStory?.title.orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var deletingCharacter by remember { mutableStateOf(false) }
    var deletingStory by remember(selectedStoryId) { mutableStateOf(false) }
    var pendingEntryDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingPresetDeleteId by remember { mutableStateOf<String?>(null) }
    var visibleHistory by rememberSaveable(selectedId, selectedStoryId) { mutableStateOf(8) }
    var showHistory by rememberSaveable(selectedId, selectedStoryId) { mutableStateOf(false) }
    var pendingHistoryDeleteKey by remember(selectedId, selectedStoryId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember(selectedId, selectedStoryId) { mutableStateOf<String?>(null) }
    var inspection by remember(selectedId, selectedStoryId) { mutableStateOf<PersonaInspectionResult?>(null) }
    var inspecting by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var showMoreActions by remember(selectedId, selectedStoryId) { mutableStateOf(false) }
    var showPortraitActions by remember(selectedId) { mutableStateOf(false) }
    var showPersonaDetails by rememberSaveable(selectedId) { mutableStateOf(false) }
    var editingStoryTitle by rememberSaveable(selectedId, selectedStoryId) { mutableStateOf(false) }
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
                    } ?: error(exportFailedText)
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
        if (busy || inspecting) return
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

    BackHandler(enabled = selected != null, onBack = ::navigateBack)

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
        DsBottomSheet(
            title = stringResource(R.string.persona_gallery_export_format_title),
            onDismiss = { showExportFormatDialog = false },
        ) {
            Text(
                stringResource(R.string.persona_gallery_export_format_hint),
                style = DsType.small13.withReadingWeight(),
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

    fun dismissMoreActions() {
        showMoreActions = false
        editingStoryTitle = false
        storyTitle = selectedStory?.title.orEmpty()
        deletingStory = false
        deletingCharacter = false
    }

    if (showMoreActions && selected != null) {
        DsBottomSheet(
            title = stringResource(R.string.persona_gallery_more_actions),
            subtitle = selected.persona.name,
            onDismiss = ::dismissMoreActions,
            dismissEnabled = !busy && !inspecting,
            scrollable = true,
            footer = {
                DsButton(
                    text = stringResource(R.string.persona_gallery_cancel),
                    onClick = ::dismissMoreActions,
                    variant = DsButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !inspecting,
                )
            },
        ) {
            selectedStory?.let { story ->
                Text(
                    stringResource(R.string.persona_gallery_current_story_actions, story.title),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
                if (editingStoryTitle) {
                    DsTextField(
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
                    DsSheetChoiceRow(
                        title = stringResource(R.string.persona_gallery_rename_story),
                        icon = FeatherIcons.Edit3,
                        onClick = { editingStoryTitle = true },
                        enabled = !busy && !inspecting,
                    )
                }
                if (
                    canSave &&
                    currentGalleryId == selected.id &&
                    currentGalleryStoryId == story.id &&
                    currentHasUnsavedChanges
                ) {
                    DsSheetChoiceRow(
                        title = stringResource(R.string.persona_gallery_save_progress),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                onSaveCurrent(notes, selected.id, story.id, false)
                                    .onSuccess {
                                        notice = mergeDoneText
                                        showMoreActions = false
                                    }
                                    .onFailure { error = it.message ?: updateFailedText }
                                busy = false
                            }
                        },
                        icon = FeatherIcons.FilePlus,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
            }
            if (
                currentGalleryId == selected.id &&
                currentGalleryStoryId == null &&
                currentHasUnsavedChanges &&
                canSave
            ) {
                DsSheetChoiceRow(
                    title = stringResource(R.string.persona_gallery_save_current_new_story),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onSaveCurrent("", selected.id, null, true)
                                .onSuccess {
                                    selectedStoryId = it.stories.maxByOrNull(PersonaGalleryStory::updatedAt)?.id
                                    notice = mergeDoneText
                                    showMoreActions = false
                                }
                                .onFailure { error = it.message ?: saveFailedText }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }

            Text(
                stringResource(R.string.persona_gallery_persona_actions),
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
            )
            DsSheetChoiceRow(
                title = stringResource(R.string.persona_gallery_view_profile),
                icon = FeatherIcons.User,
                onClick = { dismissMoreActions(); showPersonaDetails = true },
                enabled = !busy && !inspecting,
            )
            selectedStory?.let { story ->
                DsSheetChoiceRow(
                    title = if (inspecting) {
                        stringResource(R.string.persona_gallery_inspecting)
                    } else {
                        stringResource(R.string.persona_gallery_inspect)
                    },
                    onClick = {
                        dismissMoreActions()
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
                    icon = FeatherIcons.CheckSquare,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !inspecting,
                )
            }
            DsSheetChoiceRow(
                title = stringResource(R.string.persona_gallery_portrait_actions_title),
                icon = FeatherIcons.Image,
                onClick = { dismissMoreActions(); showPortraitActions = true },
                enabled = !busy && !inspecting,
            )
            DsSheetChoiceRow(
                title = stringResource(R.string.persona_gallery_export_file),
                icon = FeatherIcons.Download,
                onClick = { dismissMoreActions(); showExportFormatDialog = true; error = null },
                enabled = !busy && !inspecting,
            )
            androidx.compose.material3.HorizontalDivider(color = DsTheme.colors.borderL3)
            selectedStory?.let { story ->
                if (deletingStory) {
                    DsCard {
                        Text(
                            stringResource(R.string.persona_gallery_delete_story_confirm, story.title),
                            style = DsType.small13.withReadingWeight(),
                            color = DsTheme.colors.error,
                        )
                        Text(
                            stringResource(R.string.persona_gallery_delete_story_hint),
                            style = DsType.caption11.withReadingWeight(),
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
                                                showMoreActions = false
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
                    DsSheetChoiceRow(
                        title = stringResource(R.string.persona_gallery_delete_story),
                        icon = FeatherIcons.Trash2,
                        danger = true,
                        onClick = { deletingCharacter = false; deletingStory = true },
                        enabled = !busy && !inspecting && !hasLocalStoryEdits,
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
                        error = null
                        scope.launch {
                            onDelete(selected.id)
                                .onSuccess { selectedId = null; selectedStoryId = null; dismissMoreActions() }
                                .onFailure { error = it.message ?: deleteFailedText }
                            busy = false
                        }
                    },
                )
            } else {
                DsSheetChoiceRow(
                    title = stringResource(R.string.persona_gallery_delete),
                    icon = FeatherIcons.Trash2,
                    danger = true,
                    enabled = !busy && !inspecting && !hasLocalStoryEdits,
                    onClick = { deletingStory = false; deletingCharacter = true },
                )
            }
            error?.let { Text(it, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.error) }
        }
    }
    if (showPersonaDetails && selected != null) {
        DsBottomSheet(
            title = stringResource(R.string.persona_gallery_view_profile),
            onDismiss = { showPersonaDetails = false },
            scrollable = true,
        ) {
            PersonaDetails(selected.persona)
        }
    }
    if (showPortraitActions && selected != null) {
        DsBottomSheet(
            title = stringResource(R.string.persona_gallery_portrait_actions_title),
            onDismiss = { showPortraitActions = false },
            dismissEnabled = !busy,
        ) {
            DsSheetChoiceRow(
                title = stringResource(R.string.persona_gallery_portrait_replace),
                icon = FeatherIcons.Image,
                onClick = {
                    showPortraitActions = false
                    portraitTargetId = selected.id
                    portraitPicker.launch(arrayOf("image/*"))
                },
                enabled = !busy,
            )
            if (selected.portraitPath.isNotBlank()) {
                DsSheetChoiceRow(
                    title = stringResource(R.string.persona_gallery_portrait_remove),
                    icon = FeatherIcons.Trash2,
                    danger = true,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onRemovePortrait(selected.id)
                                .onSuccess { showPortraitActions = false }
                                .onFailure { error = it.message ?: portraitSaveFailedText }
                            busy = false
                        }
                    },
                    enabled = !busy,
                )
            }
            error?.let { Text(it, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.error) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DsTheme.colors.rootSurface(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            DsTopBar(
                title = if (selected == null) stringResource(R.string.persona_gallery_title) else stringResource(R.string.persona_gallery_detail_title),
                onBack = ::navigateBack,
                backContentDescription = stringResource(R.string.common_back),
                actionIcon = if (selected != null) FeatherIcons.MoreVertical else null,
                actionContentDescription = stringResource(R.string.persona_gallery_more_actions),
                actionEnabled = !busy && !inspecting,
                onAction = if (selected != null) ({ showMoreActions = true }) else null,
                largeTitle = selected == null,
                modifier = Modifier.padding(horizontal = DsSpacing.medium),
            )
            if (selected == null) {
                PersonaGalleryOverviewV3(
                    entries = entries,
                    presets = presets,
                    hiddenPresetIds = hiddenPresetIds,
                    currentPersona = currentPersona,
                    currentGalleryId = currentGalleryId,
                    currentHasUnsavedChanges = currentHasUnsavedChanges,
                    canSave = canSave,
                    busy = busy,
                    errorMessage = error,
                    onSelectEntry = { id ->
                        selectedId = id
                        selectedStoryId = null
                        error = null
                        notice = null
                    },
                    onCreate = onCreate,
                    onImport = {
                        importDocument.launch(
                            arrayOf(
                                "application/json",
                                "text/plain",
                                "text/markdown",
                                PERSONA_WORD_MIME,
                            ),
                        )
                    },
                    onInstallPreset = { presetId ->
                        busy = true
                        error = null
                        notice = null
                        scope.launch {
                            onInstallPreset(presetId)
                                .onFailure { error = it.message ?: presetInstallFailedText }
                            busy = false
                        }
                    },
                    onRequestHidePreset = { presetId ->
                        pendingPresetDeleteId = presetId
                        error = null
                    },
                    onSyncCurrent = {
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
                    onDeleteSelected = { ids ->
                        var firstFailure: Throwable? = null
                        ids.forEach { id ->
                            onDelete(id).exceptionOrNull()?.let { failure ->
                                if (firstFailure == null) firstFailure = failure
                            }
                        }
                        firstFailure?.let { Result.failure(it) } ?: Result.success(Unit)
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    val totalDialogue = selected.totalDialogueCount()
                    val relationSummary = stringResource(
                        R.string.persona_gallery_character_summary,
                        selected.stories.size,
                        totalDialogue,
                    )
                    PersonaGalleryDetailHeaderV3(
                        entry = selected,
                        relationSummary = relationSummary,
                        busy = busy || inspecting,
                        onChoosePortrait = {
                            portraitTargetId = selected.id
                            portraitPicker.launch(arrayOf("image/*"))
                        },
                        onManagePortrait = { showPortraitActions = true },
                    )

                    notice?.let {
                        Surface(
                            color = DsTheme.colors.characterAccentTertiary,
                            shape = DsShapes.block,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                it,
                                style = DsType.small13.withReadingWeight(),
                                color = DsTheme.colors.labelPrimary,
                                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.persona_gallery_storylines_title),
                        style = DsType.std14Strong.withReadingWeight(),
                        color = DsTheme.colors.labelPrimary,
                    )
                    if (selected.stories.isEmpty()) {
                        DsCard {
                            Text(
                                stringResource(R.string.persona_gallery_storylines_empty),
                                style = DsType.small13.withReadingWeight(),
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
                                        if (busy || inspecting) return@GalleryStoryCard
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

                    if (inspecting) {
                        Text(
                            stringResource(R.string.persona_gallery_inspecting),
                            style = DsType.small13.withReadingWeight(),
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                    if (
                        canSave && currentGalleryId == selected.id && currentHasUnsavedChanges &&
                        (currentGalleryStoryId == null || currentGalleryStoryId == selectedStoryId)
                    ) {
                        DsButton(
                            text = stringResource(R.string.persona_gallery_pending_progress),
                            onClick = { showMoreActions = true },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && !inspecting,
                        )
                    }
                    selectedStory?.let { story ->
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
                            busy = busy || inspecting,
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

                    }

                }
                error?.let {
                    Text(
                        it,
                        style = DsType.small13.withReadingWeight(),
                        color = DsTheme.colors.error,
                        modifier = Modifier.padding(top = DsSpacing.small),
                    )
                }
            }
            selected?.let { entry ->
                Surface(color = DsTheme.colors.bgLayer1) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(DsSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    ) {
                        selectedStory?.let { story ->
                            DsButton(
                                text = stringResource(R.string.persona_gallery_continue_story),
                                onClick = { onStart(entry.id, story.id, false) },
                                modifier = Modifier.fillMaxWidth(),
                                size = DsButtonSize.Large,
                                enabled = canSave && !busy && !inspecting && !hasLocalStoryEdits,
                            )
                        }
                        DsButton(
                            text = stringResource(R.string.persona_gallery_start_fresh_story),
                            onClick = { onStart(entry.id, null, true) },
                            modifier = Modifier.fillMaxWidth(),
                            variant = if (selectedStory == null) DsButtonVariant.Primary else DsButtonVariant.Ghost,
                            enabled = canSave && !busy && !inspecting && !hasLocalStoryEdits,
                        )
                    }
                }
            }
        }
    }
}
