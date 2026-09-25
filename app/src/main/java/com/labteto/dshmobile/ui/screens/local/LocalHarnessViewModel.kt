package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaInspectionService
import com.labteto.dshmobile.local.chat.galleryEntryHasUnsavedChanges
import com.labteto.dshmobile.local.chat.isMeaningfulGalleryPersona
import com.labteto.dshmobile.local.chat.samePersonaIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** UI adapter for the process-wide on-device Harness engine. */
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val engine: LocalHarnessEngine,
    private val personaAutoFillService: PersonaAutoFillService,
    private val personaInspectionService: PersonaInspectionService,
    private val galleryStore: ChatPersonaGalleryStore,
) : ViewModel() {
    val state = engine.state
    private val _gallery = MutableStateFlow<List<PersonaGalleryEntry>>(emptyList())
    val gallery = _gallery.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { galleryStore.list() }.onSuccess { _gallery.value = it }
        }
    }

    suspend fun saveCurrentToGallery(
        notes: String,
        existingId: String? = null,
        existingStoryId: String? = null,
        forceNewStory: Boolean = false,
    ): Result<PersonaGalleryEntry> = runCatching {
        val snapshot = state.value
        check(!snapshot.loading && !snapshot.running && snapshot.usageMode == LocalUsageMode.CHAT) {
            "请在聊天空闲时保存人设与故事"
        }
        val outcome = withContext(Dispatchers.IO) {
            galleryStore.save(
                persona = snapshot.chatPersona,
                sourceSessionId = snapshot.sessionId,
                history = snapshot.messages,
                chatState = snapshot.chatState,
                notes = notes,
                existingId = existingId ?: snapshot.galleryId,
                existingStoryId = existingStoryId ?: snapshot.galleryStoryId,
                forceNewStory = forceNewStory,
            ).also { _gallery.value = galleryStore.list() }
        }
        engine.bindChatGallery(outcome.entry.id, outcome.storyId)
        outcome.entry
    }

    fun hasUnsavedCurrentPersona(): Boolean {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) return false
        val persona = snapshot.chatPersona
        if (!isMeaningfulGalleryPersona(persona)) return false

        val relevantMessages = snapshot.messages.filter { message ->
            (message.role == "user" || message.role == "assistant") &&
                message.createdAt > snapshot.gallerySaveSuppressedThrough
        }
        if (snapshot.gallerySaveSuppressedThrough > 0L && relevantMessages.isEmpty()) return false

        val bound = snapshot.galleryId?.let { id -> gallery.value.firstOrNull { it.id == id } }
        if (snapshot.galleryId != null) {
            return bound == null || galleryEntryHasUnsavedChanges(
                entry = bound,
                storyId = snapshot.galleryStoryId,
                persona = persona,
                history = relevantMessages,
                chatState = snapshot.chatState,
            )
        }

        if (relevantMessages.isNotEmpty()) return true
        return gallery.value.none { samePersonaIdentity(it.persona, persona) }
    }

    fun currentGalleryNeedsUpdate(): Boolean = state.value.galleryId != null

    fun currentGalleryHasUnsavedChanges(): Boolean = hasUnsavedCurrentPersona()

    suspend fun inspectGalleryPersona(
        id: String,
        storyId: String?,
    ): Result<PersonaInspectionResult> {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) {
            return Result.failure(IllegalStateException("请在聊天空闲时检查人物"))
        }
        if (!snapshot.configured) {
            return Result.failure(IllegalStateException("请先配置聊天模型"))
        }
        val entry = gallery.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("图集条目已不存在"))
        val story = entry.story(storyId)
        val archived = story?.history.orEmpty()
        val dialogue = if (
            snapshot.galleryId == id &&
            snapshot.galleryStoryId == story?.id
        ) {
            (archived + snapshot.messages)
                .distinctBy { it.id.ifBlank { "${it.role}|${it.createdAt}|${it.content}" } }
        } else {
            archived
        }
        return runCatching {
            personaInspectionService.inspect(
                model = snapshot.model,
                baseUrl = snapshot.baseUrl,
                persona = entry.persona,
                messages = dialogue,
            )
        }
    }

    suspend fun applyGallerySuggestions(
        id: String,
        suggestions: List<PersonaAppendSuggestion>,
    ): Result<PersonaGalleryEntry> = runCatching {
        val updated = withContext(Dispatchers.IO) {
            check(suggestions.isNotEmpty()) { "请先选择要追加的内容" }
            galleryStore.applySuggestions(id, suggestions)
                ?: error("图集条目已不存在")
        }
        _gallery.value = withContext(Dispatchers.IO) { galleryStore.list() }
        if (state.value.galleryId == id) {
            engine.configureChatPersona(updated.persona)
        }
        updated
    }

    suspend fun editGalleryNotes(id: String, storyId: String, notes: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.updateStoryNotes(id, storyId, notes)) { "图集故事已不存在" }
            _gallery.value = galleryStore.list()
        }
    }

    suspend fun renameGalleryStory(id: String, storyId: String, title: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.renameStory(id, storyId, title)) { "图集故事已不存在" }
            _gallery.value = galleryStore.list()
        }
    }

    suspend fun deleteGalleryEntry(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.delete(id)) { "图集条目已不存在" }
            _gallery.value = galleryStore.list()
        }
        engine.clearChatGalleryBinding(expectedGalleryId = id)
    }

    suspend fun deleteGalleryStory(id: String, storyId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.deleteStory(id, storyId)) { "图集故事已不存在" }
            _gallery.value = galleryStore.list()
        }
        engine.clearChatGalleryBinding(expectedGalleryId = id, expectedStoryId = storyId, keepCharacter = true)
    }

    suspend fun deleteGalleryHistoryMessage(
        id: String,
        storyId: String,
        messageKey: String,
    ): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.deleteHistoryMessage(id, storyId, messageKey)) { "gallery_archive_missing" }
            _gallery.value = galleryStore.list()
        }
    }

    fun startFromGallery(
        id: String,
        storyId: String?,
        freshStory: Boolean,
    ): Boolean {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) return false
        val entry = gallery.value.firstOrNull { it.id == id } ?: return false
        engine.createSession(
            mode = LocalConversationMode.INDEPENDENT,
            usageMode = LocalUsageMode.CHAT,
            galleryEntry = entry,
            galleryStoryId = storyId,
            freshGalleryStory = freshStory,
        )
        return true
    }

    fun configure(apiKey: String, model: String, baseUrl: String) = engine.configure(apiKey, model, baseUrl)
    fun selectModel(model: String) = engine.selectModel(model)
    fun setImageInputMode(mode: LocalImageInputMode) = engine.configureImageInputMode(mode)
    fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()) = engine.send(text, attachments)
    fun regenerateReply(messageId: String): Boolean = engine.regenerateReply(messageId)
    suspend fun deleteSessions(ids: Set<String>): Int = engine.deleteSessions(ids)
    suspend fun importAttachment(uri: Uri): LocalImportedAttachment = engine.importAttachment(uri)
    suspend fun workspaceFiles() = engine.workspaceFilesForUi()
    suspend fun conversationFiles(sessionId: String) = engine.conversationFilesForUi(sessionId)
    suspend fun previewWorkspaceFile(path: String) = engine.previewWorkspaceFileForUi(path)
    fun approve(callId: String) = engine.answerApproval(callId, true)
    fun deny(callId: String) = engine.answerApproval(callId, false)
    fun enableAutoApproval() = engine.enableAutoApproval()
    fun enableAutoApprovalForPending(callId: String) = engine.enableAutoApprovalForPending(callId)
    fun enableDeviceApprovalLease(callId: String) = engine.enableDeviceApprovalLease(callId)
    fun disableDeviceApprovalLease() = engine.disableDeviceApprovalLease()
    fun disableAutoApproval() = engine.disableAutoApproval()
    fun answerQuestion(callId: String, answer: String) = engine.answerQuestion(callId, answer)
    fun cancelQuestion(callId: String) = engine.cancelQuestion(callId)
    fun stop() = engine.stop()
    fun newSession() = engine.createSession(LocalConversationMode.INDEPENDENT)
    fun createSession(mode: LocalConversationMode) = engine.createSession(mode)
    fun setPlanMode(enabled: Boolean) = engine.setPlanMode(enabled)
    fun switchUsageMode(mode: LocalUsageMode) = engine.switchUsageMode(mode)
    fun configureChatPersona(profile: PersonaProfile) = engine.configureChatPersona(profile)
    fun selectChatDirection(direction: String?) = engine.selectChatDirection(direction)

    suspend fun autoFillChatPersona(description: String): Result<PersonaProfile> {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) {
            return Result.failure(IllegalStateException("persona_autofill_busy"))
        }
        if (!snapshot.configured) {
            return Result.failure(IllegalStateException("persona_autofill_unconfigured"))
        }
        return runCatching {
            val generated = personaAutoFillService.generate(
                model = snapshot.model,
                baseUrl = snapshot.baseUrl,
                current = snapshot.chatPersona,
                recentMessages = snapshot.messages,
                description = description,
            )
            engine.syncDefaultChatPersona(generated)
        }
    }
    fun switchSession(sessionId: String) = engine.switchSession(sessionId)
    fun clearCredential() = engine.clearCredential()
}
