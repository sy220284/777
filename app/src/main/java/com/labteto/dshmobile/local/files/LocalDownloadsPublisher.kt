package com.labteto.dshmobile.local.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Single Android-owned path for publishing completed workspace files to the phone's Downloads.
 * The app-private workspace stays the execution authority; public copies are user deliverables.
 */
internal class LocalDownloadsPublisher(
    context: Context,
    private val workspace: LocalWorkspace,
) {
    private val resolver = context.contentResolver
    private val published = context.getSharedPreferences("shenyan_deliverables", Context.MODE_PRIVATE)
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    companion object {
        const val DIRECTORY = "神言神语"
        const val RELATIVE_DIRECTORY = "Download/$DIRECTORY"
    }

    /**
     * Return the actual public path. A repeated publication of identical bytes reuses its
     * existing file; changing the source creates a new Android-managed version.
     */
    @Synchronized
    fun publish(relativePath: String): String {
        val item = workspace.workspaceFileAt(relativePath)
            ?: throw FileNotFoundException("工作区成果不存在：$relativePath")
        val source = File(workspace.path, item.path)
        val version = sourceSha256(source)
        val identity = sha256(item.path.toByteArray(Charsets.UTF_8))
        val storedVersion = published.getString("$identity.sha256", null)
        val storedUri = published.getString("$identity.uri", null)
        val storedPath = published.getString("$identity.path", null)
        if (storedVersion == version && storedUri != null && storedPath != null) {
            val existing = Uri.parse(storedUri)
            val available = try {
                resolver.openFileDescriptor(existing, "r")?.use { true } == true
            } catch (_: FileNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
            if (available) return storedPath
        }

        val fileName = source.name
        // Work's dedicated deliverable folders become readable project folders in Downloads.
        // An ad-hoc exported workspace file stays directly in 下载/神言神语.
        val projectRelative = item.path.takeIf { it.startsWith("交付成果/") }
            ?.removePrefix("交付成果/")
            ?.substringBeforeLast('/', "")
            .orEmpty()
        val relativeDirectory = buildString {
            append("${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY/")
            if (projectRelative.isNotBlank()) append(projectRelative).append('/')
        }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(collection, values)
            ?: error("手机下载目录无法创建成果文件")
        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                workspace.exportFileForUi(item.path, output)
            } ?: error("无法写入手机下载目录")
            check(sourceSha256(source) == version) {
                "成果文件在发布过程中发生变化，请完成写入后重试：$relativePath"
            }
            check(resolver.update(destination, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) > 0) { "无法发布下载成果" }
            val name = resolver.query(destination,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: fileName
            val savedPath = "$relativeDirectory$name".removeSuffix("/")
            check(published.edit()
                .putString("$identity.sha256", version)
                .putString("$identity.uri", destination.toString())
                .putString("$identity.path", savedPath)
                .commit()) { "成果已写入下载目录，但保存索引失败" }
            return savedPath
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun sourceSha256(source: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
