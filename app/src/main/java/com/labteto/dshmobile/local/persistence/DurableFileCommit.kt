package com.labteto.dshmobile.local.persistence

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** A commit succeeds only after file contents and the containing directory entry are durable. */
internal object DurableFileCommit {
    fun replace(target: File, bytes: ByteArray) {
        val root = requireNotNull(target.parentFile)
        check(root.isDirectory || root.mkdirs()) { "无法创建持久数据目录：${root.path}" }
        val temporary = File(root, target.name + ".commit.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        syncDirectory(root)
    }

    fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    fun delete(target: File) {
        if (!target.exists()) return
        check(target.delete()) { "无法清理持久数据状态：${target.path}" }
        syncDirectory(requireNotNull(target.parentFile))
    }
}

internal enum class SnapshotCommitStage { PRIMARY_DURABLE, BACKUP_DURABLE, JOURNAL_CLEARED }

/** Domain reducers must make replay idempotent; the WAL stays intact until both bases are durable. */
internal fun commitSnapshotAndClearJournal(
    file: File,
    backup: File,
    journal: File,
    encoded: String,
    validate: (String) -> Boolean,
    afterStage: (SnapshotCommitStage) -> Unit = {},
) {
    require(validate(encoded)) { "拒绝提交无法解析的快照" }
    DurableFileCommit.replace(file, encoded.toByteArray())
    check(validate(file.readText())) { "持久快照写入后校验失败" }
    afterStage(SnapshotCommitStage.PRIMARY_DURABLE)
    DurableFileCommit.replace(backup, encoded.toByteArray())
    afterStage(SnapshotCommitStage.BACKUP_DURABLE)
    FileOutputStream(journal, false).use { it.fd.sync() }
    DurableFileCommit.syncDirectory(requireNotNull(journal.parentFile))
    afterStage(SnapshotCommitStage.JOURNAL_CLEARED)
}
