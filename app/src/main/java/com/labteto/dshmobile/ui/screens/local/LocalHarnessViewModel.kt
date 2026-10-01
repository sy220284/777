package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.LocalChatUserEditResult
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.presentation.LocalUiRuntime
import com.labteto.dshmobile.local.presentation.projectChatSurfaceState
import com.labteto.dshmobile.local.presentation.projectShellState
import com.labteto.dshmobile.local.presentation.projectWorkSurfaceState
import com.labteto.dshmobile.local.presentation.projectWorkState
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.withLegacyConversationContext
import com.labteto.dshmobile.local.chat.GroupAnnouncementService
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaInspectionService
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.chat.galleryEntryHasUnsavedChanges
import com.labteto.dshmobile.local.chat.isMeaningfulGalleryPersona
import com.labteto.dshmobile.local.chat.samePersonaIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
@HiltViewModel
class LocalHarnessViewModel @Inject constructor(
    private val runtime: LocalUiRuntime,
    private val personaAutoFillService: PersonaAutoFillService,
    private val groupAnnouncementService: GroupAnnouncementService,
    private val personaInspectionService: PersonaInspectionService,
    private val galleryStore: ChatPersonaGalleryStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val state = runtime.session.state
    val streamingState = runtime.session.streamingState; internal val sendFeedbackState = runtime.session.sendFeedbackState
    val shellState = state.projectShellState(viewModelScope)
    val chatSurfaceState = state.projectChatSurfaceState(viewModelScope)
    val workSurfaceState = state.projectWorkSurfaceState(viewModelScope)
    val activeModelProfile = runtime.model.activeProfile
    val workState = state.projectWorkState(viewModelScope)
    private val _gallery = MutableStateFlow<List<PersonaGalleryEntry>>(emptyList())
    val gallery = _gallery.asStateFlow()
    private val _transcriptHistory = MutableStateFlow(LocalTranscriptHistoryState())
    internal val transcriptHistory = _transcriptHistory.asStateFlow()
    private val conversationUiState = LocalConversationUiStateStore(appContext)
    val pinnedSessionIds = conversationUiState.pinnedSessionIds
    val sessionTitleOverrides = conversationUiState.sessionTitleOverrides
    private var transcriptHistoryCursor: LocalTranscriptPageCursor? = null
    private var transcriptHistoryInitializedSessionId: String? = null
    val personaPresets: List<PersonaPreset> = PersonaPresetCatalog.presets
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
        check(
            !snapshot.loading &&
                !snapshot.running &&
                snapshot.usageMode == LocalUsageMode.CHAT &&
                !snapshot.groupChat.enabled
        ) {
            "请在单人聊天空闲时保存人设与故事"
        }
        val completeHistory = withContext(Dispatchers.IO) {
            runtime.session.completeTranscriptForUi(snapshot.sessionId)
        }
        val archiveHistory = if (
            snapshot.galleryStoryId == null &&
            snapshot.gallerySaveSuppressedThrough > 0L
        ) {
            completeHistory.filter { message ->
                message.createdAt > snapshot.gallerySaveSuppressedThrough
            }
        } else {
            completeHistory
        }
        val outcome = withContext(Dispatchers.IO) {
            galleryStore.save(
                persona = snapshot.chatPersona,
                sourceSessionId = snapshot.sessionId,
                history = archiveHistory,
                chatState = snapshot.chatState.withLegacyConversationContext(snapshot.chatContext),
                notes = notes,
                existingId = existingId ?: snapshot.galleryId,
                existingStoryId = existingStoryId ?: snapshot.galleryStoryId,
                forceNewStory = forceNewStory,
            ).also { _gallery.value = galleryStore.list() }
        }
        runtime.chat.bindChatGallery(outcome.entry.id, outcome.storyId)
        outcome.entry
    }

    fun hasUnsavedCurrentPersona(): Boolean {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled
        ) return false
        val persona = snapshot.chatPersona
        if (!isMeaningfulGalleryPersona(persona)) return false

        val latestDialogueAt = snapshot.transcriptIndex.latestDialogueCreatedAt
        val suppressedThrough = snapshot.gallerySaveSuppressedThrough
        val hasDialogueAfterSuppression =
            snapshot.transcriptIndex.hasDialogue && latestDialogueAt > suppressedThrough

        if (suppressedThrough > 0L && !hasDialogueAfterSuppression) return false

        val bound = snapshot.galleryId?.let { id -> gallery.value.firstOrNull { it.id == id } }
        if (snapshot.galleryId != null) {
            if (bound == null) return true
            val story = bound.story(snapshot.galleryStoryId)
            val archivedThrough = story?.history
                ?.asReversed()
                ?.firstOrNull { message -> message.role == "user" || message.role == "assistant" }
                ?.createdAt
                ?: 0L
            val hasNewDialogue = hasDialogueAfterSuppression && latestDialogueAt > archivedThrough
            return hasNewDialogue || galleryEntryHasUnsavedChanges(
                entry = bound,
                storyId = snapshot.galleryStoryId,
                persona = persona,
                history = emptyList(),
                chatState = snapshot.chatState,
            )
        }

        if (hasDialogueAfterSuppression) return true
        return gallery.value.none { samePersonaIdentity(it.persona, persona) }
    }

    fun currentGalleryNeedsUpdate(): Boolean =
        !state.value.groupChat.enabled && state.value.galleryId != null

    fun currentGalleryHasUnsavedChanges(): Boolean = hasUnsavedCurrentPersona()

    fun selectGalleryPersonaForCurrentChat(id: String): Boolean {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.transcriptIndex.hasDialogue
        ) return false
        val entry = gallery.value.firstOrNull { it.id == id } ?: return false
        runtime.chat.selectChatPersona(entry.persona, galleryId = entry.id)
        return true
    }

    suspend fun createGalleryPersona(profile: PersonaProfile): Result<PersonaGalleryEntry> = runCatching {
        check(!state.value.loading && !state.value.running) { "请在聊天空闲时新建人物" }
        require(isMeaningfulGalleryPersona(profile)) { "请填写人物名称和至少一项人物设定" }
        val entry = withContext(Dispatchers.IO) {
            galleryStore.save(
                persona = profile,
                sourceSessionId = "",
                history = emptyList(),
                chatState = ChatCharacterState(),
                notes = "",
            ).entry.also { _gallery.value = galleryStore.list() }
        }
        // Preserve the old create-and-use flow for an empty one-to-one chat.
        // In an existing conversation or a group, creating a gallery card must not replace the cast.
        if (!state.value.groupChat.enabled) selectGalleryPersonaForCurrentChat(entry.id)
        entry
    }

    suspend fun autoFillNewPersona(description: String): Result<PersonaProfile> = runCatching {
        val snapshot = state.value
        check(!snapshot.loading && !snapshot.running && snapshot.configured) { "请先配置模型并等待当前回复结束" }
        personaAutoFillService.generate(
            model = snapshot.model, baseUrl = snapshot.baseUrl,
            profileId = snapshot.modelSelection.activeProfileId,
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
            val recent = withContext(Dispatchers.IO) {
                runtime.session.transcriptTailForUi(snapshot.sessionId, PERSONA_INSPECTION_RECENT_MESSAGES)
            }
            (archived + recent)
                .distinctBy { it.id.ifBlank { "${it.role}|${it.createdAt}|${it.content}" } }
        } else {
            archived
        }
        return runCatching {
            personaInspectionService.inspect(
                model = snapshot.model, baseUrl = snapshot.baseUrl,
                profileId = snapshot.modelSelection.activeProfileId,
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
                check(exists() || mkdirs()) { "无法创建人物立绘目录" }
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
                val saved = galleryStore.updatePortraitPath(id, target.absolutePath)
                    ?: error("图集条目已不存在")
                deleteManagedPortrait(previous.portraitPath, keepPath = target.absolutePath)
                saved
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        _gallery.value = withContext(Dispatchers.IO) { galleryStore.list() }
        updated
    }

    suspend fun removeGalleryPortrait(id: String): Result<PersonaGalleryEntry> = runCatching {
        val previous = gallery.value.firstOrNull { it.id == id }
            ?: error("图集条目已不存在")
        val updated = withContext(Dispatchers.IO) {
            galleryStore.updatePortraitPath(id, "")
                ?: error("图集条目已不存在")
        }
        deleteManagedPortrait(previous.portraitPath)
        _gallery.value = withContext(Dispatchers.IO) { galleryStore.list() }
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

    internal suspend fun exportGalleryPersona(
        id: String,
        format: PersonaTransferFormat,
    ): Result<PersonaTransferDocument> = runCatching {
        withContext(Dispatchers.IO) { galleryStore.exportPersonaDocument(id, format) }
    }

    internal suspend fun importGalleryPersona(
        bytes: ByteArray,
        fileName: String?,
        mimeType: String?,
    ): Result<PersonaGalleryEntry> = runCatching {
        withContext(Dispatchers.IO) {
            galleryStore.importPersonaDocument(bytes, fileName, mimeType)
                .also { _gallery.value = galleryStore.list() }
        }
    }

    suspend fun installPersonaPreset(id: String): Result<PersonaGalleryEntry> = runCatching {
        val preset = PersonaPresetCatalog.find(id) ?: error("人物预置不存在")
        val entry = withContext(Dispatchers.IO) {
            galleryStore.save(
                persona = preset.persona,
                sourceSessionId = "",
                history = emptyList(),
                chatState = ChatCharacterState(),
                notes = "",
            ).entry.also { _gallery.value = galleryStore.list() }
        }
        entry
    }

    suspend fun deleteGalleryEntry(id: String): Result<Unit> = runCatching {
        val portraitPath = gallery.value.firstOrNull { it.id == id }?.portraitPath.orEmpty()
        withContext(Dispatchers.IO) {
            check(galleryStore.delete(id)) { "图集条目已不存在" }
            _gallery.value = galleryStore.list()
        }
        deleteManagedPortrait(portraitPath)
        runtime.chat.removeGroupChatMemberByGalleryId(id)
        runtime.chat.clearChatGalleryBinding(expectedGalleryId = id)
    }

    suspend fun deleteGalleryStory(id: String, storyId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            check(galleryStore.deleteStory(id, storyId)) { "图集故事已不存在" }
            _gallery.value = galleryStore.list()
        }
        runtime.chat.clearChatGalleryBinding(expectedGalleryId = id, expectedStoryId = storyId, keepCharacter = true)
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
        runtime.session.createSession(
            mode = LocalConversationMode.INDEPENDENT,
            usageMode = LocalUsageMode.CHAT,
            galleryEntry = entry,
            galleryStoryId = storyId,
            freshGalleryStory = freshStory,
        )
        return true
    }

    internal suspend fun prepareTranscriptHistory(
        sessionId: String,
        force: Boolean = false,
    ) {
        if (sessionId.isBlank()) {
            transcriptHistoryCursor = null
            transcriptHistoryInitializedSessionId = null
            _transcriptHistory.value = LocalTranscriptHistoryState()
            return
        }
        val current = _transcriptHistory.value
        if (
            !force &&
            current.sessionId == sessionId &&
            transcriptHistoryInitializedSessionId == sessionId
        ) {
            return
        }

        transcriptHistoryCursor = null
        transcriptHistoryInitializedSessionId = null
        _transcriptHistory.value = LocalTranscriptHistoryState(
            sessionId = sessionId,
            loading = true,
        )
        try {
            val liveMessagesAtBootstrap = state.value
                .takeIf { snapshot -> snapshot.sessionId == sessionId }
                ?.messages
                .orEmpty()
            val firstPage = withContext(Dispatchers.IO) {
                runtime.session.transcriptPageForUi(
                    sessionId = sessionId,
                    cursor = null,
                    limit = transcriptHistoryBootstrapLimit(
                        liveMessageCount = liveMessagesAtBootstrap.size,
                        maxPageSize = LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES,
                    ),
                )
            }
            if (state.value.sessionId != sessionId) return
            val pageExtras = transcriptHistoryPageExtras(
                pageMessages = firstPage.messages,
                liveMessages = state.value.messages,
            )
            transcriptHistoryCursor = firstPage.nextCursor
            transcriptHistoryInitializedSessionId = sessionId
            _transcriptHistory.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                olderMessages = pageExtras,
                hasMore = firstPage.nextCursor != null,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (state.value.sessionId != sessionId) return
            transcriptHistoryCursor = null
            transcriptHistoryInitializedSessionId = null
            _transcriptHistory.value = LocalTranscriptHistoryState(
                sessionId = sessionId,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    internal suspend fun loadOlderTranscript(sessionId: String): Result<Int> {
        if (sessionId.isBlank()) return Result.success(0)
        if (
            transcriptHistoryInitializedSessionId != sessionId ||
            _transcriptHistory.value.sessionId != sessionId
        ) {
            prepareTranscriptHistory(sessionId)
        }
        if (
            transcriptHistoryCursor == null &&
            _transcriptHistory.value.olderMessages.isEmpty() &&
            state.value.sessionId == sessionId &&
            state.value.messages.size > LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES
        ) {
            prepareTranscriptHistory(sessionId, force = true)
        }

        val cursor = transcriptHistoryCursor ?: return Result.success(0)
        val current = _transcriptHistory.value
        if (current.loading) return Result.success(0)
        _transcriptHistory.value = current.copy(loading = true, error = null)

        return try {
            val page = withContext(Dispatchers.IO) {
                runtime.session.transcriptPageForUi(
                    sessionId = sessionId,
                    cursor = cursor,
                    limit = LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES,
                )
            }
            if (state.value.sessionId != sessionId) return Result.success(0)
            val latest = _transcriptHistory.value
            if (latest.sessionId != sessionId) return Result.success(0)
            val existingIds = latest.olderMessages.mapTo(hashSetOf(), LocalHarnessMessage::id)
            state.value.messages.mapTo(existingIds, LocalHarnessMessage::id)
            val newlyLoaded = page.messages.filterNot { message -> message.id in existingIds }
            transcriptHistoryCursor = page.nextCursor
            _transcriptHistory.value = latest.copy(
                olderMessages = newlyLoaded + latest.olderMessages,
                hasMore = page.nextCursor != null,
                loading = false,
                error = null,
            )
            Result.success(newlyLoaded.size)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (_transcriptHistory.value.sessionId == sessionId) {
                _transcriptHistory.value = _transcriptHistory.value.copy(
                    loading = false,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            Result.failure(error)
        }
    }

    fun configure(apiKey: String, model: String, baseUrl: String) = runtime.model.configure(apiKey, model, baseUrl)
    fun selectModel(model: String) = runtime.model.selectModel(model)
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()): LocalSendResult = runtime.chat.send(text, attachments)
    suspend fun generateReplySuggestions(): Boolean = runtime.chat.generateReplySuggestions()
    fun createGroupChatSession() = runtime.chat.createGroupChatSession()
    fun createSingleChatSession() = runtime.chat.createSingleChatSession()
    fun openGroupChatMode() = runtime.chat.switchChatMode(com.labteto.dshmobile.local.LocalChatMode.GROUP)
    fun leaveGroupChatMode() = runtime.chat.switchChatMode(com.labteto.dshmobile.local.LocalChatMode.SINGLE)
    fun configureGroupChatMembers(ids: List<String>): Boolean {
        val entriesById = gallery.value.associateBy(PersonaGalleryEntry::id)
        val entries = ids.distinct().mapNotNull(entriesById::get)
        if (entries.size != ids.distinct().size) return false
        return runtime.chat.configureGroupChatMembers(entries)
    }
    suspend fun setGroupChatAnnouncement(text: String): Result<Unit> =
        runtime.chat.setGroupChatAnnouncement(text)

    suspend fun generateGroupChatAnnouncement(direction: String): Result<String> = runCatching {
        val snapshot = state.value
        check(!snapshot.loading && !snapshot.running && snapshot.groupChat.enabled) { "请在群聊空闲时生成公告" }
        check(snapshot.configured) { "请先配置聊天模型" }
        check(snapshot.groupChat.members.size >= 2) { "请先添加至少两位群聊人物" }
        groupAnnouncementService.generate(
            model = snapshot.model, baseUrl = snapshot.baseUrl,
            profileId = snapshot.modelSelection.activeProfileId,
            members = snapshot.groupChat.members,
            direction = direction,
            current = snapshot.groupChat.announcement,
        )
    }
    suspend fun editAndResendUserMessage(messageId: String, text: String): LocalChatUserEditResult {
        val result = withContext(Dispatchers.IO) {
            runtime.chat.editAndResendUserMessage(messageId, text)
        }
        if (result == LocalChatUserEditResult.SENT) {
            refreshTranscriptHistoryAfterTimelineRewrite()
        }
        return result
    }

    suspend fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean {
        val selected = withContext(Dispatchers.IO) {
            runtime.chat.selectChatMessageVariant(messageId, targetIndex)
        }
        if (selected) refreshTranscriptHistoryAfterTimelineRewrite()
        return selected
    }

    private fun refreshTranscriptHistoryAfterTimelineRewrite() {
        val sessionId = state.value.sessionId
        transcriptHistoryCursor = null
        transcriptHistoryInitializedSessionId = null
        _transcriptHistory.value = LocalTranscriptHistoryState(sessionId = sessionId)
        viewModelScope.launch {
            prepareTranscriptHistory(sessionId, force = true)
        }
    }
    fun regenerateReply(messageId: String): Boolean = runtime.chat.regenerateReply(messageId)
    fun toggleSessionPinned(sessionId: String) = conversationUiState.toggleSessionPinned(sessionId)
    fun renameSession(sessionId: String, title: String): Boolean = conversationUiState.renameSession(sessionId, title)
    suspend fun deleteSessions(ids: Set<String>): Int = conversationUiState.deleteSessions(ids, runtime.session::deleteSessions)
    suspend fun importAttachment(uri: Uri): LocalImportedAttachment = runtime.session.importAttachment(uri)
    suspend fun workspaceFiles() = runtime.session.workspaceFilesForUi()
    suspend fun conversationFiles(sessionId: String) = runtime.session.conversationFilesForUi(sessionId)
    suspend fun previewWorkspaceFile(path: String) = runtime.session.previewWorkspaceFileForUi(path)
    fun backgroundJobOutput(jobId: String): String = runtime.work.backgroundJobOutputForUi(jobId)
    fun stopBackgroundJob(jobId: String): String = runtime.work.stopBackgroundJobForUi(jobId)
    fun approve(callId: String) = runtime.work.answerApproval(callId, true)
    fun deny(callId: String) = runtime.work.answerApproval(callId, false)
    fun enableAutoApproval() = runtime.work.enableAutoApproval()
    fun enableAutoApprovalForPending(callId: String) = runtime.work.enableAutoApprovalForPending(callId)
    fun enableDeviceApprovalLease(callId: String) = runtime.work.enableDeviceApprovalLease(callId)
    fun disableDeviceApprovalLease() = runtime.work.disableDeviceApprovalLease()
    fun disableAutoApproval() = runtime.work.disableAutoApproval()
    fun answerQuestion(callId: String, answer: String) = runtime.work.answerQuestion(callId, answer)
    fun cancelQuestion(callId: String) = runtime.work.cancelQuestion(callId)
    fun stop() = runtime.work.stop()
    fun newSession() = runtime.session.createSession(LocalConversationMode.INDEPENDENT)
    fun createSession(mode: LocalConversationMode) = runtime.session.createSession(mode)
    fun setPlanMode(enabled: Boolean) = runtime.work.setPlanMode(enabled)
    fun switchUsageMode(mode: LocalUsageMode) = runtime.session.switchUsageMode(mode)
    fun configureChatPersona(profile: PersonaProfile) = runtime.chat.configureChatPersona(profile)
    fun selectChatDirection(direction: String?) = runtime.chat.selectChatDirection(direction)
    fun undoChatPersonaCorrection(noticeId: Long, personaId: String, correction: String) =
        runtime.chat.undoChatPersonaCorrection(noticeId, personaId, correction)

    suspend fun autoFillChatPersona(description: String): Result<PersonaProfile> {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled
        ) {
            return Result.failure(IllegalStateException("persona_autofill_busy"))
        }
        if (!snapshot.configured) {
            return Result.failure(IllegalStateException("persona_autofill_unconfigured"))
        }
        return runCatching {
            val recentMessages = withContext(Dispatchers.IO) {
                runtime.session.transcriptTailForUi(snapshot.sessionId, PERSONA_AUTOFILL_RECENT_MESSAGES)
            }
            val generated = personaAutoFillService.generate(
                model = snapshot.model,
                baseUrl = snapshot.baseUrl,
                current = snapshot.chatPersona,
                recentMessages = recentMessages,
                description = description,
            )
            runtime.chat.syncDefaultChatPersona(generated)
        }
    }
    fun switchSession(sessionId: String) = runtime.session.switchSession(sessionId)
    fun clearCredential() = runtime.model.clearCredential()
}
