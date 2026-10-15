package com.labteto.dshmobile.local.work

import java.io.File
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
    val canonicalRoot = root.canonicalFile.toPath()
    val file = File(root, relative).canonicalFile
    return file.takeIf { it.toPath() != canonicalRoot &&
        it.toPath().startsWith(canonicalRoot) && it.isFile }
}

private fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
