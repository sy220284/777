package com.labteto.dshmobile.local.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-selected public Documents tree. The private workspace stays the executable backing
 * filesystem (Shell/Git/Bun cannot operate on SAF content URIs). User files are synchronized
 * in both directions; internal databases, attachments and tool caches never leave private storage.
 *
 * The public tree is a user-controlled, persisted SAF grant: no MANAGE_EXTERNAL_STORAGE needed.
 */
internal class LocalPublicWorkspaceBridge(
    private val context: Context,
    private val workspace: File,
) {
    private val preferences = context.getSharedPreferences("public_workspace", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver
    private val privateRoot = workspace.canonicalFile

    data class SyncResult(
        val uploaded: Int,
        val downloaded: Int,
        val conflicts: Int,
        val conflictPaths: List<String>,
    ) {
        val summary: String get() = buildString {
            append("公共工作区同步完成：导出${uploaded}项，导入${downloaded}项，冲突${conflicts}项")
            if (conflictPaths.isNotEmpty()) {
                append("。未覆盖冲突文件：")
                append(conflictPaths.take(3).joinToString("、"))
                if (conflictPaths.size > 3) append("等${conflictPaths.size}项")
            }
        }
    }

    fun selectedFolder(): Uri? {
        val value = preferences.getString(TREE_KEY, null) ?: return null
        val tree = Uri.parse(value)
        return tree.takeIf { wanted ->
            resolver.persistedUriPermissions.any {
                it.uri == wanted && it.isReadPermission && it.isWritePermission
            }
        }
    }

    /** Called only after the system picker returned the user's explicit directory choice. */
    fun selectFolder(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val changes = preferences.edit()
        if (preferences.getString(TREE_KEY, null) != uri.toString()) {
            // Changing public folders invalidates every previous external-file fingerprint.
            preferences.all.keys.filter { it.startsWith("synced:") }.forEach { changes.remove(it) }
        }
        val previousTree = preferences.getString(TREE_KEY, null)
        changes.putString(TREE_KEY, uri.toString()).apply()
        if (previousTree != null && previousTree != uri.toString()) {
            val oldGrant = Uri.parse(previousTree)
            if (resolver.persistedUriPermissions.any { it.uri == oldGrant }) {
                resolver.releasePersistableUriPermission(
                    oldGrant, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    suspend fun synchronize(): SyncResult = withContext(Dispatchers.IO) {
        val uri = selectedFolder() ?: error("公共工作区未授权，请重新选择文件夹")
        require(privateRoot.isDirectory) { "本地工作区不存在" }
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val selected = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        val publicRoot = ensureDirectory(uri, selected, "777工作区")
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        val conflictPaths = mutableListOf<String>()
        var inspected = 0
        var bytes = 0L
        val changes = preferences.edit()
        fun checkBudget() {
            inspected++
            check(inspected <= MAX_FILES) { "文件数量超过单次同步上限，请按任务整理目录" }
        }
        fun fingerprint(file: File): String = "${file.length()}:${file.lastModified()}"
        fun fingerprint(entry: DocumentEntry): String = "${entry.size}:${entry.modified}"
        fun safeLocal(path: String): File {
            val f = File(privateRoot, path)
            require(f.canonicalFile.toPath().startsWith(privateRoot.toPath())) { "文件路径越界" }
            var cursor = privateRoot.toPath()
            for (segment in path.split('/')) {
                cursor = cursor.resolve(segment)
                require(!Files.isSymbolicLink(cursor)) { "不允许同步符号链接" }
            }
            return f
        }
        fun copyIntoPrivate(document: Uri, target: File) {
            target.parentFile?.mkdirs()
            val staging = File(target.parentFile, ".同步中-${UUID.randomUUID()}")
            try {
                resolver.openInputStream(document).use { input ->
                    requireNotNull(input) { "公共文件无法读取" }
                    staging.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                Files.move(staging.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                staging.delete()
            }
        }
        fun copyIntoPublic(source: File, destination: Uri) {
            resolver.openOutputStream(destination, "wt").use { output ->
                requireNotNull(output) { "公共文件无法写入" }
                source.inputStream().use { input ->
                    val copied = input.copyTo(output, 64 * 1024)
                    check(copied == source.length()) { "源文件在同步过程中发生变化" }
                }
            }
        }
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun digest(uri: Uri): String {
            val hash = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun record(path: String, file: File, entry: DocumentEntry) {
            changes.putString("synced:${path}",
                "${fingerprint(file)}|${fingerprint(entry)}|${digest(file)}")
        }
        fun updateFile(path: String, local: File, parent: Uri, remote: DocumentEntry?) {
            checkBudget()
            val value = remote?.let { fingerprint(it) }
            val previous = preferences.getString("synced:$path", null)?.split('|')
            val wasLocal = previous?.getOrNull(0)
            val wasRemote = previous?.getOrNull(1)
            val originalDigest = previous?.getOrNull(2)
            when {
                remote == null -> {
                    bytes += local.length()
                    check(bytes <= MAX_COPY_BYTES) { "本次同步数据量超过上限" }
                    val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", local.name)
                        ?: error("公共目录无法创建文件：$path")
                    copyIntoPublic(local, created)
                    val saved = childList(uri, parent)[local.name] ?: error("公共文件创建后不可见：$path")
                    record(path, local, saved)
                    pushed++
                }
                previous == null -> {
                    if (local.length() == remote.size && digest(local) == digest(remote.uri)) {
                        record(path, local, remote)
                    } else {
                        conflicts++
                        conflictPaths += path
                    }
                }
                fingerprint(local) == wasLocal && value == wasRemote -> Unit
                fingerprint(local) != wasLocal && value == wasRemote -> {
                    if (originalDigest == null || digest(remote.uri) != originalDigest) {
                        conflicts++
                        conflictPaths += path
                    } else {
                        bytes += local.length()
                        check(bytes <= MAX_COPY_BYTES)
                        copyIntoPublic(local, remote.uri)
                        val saved = childList(uri, parent)[local.name] ?: error("公共文件状态不可读：${path}")
                        record(path, local, saved)
                        pushed++
                    }
                }
                fingerprint(local) == wasLocal && value != wasRemote -> {
                    if (originalDigest == null || digest(local) != originalDigest) {
                        conflicts++
                        conflictPaths += path
                    } else {
                        bytes += remote.size.coerceAtLeast(0)
                        check(bytes <= MAX_COPY_BYTES)
                        copyIntoPrivate(remote.uri, local)
                        record(path, local, remote)
                        pulled++
                    }
                }
                else -> {
                    conflicts++
                    conflictPaths += path
                }
            }
        }
        fun walk(localDir: File, publicDir: Uri, prefix: String) {
            val publicEntries = childList(uri, publicDir)
            val localEntries = localDir.listFiles().orEmpty().filter { entry ->
                publishableWorkspaceName(entry.name) && !Files.isSymbolicLink(entry.toPath())
            }.associateBy { it.name }
            for ((name, local) in localEntries) {
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val remote = publicEntries[name]
                if (local.isDirectory) {
                    check(remote == null || remote.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        "公共目录与本地文件冲突：$path"
                    }
                    val folder = remote?.uri ?: ensureDirectory(uri, publicDir, name)
                    walk(local, folder, path)
                } else if (local.isFile) {
                    check(remote == null || remote.mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        "公共目录与本地文件冲突：$path"
                    }
                    updateFile(path, local, publicDir, remote)
                }
            }
            // Public-only files are imported, never silently discarded.
            for ((name, remote) in publicEntries) {
                if (name in localEntries || !publishableWorkspaceName(name)) continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val target = safeLocal(path)
                if (remote.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    target.mkdirs()
                    walk(target, remote.uri, path)
                } else {
                    checkBudget()
                    bytes += remote.size.coerceAtLeast(0)
                    check(bytes <= MAX_COPY_BYTES)
                    copyIntoPrivate(remote.uri, target)
                    record(path, target, remote)
                    pulled++
                }
            }
        }
        walk(privateRoot, publicRoot, "")
        changes.apply()
        SyncResult(pushed, pulled, conflicts, conflictPaths)
    }

    private data class DocumentEntry(val uri: Uri, val mime: String, val size: Long, val modified: Long)

    private fun childList(tree: Uri, parent: Uri): Map<String, DocumentEntry> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = linkedMapOf<String, DocumentEntry>()
        resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (!publishableWorkspaceName(name)) continue
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                out[name] = DocumentEntry(
                    uri = doc,
                    mime = cursor.getString(2),
                    size = if (cursor.isNull(3)) -1 else cursor.getLong(3),
                    modified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                )
            }
        } ?: error("公共目录不可读取")
        return out
    }

    private fun ensureDirectory(tree: Uri, parent: Uri, name: String): Uri {
        val existing = childList(tree, parent)[name]
        if (existing != null) {
            require(existing.mime == DocumentsContract.Document.MIME_TYPE_DIR) { "公共目录被文件占用：$name" }
            return existing.uri
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("无法创建公共工作区文件夹：$name")
    }

    private companion object {
        const val TREE_KEY = "public_workspace_tree"
        const val MAX_FILES = 20_000
        const val MAX_COPY_BYTES = 16L * 1024L * 1024L * 1024L
    }
}

/** Keep application internal files and executable infrastructure off the public mirror. */
internal fun publishableWorkspaceName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name &&
        !name.startsWith(".") && name !in setOf("node_modules", "build", "dist", "target",
        "runtime", "bun", "工具运行时", "缓存") && '\u0000' !in name
