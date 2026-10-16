package com.labteto.dshmobile.local.work

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem

/** Current access metadata is cheap enough for a visible overview, unlike hashing every file. */
internal fun observeLocalWorkArtifact(root: File, item: LocalArtifactUiItem): LocalArtifactUiItem {
    if (item.category != "file") return item
    val observation = runCatching {
        val file = safeLocalWorkArtifactFile(root, item.reference) ?: return@runCatching null
        val attributes = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        "${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime()}"
    }.getOrNull()
    return item.copy(currentlyAvailable = observation != null, fileObservation = observation,
        versionNow = item.versionNow.takeIf { observation != null && observation == item.fileObservation })
}

internal data class LocalWorkFileVersion(val path: String, val sha256: String, val bytes: Long)

/** Capture an exact content version only for a known successful workspace tool result. */
internal fun localWorkArtifactFileVersion(root: File, toolName: String, content: String): LocalWorkFileVersion? {
    val path = workToolResultFilePath(toolName, content) ?: return null
    return runCatching {
        val file = safeLocalWorkArtifactFile(root, path) ?: return null
        LocalWorkFileVersion(path, fileSha256(file), file.length())
    }.getOrNull()
}

internal fun localWorkFileSha256(root: File, path: String): String? = runCatching {
    safeLocalWorkArtifactFile(root, path)?.let(::fileSha256)
}.getOrNull()

private fun safeLocalWorkArtifactFile(root: File, relative: String): File? {
    if (relative.isBlank() || relative.startsWith("/") || relative.contains('\\') ||
        relative.contains(':') || relative.split('/').any {
            it.isBlank() || it == "." || it == ".."
        }) return null
    // A canonical path alone silently follows symlinks within the workspace. Evidence must
    // attest to an actual workspace file rather than an alias which can be retargeted later.
    var current = root.canonicalFile.toPath()
    val canonicalRoot = current
    for (segment in relative.split('/')) {
        current = current.resolve(segment)
        if (Files.isSymbolicLink(current)) return null
    }
    if (!current.startsWith(canonicalRoot) ||
        !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) return null
    return current.toFile()
}

private fun fileSha256(file: File): String {
    val path = file.toPath()
    fun stamp(): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val before = stamp()
    require(before.isRegularFile) { "证据来源不是普通文件" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    val after = stamp()
    require(after.isRegularFile && before.fileKey() == after.fileKey() &&
        before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
        "证据文件在读取期间发生变化"
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
