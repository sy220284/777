package com.labteto.dshmobile.local.session

import android.content.Context
import android.net.Uri
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.LocalAttachmentImporter
import com.labteto.dshmobile.local.attachment.inspectImportedAttachmentFile
import com.labteto.dshmobile.uicontract.UiAttachmentInspectionReceipt
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.LocalWorkspaceToolGuide
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
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
) : com.labteto.dshmobile.uicontract.FilesUiPort {
    internal val workspace = LocalWorkspace(
        root = File(context.filesDir, "local-harness/workspace"),
        extraSearchPaths = bundledRuntimeManager::searchPaths,
        environmentProvider = bundledRuntimeManager::environment,
        boundary = LocalSandboxBoundary(
            workspaceRoot = File(context.filesDir, "local-harness/workspace"),
            userRoots = localSharedStorageRoots(),
        ),
    )

    init {
        runCatching { LocalWorkspaceToolGuide.ensure(context, File(workspace.path)) }
            .onFailure { AppLog.failure("WorkspaceToolGuide", "create_readme", it) }
    }

    private val attachmentImporter = LocalAttachmentImporter(
        context = context,
        workspace = workspace,
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

    internal suspend fun importToolImages(content: com.labteto.dshmobile.harness.tools.ToolRichContent?): List<LocalImportedAttachment> =
        withContext(Dispatchers.IO) {
            content?.images.orEmpty().map { image ->
                val mediaType = (image["mimeType"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: error("MCP 图片缺少 mimeType")
                val data = (image["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: error("MCP 图片缺少 data")
                require(data.length <= com.labteto.dshmobile.local.attachment.LocalMessageMediaPolicy.MAX_GENERATED_IMAGE_BASE64_CHARS) {
                    "MCP 图片超过附件容量"
                }
                attachmentImporter.importImageBytes(java.util.Base64.getDecoder().decode(data), mediaType)
            }
        }

    internal suspend fun inspectAttachment(item: LocalImportedAttachment): UiAttachmentInspectionReceipt =
        withContext(Dispatchers.IO) { inspectImportedAttachmentFile(File(workspace.path), item) }

    override suspend fun workspaceFiles(): List<LocalWorkspaceFile> =
        withContext(Dispatchers.IO) { coordinator.workspaceFiles() }

    override suspend fun conversationFiles(sessionId: String): LocalConversationFiles =
        withContext(Dispatchers.IO) { coordinator.conversationFiles(sessionId) }

    override suspend fun previewWorkspaceFile(path: String): LocalWorkspaceFilePreview =
        withContext(Dispatchers.IO) { coordinator.preview(path) }

    override suspend fun exportWorkspaceFile(path: String, output: java.io.OutputStream): Long =
        withContext(Dispatchers.IO) { workspace.exportFileForUi(path, output) }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalFilesUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun files(implementation: LocalSessionFilesRuntime): com.labteto.dshmobile.uicontract.FilesUiPort
}
