package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.net.Uri
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.presentation.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.presentation.galleryEntryHasUnsavedChanges
import com.labteto.dshmobile.local.presentation.isMeaningfulGalleryPersona
import com.labteto.dshmobile.local.presentation.samePersonaIdentity
import com.labteto.dshmobile.local.presentation.withoutLegacyConversationContext
import com.labteto.dshmobile.local.presentation.LocalChatUiFacade
import com.labteto.dshmobile.local.presentation.LocalUiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns persona-gallery UI state, persistence, portraits and model-assisted maintenance. */
internal class LocalPersonaGalleryUiController(
    private val runtime: LocalUiRuntime,
    private val chatUi: LocalChatUiFacade,
    private val galleryPort: com.labteto.dshmobile.uicontract.PersonaGalleryUiPort,
    private val selectionPort: com.labteto.dshmobile.uicontract.PersonaSelectionUiPort,
    private val savePort: com.labteto.dshmobile.uicontract.PersonaGallerySaveUiPort,
    private val portraitPort: com.labteto.dshmobile.uicontract.PersonaPortraitUiPort,
    private val deletionPort: com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort,
    private val transferPort: com.labteto.dshmobile.uicontract.PersonaTransferUiPort,
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val state = runtime.state
    private val _gallery = MutableStateFlow<List<PersonaGalleryEntry>>(emptyList())
    val gallery: StateFlow<List<PersonaGalleryEntry>> = _gallery.asStateFlow()
    val personaPresets: List<PersonaPreset> = PersonaPresetCatalog.presets
    suspend fun refresh() { _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() } }
    suspend fun configureChatPersona(profile: PersonaProfile): Result<Unit> = runSuspendResult {
        runtime.chat.configureChatPersona(profile).getOrThrow()
        _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
    }
    init {
        scope.launch(Dispatchers.IO) {
            runCatching { chatUi.galleryEntries() }.onSuccess { _gallery.value = it }
        }
    }

