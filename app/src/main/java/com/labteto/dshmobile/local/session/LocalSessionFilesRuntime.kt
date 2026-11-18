package com.labteto.dshmobile.local.session

import android.content.Context
import android.net.Uri
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.LocalAttachmentImporter
import com.labteto.dshmobile.local.attachment.SqliteLocalAssetCatalog
import com.labteto.dshmobile.local.attachment.inspectImportedAttachmentFile
import com.labteto.dshmobile.uicontract.UiAttachmentInspectionReceipt
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourcePriority
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.LocalDownloadsPublisher
import com.labteto.dshmobile.local.files.LocalPublishedDownload
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryEntry
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryPage
import com.labteto.dshmobile.local.files.LocalWorkspaceSearchPage
import com.labteto.dshmobile.local.files.LocalWorkspaceFileChange
import com.labteto.dshmobile.local.files.watchWorkspaceFileChanges
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.put

/** Preserve human file names in Work while keeping all imported paths under one local folder. */
internal fun workImportSafeName(originalName: String): String =
    originalName.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .take(120).trim().trim('.').ifBlank { "手机文件" }

internal fun workImportAvailablePath(safeName: String, exists: (String) -> Boolean): String {
    val stem = safeName.substringBeforeLast('.', safeName)
    val extension = safeName.substringAfterLast('.', "").takeIf(String::isNotBlank)
        ?.let { ".$it" }.orEmpty()
    return (1..512).asSequence().map { index ->
        val filename = if (index == 1) safeName else "$stem ($index)$extension"
        "导入文件/$filename"
    }.firstOrNull { !exists(it) } ?: error("同名文件过多，请先整理工作区的导入文件")
}

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
    private val resourceScheduler = runtimeStateStore.resourceScheduler
    private val context = context
    private val eventLogs = eventLogs
    internal val workspace = LocalWorkspace(
        root = File(context.filesDir, "local-harness/workspace"),
        extraSearchPaths = bundledRuntimeManager::searchPaths,
        environmentProvider = bundledRuntimeManager::environment,
        boundary = LocalSandboxBoundary(
            workspaceRoot = File(context.filesDir, "local-harness/workspace"),
            userRoots = localSharedStorageRoots(),
        ),
    )

    private val downloadsPublisher by lazy { LocalDownloadsPublisher(context, workspace) }

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            try { downloadsPublisher.ensureDirectory() }
            catch (failure: Exception) { AppLog.failure("DownloadsPublisher", "initialize_folder", failure) }
        }
        // One-off, resumable backfill on process initialization, not a periodic task.
        // Production uploads still use the same catalog and Session mutation gate.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            val db = eventLogs.sqliteDatabase ?: return@launch
            try {
                val catalog = SqliteLocalAssetCatalog(db)
                val mediaDir = File(workspace.path, ".dsh/attachments")
                if (mediaDir.exists()) {
                    val published = requireNotNull(mediaDir.listFiles()) {
                        "无法读取历史附件目录，资源回填未完成"
                    }.sortedBy(File::getName)
                    var unclassified = 0
                    for (batch in published.chunked(128)) {
                        val result = resourceScheduler.withResource(
                            HarnessResourceKind.FILE_IO, "file:asset_backfill",
                            priority = HarnessResourcePriority.BACKGROUND,
                        ) { catalog.reconcilePublishedFiles(File(workspace.path), batch) }
                        unclassified += result.unclassified
                        yield()
                    }
                    if (unclassified > 0) {
                        AppLog.failure("AssetCatalog", "unclassified_legacy_assets",
                            IllegalStateException("尚有 $unclassified 个无内容哈希的旧附件，保留原文件等待单独核对"))
                    }
                }
                while (true) {
                    val indexed = resourceScheduler.withResource(
                        HarnessResourceKind.FILE_IO, "file:event_reference_backfill",
                        priority = HarnessResourcePriority.BACKGROUND,
                    ) { catalog.reconcileCommittedEvents() }
                    if (indexed == 0) break
                    yield()
                }
            } catch (failure: Exception) {
                // Keep all files and the last durable cursor; next process start resumes.
                AppLog.failure("AssetCatalog", "reconcile", failure)
            }
        }
        runCatching { LocalWorkspaceToolGuide.ensure(context, File(workspace.path)) }
            .onFailure { AppLog.failure("WorkspaceToolGuide", "create_readme", it) }
    }

    private val attachmentImporter = LocalAttachmentImporter(
        context = context,
        workspace = workspace,
        onPublished = { published ->
            // SQLite is the only metadata authority. The file already exists;
            // catalog failure is surfaced to the caller without deleting bytes.
            val db = requireNotNull(eventLogs.sqliteDatabase) {
                "SQLite 资源目录不可用，附件已保留在本地，需重试登记"
            }
            SqliteLocalAssetCatalog(db).register(published)
        },
    )

    internal val coordinator = LocalConversationFilesCoordinator(
        workspaceFilesProvider = workspace::files,
        workspaceFileAt = workspace::workspaceFileAt,
        previewWorkspaceFile = workspace::preview,
        eventLogFor = eventLogs::get,
        currentSessionId = { runtimeStateStore.currentSessionId },
        currentEventLog = { eventLogs.get(runtimeStateStore.currentSessionId) },
    )

    internal suspend fun importAttachment(uri: Uri): LocalImportedAttachment =
        withContext(Dispatchers.IO) { resourceScheduler.withResource(
            HarnessResourceKind.FILE_IO, "file:import", priority = HarnessResourcePriority.INTERACTIVE
        ) {
            if (context.contentResolver.getType(uri)?.startsWith("image/") == true) {
                resourceScheduler.withResource(HarnessResourceKind.MEDIA_DECODE, "file:import_image", priority = HarnessResourcePriority.INTERACTIVE) {
                    attachmentImporter.import(uri)
                }
            } else attachmentImporter.import(uri)
        } }

    private val workFileImportLock = Any()

    override suspend fun importWorkspaceFile(uri: Uri): String {
        require(com.labteto.dshmobile.local.isSupportedPickerAttachmentUri(uri.scheme, uri.authority)) {
            "只能导入 Android 文件选择器授权的文档"
        }
        // A Work file enters its ordinary visible workspace directly. The shared streaming
        // writer handles atomic commit, bounds RAM use and avoids duplicating Chat attachments.
        return withContext(Dispatchers.IO) {
            resourceScheduler.withResource(
                HarnessResourceKind.FILE_IO, "file:import_work", priority = HarnessResourcePriority.INTERACTIVE,
            ) {
                val resolver = context.contentResolver
                val originalName = resolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: error("所选手机文件没有名称")
                val safeName = workImportSafeName(originalName)
                resolver.openInputStream(uri)?.use { input ->
                    synchronized(workFileImportLock) {
                        val selected = workImportAvailablePath(safeName) {
                            workspace.workspaceFileAt(it) != null
                        }
                        workspace.writeFromStream(selected, input)
                        selected
                    }
                } ?: error("无法读取系统授权的手机文件")
            }
        }
    }

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

    override suspend fun workspaceDirectory(path: String): List<LocalWorkspaceDirectoryEntry> =
        withContext(Dispatchers.IO) { workspace.directoryEntries(path) }

    override suspend fun workspaceDirectoryPage(path: String, offset: Int): LocalWorkspaceDirectoryPage =
        withContext(Dispatchers.IO) { workspace.directoryPage(path, offset) }

    override suspend fun searchWorkspaceFiles(query: String): List<LocalWorkspaceFile> =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { workspace.searchWorkspaceFileNames(query) }

    override suspend fun searchSidebarWorkspacePage(query: String, offset: Int): LocalWorkspaceSearchPage =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { workspace.searchSidebarWorkspacePage(query, offset) }

    override suspend fun searchWorkspacePage(query: String, offset: Int): LocalWorkspaceSearchPage =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { workspace.searchWorkspacePage(query, offset) }

    override fun observeWorkspaceFiles(paths: List<String>): kotlinx.coroutines.flow.Flow<LocalWorkspaceFileChange> =
        watchWorkspaceFileChanges(File(workspace.path), paths)

    override suspend fun stopWorkspaceSearch(query: String): Boolean =
        withContext(Dispatchers.IO) { workspace.stopWorkspaceSearch(query) }

    override suspend fun conversationFiles(sessionId: String): LocalConversationFiles =
        withContext(Dispatchers.IO) { coordinator.conversationFiles(sessionId) }

    override suspend fun previewWorkspaceFile(path: String): LocalWorkspaceFilePreview =
        withContext(Dispatchers.IO) { resourceScheduler.withResource(
            HarnessResourceKind.FILE_IO, "file:preview", priority = HarnessResourcePriority.INTERACTIVE
        ) { coordinator.preview(path) } }

    override suspend fun previewWorkspaceFilePage(path: String, offset: Long): LocalWorkspaceFilePreview =
        withContext(Dispatchers.IO) { resourceScheduler.withResource(
            HarnessResourceKind.FILE_IO, "file:preview_page", priority = HarnessResourcePriority.INTERACTIVE
        ) { workspace.preview(path, offset = offset) } }

    internal suspend fun publishedWorkspaceFile(path: String): LocalPublishedDownload? =
        withContext(Dispatchers.IO) { downloadsPublisher.publishedWorkspace(path) }

    override suspend fun publishedWorkspaceSnapshot(path: String): com.labteto.dshmobile.uicontract.UiPublishedDownload? =
        publishedWorkspaceFile(path)?.let { item ->
            com.labteto.dshmobile.uicontract.UiPublishedDownload(
                location = item.location, uri = item.uri, currentVersion = item.currentVersion,
            )
        }

    override suspend fun saveWorkspaceFileToDownloads(path: String): String =
        withContext(Dispatchers.IO) {
            val units = com.labteto.dshmobile.harness.resource.harnessFileIoUnitsForBytes(
                workspace.fileSizeForResourceBudget(path))
            resourceScheduler.withResource(HarnessResourceKind.FILE_IO, "file:publish_downloads",
                units = units, priority = HarnessResourcePriority.BACKGROUND) {
                downloadsPublisher.publish(path)
            }
        }

    /** The durable event makes automatic publication and its exact user-visible location auditable. */
    internal suspend fun publishVerifiedDeliverables(
        sessionId: String,
        paths: List<String>,
        deliverablesDirectory: String? = null,
    ): List<String> {
        val finalPaths = withContext(Dispatchers.IO) {
            val root = java.nio.file.Paths.get(workspace.path)
            val folderFiles = deliverablesDirectory?.let { subfolder ->
                require(subfolder.startsWith("交付成果/") &&
                    subfolder.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "交付成果目录无效"
                }
                val directory = root.resolve(subfolder)
                if (!java.nio.file.Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    emptyList()
                } else {
                    java.nio.file.Files.walk(directory, 8).use { entries ->
                        val visited = entries.limit(2001).collect(java.util.stream.Collectors.toList())
                        require(visited.size <= 2000) {
                            "交付目录文件过多，请在工作区选择需要发布的文件"
                        }
                        val files = visited.filter {
                            java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        }.map { root.relativize(it).toString().replace('\\', '/') }
                            .filter { path ->
                                val name = path.substringAfterLast('/')
                                !name.startsWith('.') && !name.endsWith('~') &&
                                    !name.endsWith(".tmp", true) && !name.endsWith(".part", true) &&
                                    !name.endsWith(".bak", true) &&
                                    path.split('/').none { it.startsWith('.') } &&
                                    workspace.workspaceFileAt(path) != null
                            }
                        require(files.size <= 512) {
                            "本次交付文件超过 512 项，请在工作区批量选择后保存"
                        }
                        files
                    }
                }
            }.orEmpty()
            (paths + folderFiles).distinct()
        }
        val locations = ArrayList<String>()
        for (path in finalPaths) {
            val previous = downloadsPublisher.publishedWorkspace(path)
            val location = try {
                saveWorkspaceFileToDownloads(path)
            } catch (error: Exception) {
                eventLogs.get(sessionId).append("artifact/publication-failed",
                    kotlinx.serialization.json.buildJsonObject {
                        put("path", path)
                        put("reason", error.message ?: error::class.java.simpleName)
                    })
                throw error
            }
            if (previous?.currentVersion != true || previous.location != location) {
                eventLogs.get(sessionId).append("artifact/published",
                    kotlinx.serialization.json.buildJsonObject {
                        put("path", path)
                        put("location", location)
                    })
            }
            locations += location
        }
        return locations
    }

    override suspend fun saveWorkHandoffDocument(sourceSessionId: String, document: String): String =
        withContext(Dispatchers.IO) {
            require(sourceSessionId.isNotBlank() && document.isNotBlank()) { "交接原文为空" }
            val parsed = kotlinx.serialization.json.Json.decodeFromString(
                WorkHandoffDocument.serializer(), document,
            )
            require(parsed.version == 1 && parsed.sourceSessionId == sourceSessionId &&
                parsed.messages.isNotEmpty() && parsed.messages.all {
                    it.messageId.isNotBlank() && it.role in setOf("user", "assistant")
                }) { "交接原文与来源会话不匹配" }
            val checksum = java.security.MessageDigest.getInstance("SHA-256")
                .digest(document.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val path = "工作交接/所选消息全文-$checksum.json"
            val expected = "sha256:$checksum"
            val existing = workspace.fileVersion(path)
            if (existing.revision == "absent") {
                val saved = workspace.writeIfVersion(path, "absent", document)
                check(saved.revision == expected) { "交接原文保存后校验失败" }
            } else {
                check(existing.revision == expected) { "交接原文文件已被更改，请重新保存" }
            }
            path
        }

    override suspend fun exportWorkspaceArchive(directory: String, destination: android.net.Uri): Int =
        withContext(Dispatchers.IO) {
            require(destination.scheme == "content") { "需要选择一个文件保存位置" }
            val staged = File.createTempFile("workspace-directory-", ".zip", context.cacheDir)
            try {
                resourceScheduler.withResource(HarnessResourceKind.FILE_IO, "file:export_archive",
                    priority = HarnessResourcePriority.BACKGROUND) {
                    kotlinx.coroutines.runInterruptible {
                        val count = staged.outputStream().buffered().use { workspace.exportDirectoryArchiveForUi(directory, it) }
                        context.contentResolver.openOutputStream(destination, "wt")?.buffered()?.use { output ->
                            staged.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    output.write(buffer, 0, size)
                                }
                            }
                        } ?: error("无法打开压缩包保存位置")
                        count
                    }
                }
            } finally {
                staged.delete()
            }
        }

    override suspend fun exportWorkspaceFile(path: String, output: java.io.OutputStream): Long =
        withContext(Dispatchers.IO) {
            val units = com.labteto.dshmobile.harness.resource.harnessFileIoUnitsForBytes(
                workspace.fileSizeForResourceBudget(path))
            resourceScheduler.withResource(HarnessResourceKind.FILE_IO, "file:export", units = units,
                priority = HarnessResourcePriority.BACKGROUND) {
                workspace.exportFileForUi(path, output)
            }
        }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalFilesUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun files(implementation: LocalSessionFilesRuntime): com.labteto.dshmobile.uicontract.FilesUiPort
}
