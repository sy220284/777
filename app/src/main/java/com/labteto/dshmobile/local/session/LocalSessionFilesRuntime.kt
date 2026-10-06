package com.labteto.dshmobile.local.session

import android.content.Context
import android.net.Uri
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.LocalAttachmentImporter
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.MAX_ATTACHMENT_BYTES
import com.labteto.dshmobile.local.runtime.localSharedStorageRoots
import com.labteto.dshmobile.local.tools.LocalSandboxBoundary
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Session/File capability owning the app-private workspace views used by local conversations.
 *
 * One workspace instance is shared by runtime composition and Session UI reads so attachment import,
 * file projection and preview cannot drift into parallel filesystem owners.
 */
@Singleton
class LocalSessionFilesRuntime @Inject internal constructor(
    @ApplicationContext context: Context,
    bundledRuntimeManager: LocalBundledRuntimeManager,
    runtimeStateStore: LocalRuntimeStateStore,
    eventLogs: LocalSessionEventLogRegistry,
) {
    internal val workspace = LocalWorkspace(
        root = File(context.filesDir, "local-harness/workspace"),
        extraSearchPaths = bundledRuntimeManager::searchPaths,
        environmentProvider = bundledRuntimeManager::environment,
        boundary = LocalSandboxBoundary(
            workspaceRoot = File(context.filesDir, "local-harness/workspace"),
            userRoots = localSharedStorageRoots(),
        ),
    )

    private val attachmentImporter = LocalAttachmentImporter(
        context = context,
        workspace = workspace,
        maxAttachmentBytes = MAX_ATTACHMENT_BYTES,
    )

    internal val coordinator = LocalConversationFilesCoordinator(
        workspaceFilesProvider = workspace::files,
        previewWorkspaceFile = workspace::preview,
        eventLogFor = eventLogs::get,
        currentSessionId = { runtimeStateStore.currentSessionId },
        currentEventLog = { eventLogs.get(runtimeStateStore.currentSessionId) },
    )

    internal suspend fun importAttachment(uri: Uri): LocalImportedAttachment =
        withContext(Dispatchers.IO) { attachmentImporter.import(uri) }

    internal suspend fun workspaceFiles(): List<LocalWorkspaceFile> =
        withContext(Dispatchers.IO) { coordinator.workspaceFiles() }

    internal suspend fun conversationFiles(sessionId: String): LocalConversationFiles =
        withContext(Dispatchers.IO) { coordinator.conversationFiles(sessionId) }

    internal suspend fun previewWorkspaceFile(path: String): LocalWorkspaceFilePreview =
        withContext(Dispatchers.IO) { coordinator.preview(path) }
}
