package com.labteto.dshmobile.local.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Conversation sections are presentation organization; Session/EventLog remains the only transcript owner. */
@Serializable
data class LocalSessionSection(val id: String, val title: String)

data class LocalSessionSectionSnapshot(
    val sections: List<LocalSessionSection> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
    val revision: Long = 0L,
)

@Serializable
private data class SectionRecord(
    val version: Int = 1,
    val sections: List<LocalSessionSection> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
    val revision: Long = 0L,
)

/**
 * Small app-private durable catalog. Writes finish on disk before publication; malformed or future-version
 * data is reported, never silently overwritten. Removing a section detaches its conversations, not the sessions.
 */
internal class LocalSessionSectionCatalog(
    private val file: File,
    private val json: Json,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(load())
    val snapshots = mutable.asStateFlow()

    fun create(title: String): LocalSessionSection = synchronized(lock) {
        val normalized = normalizedTitle(title)
        require(mutable.value.sections.size < MAX_SECTIONS) { "分区数量达到上限" }
        val section = LocalSessionSection(UUID.randomUUID().toString(), normalized)
        commit(mutable.value.copy(sections = mutable.value.sections + section))
        section
    }

    fun rename(sectionId: String, title: String): Boolean = synchronized(lock) {
        val normalized = normalizedTitle(title)
        val original = mutable.value
        require(original.sections.any { it.id == sectionId }) { "分区已不存在" }
        if (original.sections.first { it.id == sectionId }.title == normalized) return@synchronized false
        commit(original.copy(sections = original.sections.map {
            if (it.id == sectionId) it.copy(title = normalized) else it
        }))
        true
    }

    fun move(sectionId: String, targetIndex: Int): Boolean = synchronized(lock) {
        val original = mutable.value
        val index = original.sections.indexOfFirst { it.id == sectionId }
        require(index >= 0) { "分区已不存在" }
        require(targetIndex in original.sections.indices) { "目标分区位置无效" }
        if (index == targetIndex) return@synchronized false
        val reordered = original.sections.toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(targetIndex, moved)
        commit(original.copy(sections = reordered))
        true
    }

    fun delete(sectionId: String): Boolean = synchronized(lock) {
        val original = mutable.value
        if (original.sections.none { it.id == sectionId }) return@synchronized false
        commit(original.copy(
            sections = original.sections.filterNot { it.id == sectionId },
            assignments = original.assignments.filterValues { it != sectionId },
        ))
        true
    }

    /** One atomic batch: a partially moved selection must never be exposed to the drawer. */
    fun assign(sessionIds: Set<String>, sectionId: String?): Boolean = synchronized(lock) {
        require(sessionIds.size <= MAX_ASSIGNMENTS) { "一次移动的会话数量超过上限" }
        sessionIds.forEach(::validateSessionId)
        val original = mutable.value
        require(sectionId == null || original.sections.any { it.id == sectionId }) { "目标分区已不存在" }
        val next = original.assignments.toMutableMap()
        sessionIds.forEach { id -> if (sectionId == null) next.remove(id) else next[id] = sectionId }
        require(next.size <= MAX_ASSIGNMENTS) { "分区会话数量达到上限" }
        if (next == original.assignments) return@synchronized false
        commit(original.copy(assignments = next))
        true
    }

    /** Called only after the Session owner has confirmed durable deletion of these identities. */
    fun forgetDeletedSessions(sessionIds: Set<String>): Boolean = synchronized(lock) {
        if (sessionIds.isEmpty()) return@synchronized false
        val original = mutable.value
        val next = original.assignments - sessionIds
        if (next == original.assignments) return@synchronized false
        commit(original.copy(assignments = next))
        true
    }

    private fun commit(candidate: LocalSessionSectionSnapshot) {
        val next = candidate.copy(revision = mutable.value.revision + 1L)
        val encoded = json.encodeToString(
            SectionRecord.serializer(),
            SectionRecord(sections = next.sections, assignments = next.assignments, revision = next.revision),
        ).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES) { "分区目录超过存储上限" }
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        val temp = File(file.parentFile, file.name + ".pending-" + UUID.randomUUID())
        try {
            FileOutputStream(temp).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = next
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
    }

    private fun load(): LocalSessionSectionSnapshot {
        if (!file.exists()) return LocalSessionSectionSnapshot()
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "分区目录文件无效或超过上限" }
        val record = json.decodeFromString(SectionRecord.serializer(), file.readText(Charsets.UTF_8))
        require(record.version == 1) { "分区目录版本尚未受支持" }
        require(record.revision >= 0L && record.sections.size <= MAX_SECTIONS &&
            record.assignments.size <= MAX_ASSIGNMENTS) { "分区目录内容超出限制" }
        val ids = record.sections.map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it.matches(SECTION_ID) }) { "分区编号无效或重复" }
        record.sections.forEach { require(it.title == normalizedTitle(it.title)) { "分区名称无效" } }
        record.assignments.forEach { (sessionId, sectionId) ->
            validateSessionId(sessionId)
            require(sectionId in ids) { "分区目录包含悬空归属" }
        }
        return LocalSessionSectionSnapshot(record.sections, record.assignments, record.revision)
    }

    private fun normalizedTitle(title: String): String = title.trim().also {
        require(it.isNotEmpty() && it.length <= 60 && it.none { char -> char.isISOControl() }) {
            "分区名称长度须为 1—60 字且不能含控制字符"
        }
    }

    private fun validateSessionId(id: String) {
        require(id.matches(SESSION_ID)) { "会话编号格式无效" }
    }

    private companion object {
        val SECTION_ID = Regex("[a-f0-9-]{36}")
        val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
        const val MAX_SECTIONS = 128
        const val MAX_ASSIGNMENTS = 20_000
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

@Singleton
class LocalSessionSectionStore @Inject constructor(
    @ApplicationContext context: Context,
    json: Json,
) {
    private val catalog = LocalSessionSectionCatalog(
        File(context.filesDir, "local-harness/session-sections.v1.json"), json,
    )
    val snapshots = catalog.snapshots
    fun create(title: String) = catalog.create(title)
    fun rename(id: String, title: String) = catalog.rename(id, title)
    fun move(id: String, targetIndex: Int) = catalog.move(id, targetIndex)
    fun delete(id: String) = catalog.delete(id)
    fun assign(sessionIds: Set<String>, sectionId: String?) = catalog.assign(sessionIds, sectionId)
    fun forgetDeletedSessions(sessionIds: Set<String>) = catalog.forgetDeletedSessions(sessionIds)
}
