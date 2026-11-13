package com.labteto.dshmobile.local.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal data class LocalPublishedDownload(val location: String, val uri: Uri, val currentVersion: Boolean)

/** One public-download publisher for Work files and app-owned generated media. */
internal class LocalDownloadsPublisher(context: Context, private val workspace: LocalWorkspace? = null) {
    private val resolver = context.contentResolver
    private val appFiles = context.filesDir.canonicalFile
    private val appCache = context.cacheDir.canonicalFile
    private val catalog = context.getSharedPreferences("shenyan_deliverables", Context.MODE_PRIVATE)
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    companion object {
        const val DIRECTORY = "神言神语"
        const val RELATIVE_DIRECTORY = "Download/$DIRECTORY"
        private val ROOT = "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY/"
    }

    /** An explanatory first-run file makes the public folder visible before any task completes. */
    fun ensureDirectory() {
        val explanation = ("神言神语生成的成果保存在此文件夹。\n" +
            "手机存储/Download/神言神语\n" +
            "任务执行文件仍保留在应用内部工作区。\n").toByteArray(Charsets.UTF_8)
        publishBytes("directory-v1", "成果保存说明.txt", explanation)
    }

    private fun publishBytes(id: String, name: String, bytes: ByteArray) {
        publishInternal(id, name, ROOT, bytes.size.toLong(), 0L) { output ->
            output.write(bytes)
            bytes.size.toLong()
        }
    }

    fun publish(relativePath: String): String {
        val owner = requireNotNull(workspace) { "工作区文件发布器未接入" }
        val item = owner.workspaceFileAt(relativePath)
            ?: throw FileNotFoundException("工作区成果不存在：$relativePath")
        val source = File(owner.path, item.path)
        val folder = item.path.takeIf { it.startsWith("交付成果/") }
            ?.removePrefix("交付成果/")?.substringBeforeLast('/', "").orEmpty()
        val destination = if (folder.isEmpty()) ROOT else "$ROOT$folder/"
        return publishInternal("work:${source.canonicalPath}", source.name, destination,
            source.length(), source.lastModified()) { output ->
            owner.exportFileForUi(item.path, output)
        }.location
    }

    /** Caller passes an app-owned generated file (Studio or inline media). */
    fun publishOwnedFile(source: File): String {
        val file = requireOwned(source)
        return publishInternal("media:${file.path}", file.name, ROOT,
            file.length(), file.lastModified()) { output ->
            file.inputStream().buffered().use { it.copyTo(output) }
        }.location
    }

    fun publishedWorkspace(relativePath: String): LocalPublishedDownload? {
        val owner = workspace ?: return null
        val item = owner.workspaceFileAt(relativePath) ?: return null
        val file = File(owner.path, item.path)
        val id = "work:${file.canonicalPath}"
        return cached(id)?.copy(currentVersion = sameSource(id, file.length(), file.lastModified()))
    }

    fun publishedOwnedFile(source: File): LocalPublishedDownload? {
        val file = requireOwned(source)
        val id = "media:${file.path}"
        return cached(id)?.copy(currentVersion = sameSource(id, file.length(), file.lastModified()))
    }

    private fun requireOwned(file: File): File = file.canonicalFile.also {
        require(it.isFile && (it.toPath().startsWith(appFiles.toPath()) ||
            it.toPath().startsWith(appCache.toPath()))) { "只能发布应用自身生成的文件" }
    }

    private fun key(id: String): String = hash(id.toByteArray(Charsets.UTF_8))
    private fun sameSource(id: String, size: Long, modified: Long): Boolean {
        val k = key(id)
        return catalog.getLong("$k.size", -1) == size &&
            catalog.getLong("$k.modified", -1) == modified
    }

    private fun cached(id: String): LocalPublishedDownload? {
        val k = key(id)
        val uri = catalog.getString("$k.uri", null)?.let(Uri::parse) ?: return null
        val path = catalog.getString("$k.path", null) ?: return null
        val exists = try {
            resolver.openFileDescriptor(uri, "r")?.use { true } == true
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        return if (exists) LocalPublishedDownload(path, uri, true) else null
    }

    /** Unchanged sources require zero data reads; changed sources are streamed exactly once. */
    @Synchronized private fun publishInternal(
        id: String, filename: String, directory: String, size: Long, modified: Long,
        copy: (OutputStream) -> Long,
    ): LocalPublishedDownload {
        val old = cached(id)
        if (old != null && sameSource(id, size, modified)) return old
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    filename.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("无法创建手机存储/Download/神言神语中的文件")
        var retained = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val written = resolver.openOutputStream(uri, "w")?.use { target ->
                DigestOutputStream(target, digest).use(copy)
            } ?: error("无法写入公共下载目录")
            check(written == size) { "成果在复制过程中发生变化，请重试保存" }
            val contentHash = digest.digest().joinToString("") { "%02x".format(it) }
            val k = key(id)
            if (old != null && catalog.getString("$k.sha256", null) == contentHash) {
                check(catalog.edit().putLong("$k.size", size).putLong("$k.modified", modified).commit()) {
                    "成果版本索引更新失败"
                }
                return old
            }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) > 0) { "无法发布已写入的下载成果" }
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: filename
            val path = directory + actual
            check(catalog.edit().putString("$k.uri", uri.toString())
                .putString("$k.path", path).putString("$k.sha256", contentHash)
                .putLong("$k.size", size).putLong("$k.modified", modified).commit()) {
                "成果已写入下载目录，但索引保存失败"
            }
            retained = true
            return LocalPublishedDownload(path, uri, true)
        } finally {
            if (!retained) resolver.delete(uri, null, null)
        }
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
