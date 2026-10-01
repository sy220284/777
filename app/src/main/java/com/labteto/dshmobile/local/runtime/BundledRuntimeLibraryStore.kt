package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class BundledRuntimeLibraryEntry(
    val name: String,
    val sha256: String,
    val size: Long,
)

internal fun parseBundledRuntimeLibraryManifest(text: String): List<BundledRuntimeLibraryEntry> {
    val entries = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "运行库清单字段数量无效：" + line }
            val name = fields[0]
            val digest = fields[1]
            val size = requireNotNull(fields[2].toLongOrNull()) {
                "运行库大小无效：" + line
            }
            require(LIBRARY_NAME.matches(name)) { "运行库名称无效：" + name }
            require(SHA256.matches(digest)) { "运行库摘要无效：" + digest }
            require(size > 0L) { "运行库大小必须大于零：" + name }
            BundledRuntimeLibraryEntry(name, digest, size)
        }
        .toList()
    require(entries.isNotEmpty()) { "运行库清单为空" }
    require(entries.map(BundledRuntimeLibraryEntry::name).distinct().size == entries.size) {
        "运行库清单包含重复名称"
    }
    return entries
}

internal fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = digits[value ushr 4]
        chars[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(chars)
}

internal fun runtimeBlobMatches(
    file: File,
    entry: BundledRuntimeLibraryEntry,
): Boolean {
    if (!file.isFile || file.length() != entry.size) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toLowerHex() == entry.sha256
}

internal object BundledRuntimeLibraryStore {
    const val LAYOUT_VERSION = "shared-v1"

    private val runtimeNames = listOf("node", "python", "git")

    @Synchronized
    fun materialize(
        context: Context,
        runtime: String,
        abi: String,
        targetDir: File,
    ) {
        require(runtime in runtimeNames) { "未知内置运行时：" + runtime }
        verifyLayoutVersion(context)
        val entries = readManifest(context, runtime, abi)
        val sharedDir = File(context.noBackupFilesDir, "runtime/shared/$abi/lib")
        sharedDir.mkdirs()
        targetDir.mkdirs()

        var linked = 0
        var copied = 0
        entries.forEach { entry ->
            val canonical = File(sharedDir, entry.sha256)
            ensureCanonicalBlob(context, abi, entry, canonical)
            val destination = File(targetDir, entry.name)
            Files.deleteIfExists(destination.toPath())
            try {
                Files.createLink(destination.toPath(), canonical.toPath())
                linked += 1
            } catch (_: Exception) {
                Files.copy(canonical.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                copied += 1
            }
            destination.setReadable(true, true)
            destination.setWritable(true, true)
            destination.setExecutable(false, false)
        }

        pruneUnusedSharedBlobs(context, abi, sharedDir)
        AppLog.info(
            TAG,
            "materialized " + runtime + "/" + abi +
                ": aliases=" + entries.size + ", hardlinks=" + linked + ", copies=" + copied,
        )
    }

    private fun verifyLayoutVersion(context: Context) {
        val version = context.assets.open("runtime/shared/schema-version.txt")
            .bufferedReader()
            .use { it.readText().trim() }
        require(version == LAYOUT_VERSION) {
            "共享运行时布局版本不匹配：期望 " + LAYOUT_VERSION + "，实际 " + version
        }
    }

    private fun readManifest(
        context: Context,
        runtime: String,
        abi: String,
    ): List<BundledRuntimeLibraryEntry> {
        val asset = "runtime/$runtime/$abi/libraries.tsv"
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        return parseBundledRuntimeLibraryManifest(text)
    }

    private fun ensureCanonicalBlob(
        context: Context,
        abi: String,
        entry: BundledRuntimeLibraryEntry,
        canonical: File,
    ) {
        if (runtimeBlobMatches(canonical, entry)) return

        Files.deleteIfExists(canonical.toPath())
        val temporary = File(canonical.parentFile, "." + entry.sha256 + ".tmp")
        Files.deleteIfExists(temporary.toPath())
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            context.assets.open("runtime/shared/$abi/lib/" + entry.sha256).use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                    }
                }
            }
            require(written == entry.size) {
                "共享运行库大小校验失败：" + entry.name
            }
            require(digest.digest().toLowerHex() == entry.sha256) {
                "共享运行库摘要校验失败：" + entry.name
            }
            try {
                Files.move(
                    temporary.toPath(),
                    canonical.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    canonical.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            canonical.setReadable(true, true)
            canonical.setWritable(true, true)
            canonical.setExecutable(false, false)
        } finally {
            temporary.delete()
        }
    }

    private fun pruneUnusedSharedBlobs(
        context: Context,
        abi: String,
        sharedDir: File,
    ) {
        val referenced = runtimeNames
            .flatMap { runtime -> readManifest(context, runtime, abi) }
            .mapTo(mutableSetOf(), BundledRuntimeLibraryEntry::sha256)

        sharedDir.listFiles().orEmpty()
            .filter(File::isFile)
            .filterNot { it.name in referenced }
            .forEach(File::delete)
    }

    private const val TAG = "BundledRuntimeLibs"
}

private val LIBRARY_NAME = Regex("[A-Za-z0-9._+\\-]+")
private val SHA256 = Regex("[0-9a-f]{64}")
