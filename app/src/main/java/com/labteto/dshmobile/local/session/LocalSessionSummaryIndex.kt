package com.labteto.dshmobile.local

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LocalSessionSummaryRecord(
    val version: Int = 2,
    val id: String,
    val title: String,
    val updatedAt: Long,
    val usageMode: String,
    val chatMode: String,
    val groupMemberCount: Int = 0,
    val personaId: String? = null,
    val galleryId: String? = null,
    val blank: Boolean,
    val summaryPreview: String? = null,
    val projectId: String? = null,
    val lineageId: String? = null,
    val sourceModifiedAt: Long,
)

internal class LocalSessionSummaryIndex(
    sessionsRoot: File,
    private val json: Json,
) {
    private val root = File(sessionsRoot, ".summaries").apply { mkdirs() }
    private val lock = Any()

    fun read(id: String, sourceModifiedAt: Long): LocalSessionSummary? = synchronized(lock) {
        val record = readRecord(id) ?: return@synchronized null
        if (record.sourceModifiedAt != sourceModifiedAt) return@synchronized null
        record.toSummary()
    }

    fun write(summary: LocalSessionSummary, sourceModifiedAt: Long) = synchronized(lock) {
        require(sourceModifiedAt > 0L) { "会话摘要缺少有效源文件代际：${summary.id}" }
        val record = LocalSessionSummaryRecord(
            id = summary.id,
            title = summary.title,
            updatedAt = summary.updatedAt,
            usageMode = summary.usageMode.name,
            chatMode = summary.chatMode.name,
            groupMemberCount = summary.groupMemberCount,
            personaId = summary.personaId,
            galleryId = summary.galleryId,
            blank = summary.blank,
            summaryPreview = summary.summaryPreview,
            projectId = summary.projectId,
            lineageId = summary.lineageId,
            sourceModifiedAt = sourceModifiedAt,
        )
        val encoded = json.encodeToString(LocalSessionSummaryRecord.serializer(), record)
        val target = fileFor(summary.id)
        val temporary = File(root, target.name + ".tmp")
        temporary.writeText(encoded)
        moveReplace(temporary, target)
    }

    fun delete(id: String) = synchronized(lock) {
        val target = fileFor(id)
        target.delete()
        File(root, target.name + ".tmp").delete()
    }

    fun prune(validIds: Set<String>) = synchronized(lock) {
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUMMARY_SUFFIX) }
            .filter { it.name.removeSuffix(SUMMARY_SUFFIX) !in validIds }
            .forEach(File::delete)
    }

    private fun readRecord(id: String): LocalSessionSummaryRecord? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(LocalSessionSummaryRecord.serializer(), file.readText())
        }.getOrElse {
            val corrupt = File(root, file.name + ".corrupt-" + System.currentTimeMillis())
            runCatching { file.renameTo(corrupt) }
            null
        }
    }

    private fun LocalSessionSummaryRecord.toSummary(): LocalSessionSummary? {
        if (version != CURRENT_VERSION || id.isBlank()) return null
        val usage = runCatching { LocalUsageMode.valueOf(usageMode) }.getOrNull() ?: return null
        val mode = runCatching { LocalChatMode.valueOf(chatMode) }.getOrNull() ?: return null
        return LocalSessionSummary(
            id = id,
            title = title,
            updatedAt = updatedAt,
            usageMode = usage,
            chatMode = mode,
            groupMemberCount = groupMemberCount,
            personaId = personaId,
            galleryId = galleryId,
            blank = blank,
            summaryPreview = summaryPreview,
            projectId = projectId,
            lineageId = lineageId,
        )
    }

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "非法会话编号：$id" }
        return File(root, id + SUMMARY_SUFFIX)
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

    private companion object {
        const val CURRENT_VERSION = 2
        const val SUMMARY_SUFFIX = ".summary"
    }
}
