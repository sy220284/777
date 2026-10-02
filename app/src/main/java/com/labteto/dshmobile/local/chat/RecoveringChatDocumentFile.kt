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
internal enum class RecoveringDocumentFailurePolicy {
    FAIL_CLOSED,
    RECREATE_DEFAULT,
}

internal class RecoveringChatDocumentFile(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val failurePolicy: RecoveringDocumentFailurePolicy = RecoveringDocumentFailurePolicy.FAIL_CLOSED,
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
            if (backup.isFile) {
                return when (failurePolicy) {
                    RecoveringDocumentFailurePolicy.FAIL_CLOSED ->
                        throw IllegalStateException("持久数据备份已损坏，无法自动恢复：${backup.path}")
                    RecoveringDocumentFailurePolicy.RECREATE_DEFAULT -> {
                        quarantineCorrupt(backup)
                        defaultValue()
                    }
                }
            }
            return defaultValue()
        }

        quarantineCorrupt(file)
        decodeFile(backup, decode)?.let { recovered ->
            restoreBackup()
            return recovered
        }
        if (backup.isFile && failurePolicy == RecoveringDocumentFailurePolicy.RECREATE_DEFAULT) {
            quarantineCorrupt(backup)
        }
        return when (failurePolicy) {
            RecoveringDocumentFailurePolicy.FAIL_CLOSED -> throw IllegalStateException(
                "持久数据主文件已损坏且备份不可用；损坏主文件已隔离，未用空数据覆盖原内容",
            )
            RecoveringDocumentFailurePolicy.RECREATE_DEFAULT -> defaultValue()
        }
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

    private fun quarantineCorrupt(target: File) {
        if (!target.isFile) return
        val corrupt = File(root, "${target.name}.corrupt-${clock()}")
        if (runCatching { target.renameTo(corrupt) }.getOrDefault(false)) return

        val copied = runCatching {
            target.copyTo(corrupt, overwrite = false)
            true
        }.getOrDefault(false)
        check(copied) { "持久数据损坏且无法隔离：${target.path}" }
        check(target.delete()) { "持久数据已备份但无法移除损坏文件：${target.path}" }
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
