package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
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
import java.io.File
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
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val state = runtime.state
    private val _gallery = MutableStateFlow<List<PersonaGalleryEntry>>(emptyList())
    private val transfer = LocalPersonaTransferCoordinator(runtime, chatUi)
    val gallery: StateFlow<List<PersonaGalleryEntry>> = _gallery.asStateFlow()
    val personaPresets: List<PersonaPreset> = PersonaPresetCatalog.presets
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
): Result<PersonaGalleryEntry> = runCatching {
    val snapshot = state.value
    check(
        !snapshot.loading &&
            !snapshot.kernel.running &&
            snapshot.usageMode == LocalUsageMode.CHAT &&
            !snapshot.chat.groupChat.enabled
    ) {
        "请在单人聊天空闲时保存人设与故事"
    }
    val completeHistory = withContext(Dispatchers.IO) {
        runtime.session.completeTranscriptForUi(snapshot.sessionId)
    }
    val archiveHistory = if (
        snapshot.chat.galleryStoryId == null &&
        snapshot.chat.gallerySaveSuppressedThrough > 0L
    ) {
        completeHistory.filter { message ->
            message.createdAt > snapshot.chat.gallerySaveSuppressedThrough
        }
    } else {
        completeHistory
    }
    val outcome = withContext(Dispatchers.IO) {
        chatUi.saveGallery(
            persona = snapshot.chat.chatPersona,
            sourceSessionId = snapshot.sessionId,
            history = archiveHistory,
            chatState = snapshot.chat.chatState.withoutLegacyConversationContext(),
            notes = notes,
            chatContext = snapshot.chat.chatContext,
            existingId = existingId ?: snapshot.chat.galleryId,
            existingStoryId = existingStoryId ?: snapshot.chat.galleryStoryId,
            forceNewStory = forceNewStory,
        ).also { _gallery.value = chatUi.galleryEntries() }
    }
    runtime.chat.bindChatGallery(outcome.entry.id, outcome.storyId)
    outcome.entry
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

fun selectGalleryPersonaForCurrentChat(id: String): Boolean {
    val snapshot = state.value
    if (
        snapshot.loading ||
        snapshot.kernel.running ||
        snapshot.usageMode != LocalUsageMode.CHAT ||
        snapshot.transcriptIndex.hasDialogue
    ) return false
    val entry = gallery.value.firstOrNull { it.id == id } ?: return false
    runtime.chat.selectChatPersona(entry.persona, galleryId = entry.id)
    return true
}

suspend fun createGalleryPersona(profile: PersonaProfile): Result<PersonaGalleryEntry> = runCatching {
    check(!state.value.loading && !state.value.kernel.running) { "请在聊天空闲时新建人物" }
    require(isMeaningfulGalleryPersona(profile)) { "请填写人物名称和至少一项人物设定" }
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
    if (!state.value.chat.groupChat.enabled) selectGalleryPersonaForCurrentChat(entry.id)
    entry
}
suspend fun autoFillNewPersona(description: String): Result<PersonaProfile> = runSuspendResult {
    val snapshot = state.value
    check(!snapshot.loading && !snapshot.kernel.running && snapshot.modelState.configured) { "请先配置模型并等待当前回复结束" }
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
        return Result.failure(IllegalStateException("请在聊天空闲时检查人物"))
    }
    if (!snapshot.modelState.configured) {
        return Result.failure(IllegalStateException("请先配置聊天模型"))
    }
    val entry = gallery.value.firstOrNull { it.id == id }
        ?: return Result.failure(IllegalStateException("图集条目已不存在"))
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
            storyStage = story?.chatContext?.storyStage.orEmpty(),
        )
    }
}

suspend fun applyGallerySuggestions(
    id: String,
    suggestions: List<PersonaAppendSuggestion>,
): Result<PersonaGalleryEntry> = runCatching {
    val updated = withContext(Dispatchers.IO) {
        check(suggestions.isNotEmpty()) { "请先选择要追加的内容" }
        chatUi.applyGallerySuggestions(id, suggestions)
            ?: error("图集条目已不存在")
    }
    _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
    if (state.value.chat.galleryId == id) {
        runtime.chat.configureChatPersona(updated.persona)
    }
    updated
}

suspend fun setGalleryPortrait(id: String, uri: Uri): Result<PersonaGalleryEntry> = runCatching {
    val previous = gallery.value.firstOrNull { it.id == id }
        ?: error("图集条目已不存在")
    val updated = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType.startsWith("image/")) { "请选择图片文件" }
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.lowercase()
            ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") }
            ?: "jpg"
        val portraitDir = File(appContext.filesDir, "local-harness/chat/persona-portraits").apply {
            check(exists() || mkdirs()) { appContext.getString(R.string.persona_gallery_portrait_dir_failed) }
        }
        val safeId = id.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80)
        val target = File(portraitDir, "$safeId-${System.currentTimeMillis()}.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PERSONA_PORTRAIT_BYTES) { "人物形象图过大，请选择 20MB 以内的图片" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取人物形象图")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "人物形象图无法识别" }
            val saved = chatUi.updateGalleryPortrait(id, target.absolutePath)
                ?: error("图集条目已不存在")
            deleteManagedPortrait(previous.portraitPath, keepPath = target.absolutePath)
            saved
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
    _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
    updated
}