suspend fun saveCurrentToGallery(
    notes: String,
    existingId: String? = null,
    existingStoryId: String? = null,
    forceNewStory: Boolean = false,
    expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?,
): Result<PersonaGalleryEntry> = runSuspendResult {
    val identity = expected ?: error(appContext.getString(R.string.ui_r4_gallery_session_not_ready))
    when (val result = savePort.save(com.labteto.dshmobile.uicontract.SavePersonaGalleryCommand(identity, notes, existingId, existingStoryId, forceNewStory))) {
        is com.labteto.dshmobile.uicontract.UiPersonaGallerySaveReceipt.Saved -> {
            if (result.sessionSync == com.labteto.dshmobile.uicontract.UiStorySessionSync.DEFERRED) {
                withContext(Dispatchers.Main.immediate) {
                    android.widget.Toast.makeText(appContext, R.string.local_gallery_story_saved_sync_pending, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            refresh()
            gallery.value.firstOrNull { it.id == result.galleryId } ?: error(appContext.getString(R.string.ui_r4_gallery_saved_changed))
        }
        com.labteto.dshmobile.uicontract.UiPersonaGallerySaveReceipt.Rejected -> error(appContext.getString(R.string.ui_r4_gallery_save_idle_single))
        is com.labteto.dshmobile.uicontract.UiPersonaGallerySaveReceipt.Failed -> error(result.message ?: appContext.getString(R.string.ui_r4_gallery_save_uncertain))
    }
}

fun hasUnsavedCurrentPersona(): Boolean {
    val snapshot = state.value
    if (
        snapshot.loading ||
        snapshot.kernel.running ||
        snapshot.usageMode != LocalUsageMode.CHAT ||
        snapshot.chat.groupChat.enabled
    ) return false
    val persona = snapshot.chat.chatPersona
    if (!isMeaningfulGalleryPersona(persona)) return false

    val latestDialogueAt = snapshot.transcriptIndex.latestDialogueCreatedAt
    val suppressedThrough = snapshot.chat.gallerySaveSuppressedThrough
    val hasDialogueAfterSuppression =
        snapshot.transcriptIndex.hasDialogue && latestDialogueAt > suppressedThrough

    if (suppressedThrough > 0L && !hasDialogueAfterSuppression) return false

    val bound = snapshot.chat.galleryId?.let { id -> gallery.value.firstOrNull { it.id == id } }
    if (snapshot.chat.galleryId != null) {
        if (bound == null) return true
        val story = bound.story(snapshot.chat.galleryStoryId)
        val archivedThrough = story?.history
            ?.asReversed()
            ?.firstOrNull { message -> message.role == "user" || message.role == "assistant" }
            ?.createdAt
            ?: 0L
        val hasNewDialogue = hasDialogueAfterSuppression && latestDialogueAt > archivedThrough
        return hasNewDialogue || galleryEntryHasUnsavedChanges(
            entry = bound,
            storyId = snapshot.chat.galleryStoryId,
            persona = persona,
            history = emptyList(),
            chatState = snapshot.chat.chatState,
        )
    }

    if (hasDialogueAfterSuppression) return true
    return gallery.value.none { samePersonaIdentity(it.persona, persona) }
}

fun currentGalleryNeedsUpdate(): Boolean =
    !state.value.chat.groupChat.enabled && state.value.chat.galleryId != null

fun currentGalleryHasUnsavedChanges(): Boolean = hasUnsavedCurrentPersona()

fun selectGalleryPersonaForCurrentChat(id: String, expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?): Boolean {
    val identity = expected ?: return false
    return selectionPort.submit(com.labteto.dshmobile.uicontract.PersonaSelectionUiCommand.SelectForCurrentChat(identity, id)) ==
        com.labteto.dshmobile.uicontract.UiCommandReceipt.Queued
}

suspend fun createGalleryPersona(profile: PersonaProfile): Result<PersonaGalleryEntry> = runCatching {
    val originalIdentity = selectionPort.snapshot().identity
    check(!state.value.loading && !state.value.kernel.running) { appContext.getString(R.string.ui_r4_gallery_create_idle) }
    require(isMeaningfulGalleryPersona(profile)) { appContext.getString(R.string.ui_r4_gallery_create_incomplete) }
    val entry = withContext(Dispatchers.IO) {
        chatUi.saveGallery(
            persona = profile,
            sourceSessionId = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry.also { _gallery.value = chatUi.galleryEntries() }
    }
    // Preserve the old create-and-use flow for an empty one-to-one chat.
    // In an existing conversation or a group, creating a gallery card must not replace the cast.
    selectGalleryPersonaForCurrentChat(entry.id, originalIdentity)
    entry
}
suspend fun autoFillNewPersona(description: String): Result<PersonaProfile> = runSuspendResult {
    val snapshot = state.value
    check(!snapshot.loading && !snapshot.kernel.running && snapshot.modelState.configured) { appContext.getString(R.string.ui_r4_gallery_model_not_ready) }
    chatUi.autoFillPersona(
        model = snapshot.modelState.model, baseUrl = snapshot.modelState.baseUrl,
        profileId = snapshot.modelState.modelSelection.activeProfileId,
        current = PersonaProfile(name = ""),
        recentMessages = emptyList(),
        description = description,
    )
}

suspend fun inspectGalleryPersona(
    id: String,
    storyId: String?,
): Result<PersonaInspectionResult> {
    val snapshot = state.value
    if (snapshot.loading || snapshot.kernel.running || snapshot.usageMode != LocalUsageMode.CHAT) {
        return Result.failure(IllegalStateException(appContext.getString(R.string.ui_r4_gallery_inspect_busy)))
    }
    if (!snapshot.modelState.configured) {
        return Result.failure(IllegalStateException(appContext.getString(R.string.ui_r4_gallery_model_unset)))
    }
    val entry = gallery.value.firstOrNull { it.id == id }
        ?: return Result.failure(IllegalStateException(appContext.getString(R.string.ui_r4_gallery_entry_gone)))
    val story = entry.story(storyId)
    val archived = story?.history.orEmpty()
    val dialogue = if (
        snapshot.chat.galleryId == id &&
        snapshot.chat.galleryStoryId == story?.id
    ) {
        val recent = withContext(Dispatchers.IO) {
            runtime.session.transcriptTailForUi(snapshot.sessionId, PERSONA_INSPECTION_RECENT_MESSAGES)
        }
        (archived + recent)
            .distinctBy { it.id.ifBlank { "${it.role}|${it.createdAt}|${it.content}" } }
    } else {
        archived
    }
    return runSuspendResult {
        chatUi.inspectPersona(
            model = snapshot.modelState.model, baseUrl = snapshot.modelState.baseUrl,
            profileId = snapshot.modelState.modelSelection.activeProfileId,
            persona = entry.persona,
            messages = dialogue,
            chatContext = story?.chatContext,
        )
    }
}

suspend fun applyGallerySuggestions(
    id: String,
    suggestions: List<PersonaAppendSuggestion>,
): Result<PersonaGalleryEntry> = runCatching {
    val updated = withContext(Dispatchers.IO) {
        check(suggestions.isNotEmpty()) { appContext.getString(R.string.ui_r4_gallery_suggestions_empty) }
        chatUi.applyGallerySuggestions(id, suggestions)
            ?: error(appContext.getString(R.string.ui_r4_gallery_entry_gone))
    }
    _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
    if (state.value.chat.galleryId == id) {
        runtime.chat.configureChatPersona(updated.persona)
    }
    updated
}

suspend fun setGalleryPortrait(id: String, uri: Uri): Result<PersonaGalleryEntry> =
    updatePortrait(com.labteto.dshmobile.uicontract.PersonaPortraitUiCommand.Import(id, uri.toString()))

suspend fun removeGalleryPortrait(id: String): Result<PersonaGalleryEntry> =
    updatePortrait(com.labteto.dshmobile.uicontract.PersonaPortraitUiCommand.Remove(id))

private suspend fun updatePortrait(command: com.labteto.dshmobile.uicontract.PersonaPortraitUiCommand): Result<PersonaGalleryEntry> = runSuspendResult {
    when (val result = portraitPort.submit(command)) {
        is com.labteto.dshmobile.uicontract.UiPersonaPortraitReceipt.Updated -> {
            result.cleanupWarning?.let { warning -> withContext(Dispatchers.Main.immediate) {
                android.widget.Toast.makeText(appContext, warning, android.widget.Toast.LENGTH_LONG).show()
            } }
            refresh()
            gallery.value.firstOrNull { it.id == result.galleryId } ?: error(appContext.getString(R.string.ui_r4_gallery_portrait_changed))
        }
        com.labteto.dshmobile.uicontract.UiPersonaPortraitReceipt.Missing -> error(appContext.getString(R.string.ui_r4_gallery_persona_gone))
        is com.labteto.dshmobile.uicontract.UiPersonaPortraitReceipt.Failed -> error(result.message ?: appContext.getString(R.string.ui_r4_gallery_portrait_uncertain))
    }
}

suspend fun renameGalleryStory(id: String, storyId: String, title: String): Result<Unit> = runSuspendResult {
    requireGalleryCommit(galleryPort.submit(com.labteto.dshmobile.uicontract.PersonaGalleryUiCommand.RenameStory(id, storyId, title)))
    withContext(Dispatchers.IO) {
        _gallery.value = chatUi.galleryEntries()
    }
}

internal suspend fun exportGalleryPersona(
    id: String,
    format: PersonaTransferFormat,
): Result<PersonaTransferDocument> = runSuspendResult {
    when (val result = transferPort.export(com.labteto.dshmobile.uicontract.ExportPersonaDocumentCommand(id, format))) {
        is com.labteto.dshmobile.uicontract.UiPersonaExportReceipt.Ready -> result.document
        is com.labteto.dshmobile.uicontract.UiPersonaExportReceipt.Failed -> error(result.message ?: appContext.getString(R.string.ui_r4_gallery_export_fail))
    }
}

internal suspend fun importGalleryPersona(
    bytes: ByteArray,
    fileName: String?,
    mimeType: String?,
): Result<PersonaGalleryEntry> = runSuspendResult {
    val result = transferPort.import(com.labteto.dshmobile.uicontract.ImportPersonaDocumentCommand(bytes, fileName, mimeType))
    when (result) {
        is com.labteto.dshmobile.uicontract.UiPersonaImportReceipt.Failed -> error(result.message ?: appContext.getString(R.string.ui_r4_gallery_import_uncertain))
        is com.labteto.dshmobile.uicontract.UiPersonaImportReceipt.Imported -> {
            _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
            gallery.value.firstOrNull { it.id == result.galleryId } ?: error(appContext.getString(R.string.ui_r4_gallery_import_changed))
        }
    }
}

suspend fun installPersonaPreset(id: String): Result<PersonaGalleryEntry> = runCatching {
    val preset = PersonaPresetCatalog.find(id) ?: error(appContext.getString(R.string.ui_r4_gallery_preset_missing))
    val entry = withContext(Dispatchers.IO) {
        PersonaPresetArtworkInstaller(appContext, chatUi, portraitPort.maxImportBytes, deletionPort).install(preset).also {
            _gallery.value = runCatching { chatUi.galleryEntries() }.getOrDefault(_gallery.value)
        }
    }
    entry
}

suspend fun deleteGalleryHistoryMessage(
    id: String,
    storyId: String,
    messageKey: String,
): Result<Unit> = runSuspendResult {
    requireGalleryCommit(galleryPort.submit(com.labteto.dshmobile.uicontract.PersonaGalleryUiCommand.DeleteHistoryMessage(id, storyId, messageKey)))
    withContext(Dispatchers.IO) {
        _gallery.value = chatUi.galleryEntries()
    }
}

private fun requireGalleryCommit(receipt: com.labteto.dshmobile.uicontract.UiCommandReceipt) {
    when (receipt) {
        com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted -> Unit
        is com.labteto.dshmobile.uicontract.UiCommandReceipt.Failed -> error(receipt.message)
        else -> error(appContext.getString(R.string.ui_r4_gallery_story_missing))
    }
}

fun startFromGallery(
    id: String,
    storyId: String?,
    freshStory: Boolean,
    expected: com.labteto.dshmobile.uicontract.UiSessionIdentity?,
): Boolean {
    val identity = expected ?: return false
    return selectionPort.submit(com.labteto.dshmobile.uicontract.PersonaSelectionUiCommand.StartStory(identity, id, storyId, freshStory)) ==
        com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted
}


}
