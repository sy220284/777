package com.labteto.dshmobile.local.chat

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small durable-file wrapper for chat persona documents.
 *
 * A malformed primary is quarantined instead of being silently treated as an empty document.
 * Writes keep a last-known-good backup and replace the primary atomically when the platform allows.
 */
internal class RecoveringChatDocumentFile(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root: File = requireNotNull(file.parentFile) {
        "聊天数据文件必须位于目录中：${file.path}"
    }
    private val backup = File(root, "${file.name}.bak")

    fun <T> read(defaultValue: () -> T, decode: (String) -> T): T {
        decodeFile(file, decode)?.let { return it }

        if (!file.isFile) {
            decodeFile(backup, decode)?.let { recovered ->
                restoreBackup()
                return recovered
            }
            return defaultValue()
        }

        quarantineCorruptPrimary()
        decodeFile(backup, decode)?.let { recovered ->
            restoreBackup()
            return recovered
        }
        return defaultValue()
    }

    fun write(serialized: String, validate: (String) -> Boolean) {
        root.mkdirs()
        require(validate(serialized)) { "拒绝写入无法解析的聊天数据" }

        val currentText = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        if (currentText != null && validate(currentText)) {
            file.copyTo(backup, overwrite = true)
        }

        val temporary = File(root, "${file.name}.tmp")
        temporary.writeText(serialized)
        moveReplace(temporary, file)

        val backupText = if (backup.isFile) runCatching { backup.readText() }.getOrNull() else null
        if (backupText == null || !validate(backupText)) {
            file.copyTo(backup, overwrite = true)
        }
    }

    private fun <T> decodeFile(source: File, decode: (String) -> T): T? {
        if (!source.isFile) return null
        return runCatching { decode(source.readText()) }.getOrNull()
    }

    private fun quarantineCorruptPrimary() {
        val corrupt = File(root, "${file.name}.corrupt-${clock()}")
        if (runCatching { file.renameTo(corrupt) }.getOrDefault(false)) return

        val copied = runCatching {
            file.copyTo(corrupt, overwrite = false)
            true
        }.getOrDefault(false)
        check(copied) { "人设数据损坏且无法隔离：${file.path}" }
        check(file.delete()) { "人设数据已备份但无法移除损坏主文件：${file.path}" }
    }

    private fun restoreBackup() {
        if (!backup.isFile) return
        root.mkdirs()
        val temporary = File(root, "${file.name}.restore.tmp")
        backup.copyTo(temporary, overwrite = true)
        moveReplace(temporary, file)
    }

    private fun moveReplace(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
