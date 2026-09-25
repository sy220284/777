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

    suspend fun saveCurrentToGallery(notes: String, existingId: String? = null): Result<PersonaGalleryEntry> = runCatching {
        val snapshot = state.value
        check(!snapshot.loading && !snapshot.running && snapshot.usageMode == LocalUsageMode.CHAT) {
            "请在聊天空闲时保存人设与故事"
        }
        withContext(Dispatchers.IO) {
            galleryStore.save(snapshot.chatPersona, snapshot.sessionId, snapshot.messages, snapshot.chatState, notes, existingId)
                .also { _gallery.value = galleryStore.list() }
        }
    }

    fun hasUnsavedCurrentPersona(): Boolean {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) return false
        val persona = snapshot.chatPersona
        if (!isMeaningfulGalleryPersona(persona)) return false
        return gallery.value.none { samePersonaIdentity(it.persona, persona) }
    }

    suspend fun inspectGalleryPersona(id: String): Result<PersonaInspectionResult> {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) {
            return Result.failure(IllegalStateException("请在聊天空闲时检查人物"))
        }
        if (!snapshot.configured) {
            return Result.failure(IllegalStateException("请先配置聊天模型"))
        }
        val entry = gallery.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("图集条目已不存在"))
        val dialogue = if (snapshot.galleryId == id) {
            (entry.history + snapshot.messages)
                .distinctBy { it.id.ifBlank { "${it.role}|${it.createdAt}|${it.content}" } }
        } else {
            entry.history
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

    suspend fun editGalleryNotes(id: String, notes: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.updateNotes(id, notes)) { "图集条目已不存在" }
            _gallery.value = galleryStore.list()
        }
    }

    suspend fun deleteGalleryEntry(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.delete(id)) { "图集条目已不存在" }
            _gallery.value = galleryStore.list()
        }
    }

    fun startFromGallery(id: String): Boolean {
        val snapshot = state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) return false
        val entry = gallery.value.firstOrNull { it.id == id } ?: return false
        engine.createSession(LocalConversationMode.INDEPENDENT, LocalUsageMode.CHAT, entry)
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
