package com.labteto.dshmobile.local.persistence

import java.io.File

internal enum class RecoveringDocumentFailurePolicy { FAIL_CLOSED, RECREATE_DEFAULT }

/** Callers own decoding and domain semantics; quarantine never turns existing data into new data. */
internal class RecoveringDocumentFile(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val failurePolicy: RecoveringDocumentFailurePolicy = RecoveringDocumentFailurePolicy.FAIL_CLOSED,
) {
    private val root = requireNotNull(file.parentFile)
    private val backup = File(root, "${file.name}.bak")
    private val recoveryRequired = File(root, "${file.name}.recovery-required")

    @Synchronized
    fun <T> read(defaultValue: () -> T, decode: (String) -> T): T {
        decodeFile(file, decode)?.let {
            DurableFileCommit.delete(recoveryRequired)
            return it
        }
        decodeFile(backup, decode)?.let {
            if (file.isFile) quarantineCorrupt(file)
            DurableFileCommit.replace(file, backup.readBytes())
            DurableFileCommit.delete(recoveryRequired)
            return it
        }
        val hadData = file.exists() || backup.exists() || recoveryRequired.exists() || hasLegacyQuarantine()
        if (!hadData) return defaultValue()

        // Persist before moving either source: interruption cannot make the next read look new.
        if (!recoveryRequired.exists()) {
            DurableFileCommit.replace(recoveryRequired, "recovery-required\n".toByteArray())
        }
        if (file.isFile) quarantineCorrupt(file)
        if (backup.isFile) quarantineCorrupt(backup)
        if (failurePolicy == RecoveringDocumentFailurePolicy.RECREATE_DEFAULT) return defaultValue()
        error("持久数据已损坏且备份不可用；损坏数据已隔离，需恢复后才能继续：${file.path}")
    }

    @Synchronized
    fun write(serialized: String, validate: (String) -> Boolean) {
        require(validate(serialized)) { "拒绝写入无法解析的持久数据" }
        // Validate existing state even if this caller has not read it. FAIL_CLOSED blocks blind writes.
        read(defaultValue = { Unit }, decode = { check(validate(it)); Unit })
        commit(serialized, validate)
    }

    /** Explicit import/migration recovery from an independently validated durable source. */
    @Synchronized
    fun restoreFromRecoverySource(serialized: String, validate: (String) -> Boolean) {
        require(validate(serialized)) { "恢复来源无法解析" }
        commit(serialized, validate)
    }

    private fun commit(serialized: String, validate: (String) -> Boolean) {
        val current = if (file.isFile) file.readText() else null
        if (current != null && validate(current)) DurableFileCommit.replace(backup, current.toByteArray())
        DurableFileCommit.replace(file, serialized.toByteArray())
        val backupText = if (backup.isFile) backup.readText() else null
        if (backupText == null || !validate(backupText)) {
            DurableFileCommit.replace(backup, serialized.toByteArray())
        }
        DurableFileCommit.delete(recoveryRequired)
    }

    private fun <T> decodeFile(source: File, decode: (String) -> T): T? {
        if (!source.isFile) return null
        return runCatching { decode(source.readText()) }.getOrNull()
    }

    private fun hasLegacyQuarantine(): Boolean = root.listFiles().orEmpty().any {
        (it.name.startsWith(file.name + ".") || it.name.startsWith(file.nameWithoutExtension + ".")) &&
            it.name.contains(".corrupt-")
    }

    private fun quarantineCorrupt(target: File) {
        val corrupt = File.createTempFile(target.name + ".corrupt-${clock()}-", ".quarantine", root)
        DurableFileCommit.replace(corrupt, target.readBytes())
        DurableFileCommit.delete(target)
    }
}
