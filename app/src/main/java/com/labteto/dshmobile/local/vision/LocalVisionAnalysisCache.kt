package com.labteto.dshmobile.local.vision

import java.io.File
import com.labteto.dshmobile.local.model.routeFingerprint
import com.labteto.dshmobile.local.model.localMediaCredentialSignature
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * App-private cache for completed workspace-image vision analyses.
 *
 * Keys bind the exact image request, credential, model route and task prompt. The cache
 * never invents a generic summary and never skips explicit tool approval; it only avoids paying
 * for the same already-approved analysis twice.
 */
internal class LocalVisionAnalysisCache(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(ttlMillis > 0L)
        require(maxBytes > 0L)
        require(maxEntries > 0)
        root.mkdirs()
    }

    fun get(imageDataUrl: String, route: LocalVisionRoute, prompt: String, credentialKey: String): String? {
        val entry = entryFor(imageDataUrl, route, prompt, credentialKey)
        if (!entry.isFile) return null
        if (clock() - entry.lastModified() > ttlMillis || entry.length() > MAX_ENTRY_BYTES) {
            entry.delete()
            return null
        }
        val value = runCatching { entry.readText() }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: run {
                entry.delete()
                return null
            }
        entry.setLastModified(clock())
        return value
    }

    fun put(imageDataUrl: String, route: LocalVisionRoute, prompt: String, result: String, credentialKey: String) {
        val bytes = result.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        root.mkdirs()
        val entry = entryFor(imageDataUrl, route, prompt, credentialKey)
        val temporary = File(root, ".${entry.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(entry)) {
                temporary.copyTo(entry, overwrite = true)
            }
            entry.setLastModified(clock())
            prune()
        } finally {
            temporary.delete()
        }
    }

    private fun entryFor(imageDataUrl: String, route: LocalVisionRoute, prompt: String, credentialKey: String): File {
        val keyMaterial = buildString {
            append(CACHE_VERSION).append('\u0000')
            append(contentIdentity(imageDataUrl)).append('\u0000')
            append(route.baseUrl.trim().trimEnd('/')).append('\u0000')
            append(route.model.trim()).append('\u0000')
            append(route.profile?.id.orEmpty()).append('\u0000')
            append(route.profile?.routeFingerprint().orEmpty()).append('\u0000')
            append(localMediaCredentialSignature(route.profile?.id.orEmpty(), credentialKey)).append('\u0000')
            append(prompt)
        }
        return File(root, sha256(keyMaterial.toByteArray(Charsets.UTF_8)) + ".txt")
    }

    private fun contentIdentity(imageDataUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Hash the immutable bytes actually submitted to inference. Reopening the workspace
        // path after inference could bind an old answer to a newly replaced image.
        var offset = 0
        while (offset < imageDataUrl.length) {
            val end = minOf(offset + 32 * 1024, imageDataUrl.length)
            digest.update(imageDataUrl.substring(offset, end).toByteArray(Charsets.UTF_8))
            offset = end
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun prune() {
        val files = root.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "txt" }
            .sortedByDescending(File::lastModified)
        val now = clock()
        var retainedBytes = 0L
        var retainedEntries = 0
        files.forEach { file ->
            val expired = now - file.lastModified() > ttlMillis
            val overEntries = retainedEntries >= maxEntries
            val overBytes = retainedBytes + file.length() > maxBytes
            if (expired || overEntries || overBytes) {
                file.delete()
            } else {
                retainedEntries += 1
                retainedBytes += file.length()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val CACHE_VERSION = "vision-cache-v3-account-route"
        const val DEFAULT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRIES = 256
        const val MAX_ENTRY_BYTES = 256 * 1024
    }
}
