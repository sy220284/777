package com.labteto.dshmobile.local.session

import android.net.Uri
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Session/file capability boundary for the local UI. */
@Singleton
class LocalSessionRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    internal val state: StateFlow<LocalHarnessState> = runtimeStateStore.state
    internal val streamingState: StateFlow<LocalHarnessStreamingState> = runtimeStateStore.streamingPreviewStore.state
    internal val sendFeedbackState: StateFlow<com.labteto.dshmobile.local.send.LocalSendFeedbackState> =
        runtimeStateStore.sendFeedbackState

    internal fun createSession(mode: LocalConversationMode) = createSession(mode, state.value.usageMode)
    internal fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        galleryEntry: PersonaGalleryEntry? = null,
        galleryStoryId: String? = null,
        freshGalleryStory: Boolean = false,
        chatMode: LocalChatMode? = null,
        groupEntries: List<PersonaGalleryEntry> = emptyList(),
    ) = engine.createSession(
        mode = mode,
        usageMode = usageMode,
        galleryEntry = galleryEntry,
        galleryStoryId = galleryStoryId,
        freshGalleryStory = freshGalleryStory,
        chatMode = chatMode,
        groupEntries = groupEntries,
    )

    internal fun switchChatMode(mode: LocalChatMode) = engine.switchChatMode(mode)
    internal fun switchUsageMode(mode: LocalUsageMode) = engine.switchUsageMode(mode)
    internal fun switchSession(sessionId: String) = engine.switchSession(sessionId)
    internal suspend fun deleteSessions(ids: Set<String>): Int = engine.deleteSessions(ids)
    internal suspend fun importAttachment(uri: Uri): LocalImportedAttachment = engine.importAttachment(uri)
    internal suspend fun workspaceFilesForUi(): List<LocalWorkspaceFile> = engine.workspaceFilesForUi()
    internal suspend fun conversationFilesForUi(sessionId: String): LocalConversationFiles =
        engine.conversationFilesForUi(sessionId)
    internal suspend fun previewWorkspaceFileForUi(path: String): LocalWorkspaceFilePreview =
        engine.previewWorkspaceFileForUi(path)
    internal fun transcriptPageForUi(
        sessionId: String,
        cursor: LocalTranscriptPageCursor? = null,
        limit: Int = 200,
    ): LocalTranscriptPage = engine.transcriptPageForUi(sessionId, cursor, limit)
    internal fun transcriptTailForUi(sessionId: String, limit: Int) =
        engine.transcriptTailForUi(sessionId, limit)
    internal fun completeTranscriptForUi(sessionId: String) =
        engine.completeTranscriptForUi(sessionId)
}