suspend fun removeGalleryPortrait(id: String): Result<PersonaGalleryEntry> = runCatching {
    val previous = gallery.value.firstOrNull { it.id == id }
        ?: error("图集条目已不存在")
    val updated = withContext(Dispatchers.IO) {
        chatUi.updateGalleryPortrait(id, "")
            ?: error("图集条目已不存在")
    }
    deleteManagedPortrait(previous.portraitPath)
    _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
    updated
}

private fun deleteManagedPortrait(path: String, keepPath: String? = null) {
    if (path.isBlank() || path == keepPath) return
    runCatching {
        val root = File(appContext.filesDir, "local-harness/chat/persona-portraits").canonicalFile
        val candidate = File(path).canonicalFile
        if (candidate.path.startsWith(root.path + File.separator)) candidate.delete()
    }
}

suspend fun editGalleryStoryDetails(id: String, storyId: String, notes: String, storyStage: String): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        check(chatUi.updateStoryDetails(id, storyId, notes, storyStage)) { "图集故事已不存在" }
        _gallery.value = chatUi.galleryEntries()
    }
    // The gallery owns persisted stage data. Update the active copy through Chat's
    // session owner only when this very story is bound and the turn is idle.
    runtime.chat.updateBoundStoryStage(id, storyId, storyStage)
    Unit
}

suspend fun renameGalleryStory(id: String, storyId: String, title: String): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        check(chatUi.renameStory(id, storyId, title)) { "图集故事已不存在" }
        _gallery.value = chatUi.galleryEntries()
    }
}

internal suspend fun exportGalleryPersona(
    id: String,
    format: PersonaTransferFormat,
): Result<PersonaTransferDocument> = runCatching { transfer.export(id, format) }

internal suspend fun importGalleryPersona(
    bytes: ByteArray,
    fileName: String?,
    mimeType: String?,
): Result<PersonaGalleryEntry> = runCatching {
    transfer.import(bytes, fileName, mimeType).also {
        _gallery.value = withContext(Dispatchers.IO) { chatUi.galleryEntries() }
    }
}

suspend fun installPersonaPreset(id: String): Result<PersonaGalleryEntry> = runCatching {
    val preset = PersonaPresetCatalog.find(id) ?: error("人物预置不存在")
    val entry = withContext(Dispatchers.IO) {
        PersonaPresetArtworkInstaller(appContext, chatUi).install(preset).also {
            _gallery.value = runCatching { chatUi.galleryEntries() }.getOrDefault(_gallery.value)
        }
    }
    entry
}

suspend fun deleteGalleryEntry(id: String): Result<Unit> = runCatching {
    val portraitPath = gallery.value.firstOrNull { it.id == id }?.portraitPath.orEmpty()
    withContext(Dispatchers.IO) {
        check(chatUi.deleteGallery(id)) { "图集条目已不存在" }
        _gallery.value = chatUi.galleryEntries()
    }
    deleteManagedPortrait(portraitPath)
    runtime.chat.removeGroupChatMemberByGalleryId(id)
    runtime.chat.clearChatGalleryBinding(expectedGalleryId = id)
}

suspend fun deleteGalleryStory(id: String, storyId: String): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        check(chatUi.deleteStory(id, storyId)) { "图集故事已不存在" }
        _gallery.value = chatUi.galleryEntries()
    }
    runtime.chat.clearChatGalleryBinding(expectedGalleryId = id, expectedStoryId = storyId, keepCharacter = true)
}

suspend fun deleteGalleryHistoryMessage(
    id: String,
    storyId: String,
    messageKey: String,
): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        check(chatUi.deleteHistoryMessage(id, storyId, messageKey)) { "gallery_archive_missing" }
        _gallery.value = chatUi.galleryEntries()
    }
}

fun startFromGallery(
    id: String,
    storyId: String?,
    freshStory: Boolean,
): Boolean {
    val snapshot = state.value
    if (snapshot.loading || snapshot.kernel.running || snapshot.usageMode != LocalUsageMode.CHAT) return false
    val entry = gallery.value.firstOrNull { it.id == id } ?: return false
    return runtime.chat.startSessionFromGallery(
        entry = entry,
        storyId = storyId,
        freshStory = freshStory,
    )
}


}
