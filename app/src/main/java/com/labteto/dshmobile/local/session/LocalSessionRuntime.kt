package com.labteto.dshmobile.local.session

import android.net.Uri
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Session/file capability boundary for the local UI. */
@Singleton
class LocalSessionRuntime @Inject internal constructor(
    private val lifecycle: LocalSessionLifecyclePort,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionFiles: LocalSessionFilesRuntime,
    private val sessionRead: LocalSessionReadRuntime,
) {
    internal val state: StateFlow<LocalHarnessState> = runtimeStateStore.state
    internal val streamingState: StateFlow<LocalHarnessStreamingState> = runtimeStateStore.streamingPreviewStore.state
    internal val sendFeedbackState: StateFlow<com.labteto.dshmobile.local.send.LocalSendFeedbackState> =
        runtimeStateStore.sendFeedbackState

    internal fun createSession(mode: LocalConversationMode) = createSession(mode, state.value.usageMode)
    internal fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec? = null,
    ) = lifecycle.createSession(
        mode = mode,
        usageMode = usageMode,
        domainSpec = domainSpec,
    )

    internal fun switchDomainMode(command: LocalSessionDomainModeCommand) =
        lifecycle.switchDomainMode(command)
    internal fun switchUsageMode(mode: LocalUsageMode) = lifecycle.switchUsageMode(mode)
    internal fun switchSession(sessionId: String) = lifecycle.switchSession(sessionId)
    internal suspend fun deleteSessions(ids: Set<String>): Int = lifecycle.deleteSessions(ids)
    internal suspend fun importAttachment(uri: Uri): LocalImportedAttachment = sessionFiles.importAttachment(uri)
    internal suspend fun workspaceFilesForUi(): List<LocalWorkspaceFile> = sessionFiles.workspaceFiles()
    internal suspend fun conversationFilesForUi(sessionId: String): LocalConversationFiles =
        sessionFiles.conversationFiles(sessionId)
    internal suspend fun previewWorkspaceFileForUi(path: String): LocalWorkspaceFilePreview =
        sessionFiles.previewWorkspaceFile(path)
    internal fun transcriptPageForUi(
        sessionId: String,
        cursor: LocalTranscriptPageCursor? = null,
        limit: Int = 200,
    ): LocalTranscriptPage = sessionRead.transcriptPage(sessionId, cursor, limit)
    internal fun transcriptTailForUi(sessionId: String, limit: Int) =
        sessionRead.transcriptTail(sessionId, limit)
    internal fun completeTranscriptForUi(sessionId: String) =
        sessionRead.completeTranscript(sessionId)
}
