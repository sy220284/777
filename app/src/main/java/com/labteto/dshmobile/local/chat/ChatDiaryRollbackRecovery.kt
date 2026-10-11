package com.labteto.dshmobile.local.chat

import java.io.File
import com.labteto.dshmobile.observability.AppLog
import kotlinx.serialization.json.Json

/** Keeps rollback recovery mechanics out of the bounded diary document store. */
internal class ChatDiaryRollbackRecovery(
    root: File,
    private val json: Json,
) {
    private val file = File(root, "diary.json")
    private val backup = File(root, "diary.json.bak")

    fun restore(document: ChatDiaryDocument, documents: ChatDiaryDocumentStore) {
        documents.write(document)
        val backupRestored = runCatching {
            file.copyTo(backup, overwrite = true)
            decodeBackup() == document
        }.onFailure { error ->
            AppLog.warn("ChatDiaryRecovery",
                "failure=true operation=restore_diary_backup cause_type=${error.javaClass.simpleName}")
        }.getOrDefault(false)
        if (!backupRestored) {
            AppLog.warn("ChatDiaryRecovery",
                "failure=true operation=verify_diary_backup status=backup_unavailable_or_invalid")
            val staleBackupRemoved = !backup.exists() || backup.delete()
            check(staleBackupRemoved) { "人物日记回滚备份无法恢复或清除" }
        }
    }

    private fun decodeBackup(): ChatDiaryDocument? =
        if (!backup.isFile) null else runCatching {
            json.decodeFromString(ChatDiaryDocument.serializer(), backup.readText())
        }.onFailure { error ->
            AppLog.warn("ChatDiaryRecovery",
                "failure=true operation=decode_diary_backup cause_type=${error.javaClass.simpleName}")
        }.getOrNull()
}
