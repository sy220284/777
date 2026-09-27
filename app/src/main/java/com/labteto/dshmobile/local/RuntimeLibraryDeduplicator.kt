package com.labteto.dshmobile.local

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Share identical immutable runtime libraries by inode without changing their lookup paths. */
internal object RuntimeLibraryDeduplicator {
    @Synchronized
    fun deduplicate(runtimeRoot: File, abi: String) {
        val libraries = listOf("node", "git", "python").flatMap { runtime ->
            File(runtimeRoot, runtime).listFiles().orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { version ->
                    val abiRoot = File(version, abi)
                    if (!File(abiRoot, ".ready").isFile) return@mapNotNull null
                    File(abiRoot, "lib/libicudata.so").takeIf { it.isFile }
                }
        }
        if (libraries.size < 2) return
        val canonical = libraries.first()
        val checksum by lazy { canonical.sha256() }
        for (other in libraries.drop(1)) {
            if (Files.isSameFile(canonical.toPath(), other.toPath())) continue
            if (other.length() != canonical.length() || !other.sha256().contentEquals(checksum)) continue
            val temporary = File(other.parentFile, "${other.name}.dedup-tmp")
            try {
                Files.deleteIfExists(temporary.toPath())
                Files.createLink(temporary.toPath(), canonical.toPath())
                try {
                    Files.move(temporary.toPath(), other.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), other.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: Exception) {
                // Some filesystems disallow hard links. Preserve the original library in that case.
            } finally {
                temporary.delete()
            }
        }
    }

    private fun File.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
