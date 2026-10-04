package com.labteto.dshmobile.local.session

import android.net.Uri
import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalConversationFiles
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalHarnessStreamingState
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalTranscriptPage
import com.labteto.dshmobile.local.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalWorkspaceFile
import com.labteto.dshmobile.local.LocalWorkspaceFilePreview
import com.labteto.dshmobile.local.MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
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

    internal fun createSession(mode: LocalConversationMode) = engine.createSession(mode)
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

    internal fun createGroupChatSession(entries: List<PersonaGalleryEntry>): Boolean {
        val selected = entries
            .distinctBy(PersonaGalleryEntry::id)
            .take(MAX_GROUP_CHAT_MEMBERS)
        if (selected.size !in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS) return false
        val snapshot = state.value
        if (snapshot.loading || snapshot.kernel.running) return false
        return createSession(
            mode = LocalConversationMode.INDEPENDENT,
            usageMode = LocalUsageMode.CHAT,
            chatMode = LocalChatMode.GROUP,
            groupEntries = selected,
        )
    }

    internal fun createSingleChatSession(): Boolean = createSession(
        mode = LocalConversationMode.INDEPENDENT,
        usageMode = LocalUsageMode.CHAT,
        chatMode = LocalChatMode.SINGLE,
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
