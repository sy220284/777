package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * Durable JSON boundary for character diary projections.
 *
 * Business rules stay in [ChatDiaryStore]; this class owns only cache, backup, corruption recovery
 * and atomic replacement.
 */
internal class ChatDiaryDocumentStore(private val root: File, private val json: Json) {
    init {
        root.mkdirs()
    }

    private val file = File(root, "diary.json")
    private val backup = File(root, "diary.json.bak")
    private var cached: ChatDiaryDocument? = null
    private var cachedStamp: Pair<Long, Long>? = null

    @Synchronized
    fun read(): ChatDiaryDocument {
        val currentStamp = stamp()
        cached?.takeIf { cachedStamp == currentStamp }?.let { return it }

        decode(file)?.let { document ->
            cached = document
            cachedStamp = currentStamp
            return document
        }

        val recovered = decode(backup)
        if (recovered != null) {
            if (file.isFile) quarantine(file, "primary")
            runCatching { backup.copyTo(file, overwrite = true) }
            cached = recovered
            cachedStamp = stamp()
            return recovered
        }

        val hadDurableData = file.isFile || backup.isFile
        if (file.isFile) quarantine(file, "primary")
        if (backup.isFile) quarantine(backup, "backup")
        if (hadDurableData) {
            val message = "人物日记主文件与备份均无法解析；损坏文件已隔离，未用空日记覆盖历史"
            AppLog.error("ChatDiaryStore", message)
            throw IllegalStateException(message)
        }
        return ChatDiaryDocument().also { document ->
            cached = document
            cachedStamp = stamp()
        }
    }

    @Synchronized
    fun write(document: ChatDiaryDocument) {
        root.mkdirs()
        val temp = File(root, "diary.json.tmp")
        temp.writeText(json.encodeToString(ChatDiaryDocument.serializer(), document))
        if (file.isFile && decode(file) != null) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!backup.isFile || decode(backup) == null) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        cached = document
        cachedStamp = stamp()
    }

    /**
     * Restores a failed higher-level transaction without leaving the rolled-back document in the
     * recovery backup. A normal [write] intentionally backs up the previous primary; rollback must
     * instead make both recovery copies agree with the restored state.
     */
    @Synchronized
    fun restore(document: ChatDiaryDocument) {
        write(document)
        file.copyTo(backup, overwrite = true)
        check(decode(backup) == document) { "人物日记回滚备份校验失败" }
        cached = document
        cachedStamp = stamp()
    }

    private fun decode(target: File): ChatDiaryDocument? {
        if (!target.isFile) return null
        return runCatching {
            json.decodeFromString(ChatDiaryDocument.serializer(), target.readText())
        }.getOrNull()
    }

    private fun quarantine(target: File, label: String) {
        if (!target.isFile) return
        val corrupt = File(root, "diary.$label.corrupt-${System.currentTimeMillis()}.json")
        val moved = runCatching { target.renameTo(corrupt) }.getOrDefault(false)
        if (!moved) {
            runCatching {
                target.copyTo(corrupt, overwrite = false)
                target.delete()
            }
        }
    }

    private fun stamp(): Pair<Long, Long> = file.lastModified() to file.length()
}
