package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
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

/** Work project metadata references existing workspace folders; file contents remain owned by Files. */
@Serializable
data class LocalWorkProject(
    val id: String,
    val name: String,
    /** Normalized, sandbox-relative directory; "." is the workspace root. */
    val root: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** A durable creation identity prevents duplicate projects after a retry. */
    val requestId: String,
)

data class LocalWorkProjectSnapshot(
    val projects: List<LocalWorkProject> = emptyList(),
    val sessionProjects: Map<String, String> = emptyMap(),
    val revision: Long = 0,
)

@Serializable
private data class LocalWorkProjectRecord(
    val version: Int = 1,
    val projects: List<LocalWorkProject> = emptyList(),
    val sessionProjects: Map<String, String> = emptyMap(),
    val revision: Long = 0,
)

/**
 * Small transactional Work-owned catalog. Projects own only identity, roots and session membership:
 * no second Session database, no duplicate file owner and no second Agent execution engine.
 */
internal class LocalWorkProjectCatalog(
    private val file: File,
    private val workspace: File,
    private val json: Json,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(load())
    val snapshots = mutable.asStateFlow()

    fun create(name: String, root: String, requestId: String = UUID.randomUUID().toString(),
        initialSessionId: String? = null): LocalWorkProject = synchronized(lock) {
        val title = checkedName(name)
        val normalized = checkedRoot(root)
        require(requestId.matches(ID_PATTERN)) { "项目创建编号无效" }
        initialSessionId?.let(::validateSession)
        val old = mutable.value
        old.projects.firstOrNull { it.requestId == requestId }?.let { original ->
            require(original.name == title && original.root == normalized &&
                (initialSessionId == null || old.sessionProjects[initialSessionId] == original.id)) {
                "相同项目创建编号关联了其他内容"
            }
            return@synchronized original
        }
        require(old.projects.size < MAX_PROJECTS) { "项目数量达到上限" }
        require(old.projects.none { it.name.equals(title, ignoreCase = true) }) { "项目名称已存在" }
        val now = System.currentTimeMillis()
        val item = LocalWorkProject(UUID.randomUUID().toString(), title, normalized, now, now, requestId)
        val memberships = if (initialSessionId == null) old.sessionProjects
            else old.sessionProjects + (initialSessionId to item.id)
        commit(old.copy(projects = old.projects + item, sessionProjects = memberships))
        item
    }

    /** Import is a project registration against a pre-existing authorized local folder. */
    fun importExisting(name: String, root: String, requestId: String = UUID.randomUUID().toString(),
        initialSessionId: String? = null): LocalWorkProject =
        create(name, root, requestId, initialSessionId)

    /** Update both user-visible fields as a single durable transaction. */
    fun update(id: String, name: String, root: String): Boolean = synchronized(lock) {
        val title = checkedName(name)
        val normalized = checkedRoot(root)
        val old = mutable.value
        val project = old.projects.firstOrNull { it.id == id } ?: error("项目不存在")
        if (project.name == title && project.root == normalized) return@synchronized false
        require(old.projects.none { it.id != id && it.name.equals(title, ignoreCase = true) }) {
            "项目名称已存在"
        }
        commit(old.copy(projects = old.projects.map {
            if (it.id == id) it.copy(
                name = title, root = normalized, updatedAtMs = System.currentTimeMillis(),
            ) else it
        }))
        true
    }

    fun rename(id: String, name: String): Boolean = synchronized(lock) {
        val title = checkedName(name)
        val old = mutable.value
        val project = old.projects.firstOrNull { it.id == id } ?: error("项目不存在")
        if (project.name == title) return@synchronized false
        require(old.projects.none { it.id != id && it.name.equals(title, ignoreCase = true) }) { "项目名称已存在" }
        commit(old.copy(projects = old.projects.map {
            if (it.id == id) it.copy(name = title, updatedAtMs = System.currentTimeMillis()) else it
        }))
        true
    }

    fun changeRoot(id: String, root: String): Boolean = synchronized(lock) {
        val normalized = checkedRoot(root)
        val old = mutable.value
        val project = old.projects.firstOrNull { it.id == id } ?: error("项目不存在")
        if (project.root == normalized) return@synchronized false
        commit(old.copy(projects = old.projects.map {
            if (it.id == id) it.copy(root = normalized, updatedAtMs = System.currentTimeMillis()) else it
        }))
        true
    }

    fun move(id: String, newIndex: Int): Boolean = synchronized(lock) {
        val old = mutable.value
        val from = old.projects.indexOfFirst { it.id == id }
        require(from >= 0 && newIndex in old.projects.indices) { "项目排序位置无效" }
        if (from == newIndex) return@synchronized false
        val reordered = old.projects.toMutableList()
        reordered.add(newIndex, reordered.removeAt(from))
        commit(old.copy(projects = reordered))
        true
    }

    /** Deletion detaches sessions. It never deletes user folders, Git history, or transcripts. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val old = mutable.value
        if (old.projects.none { it.id == id }) return@synchronized false
        commit(old.copy(
            projects = old.projects.filterNot { it.id == id },
            sessionProjects = old.sessionProjects.filterValues { it != id },
        ))
        true
    }

    /** Atomic session reassignment across projects, consistent with the existing Session owner. */
    fun assign(sessionIds: Set<String>, projectId: String?): Boolean = synchronized(lock) {
        require(sessionIds.isNotEmpty() && sessionIds.size <= MAX_MEMBERS) { "关联会话数量无效" }
        sessionIds.forEach(::validateSession)
        val old = mutable.value
        require(projectId == null || old.projects.any { it.id == projectId }) { "目标项目不存在" }
        val updated = old.sessionProjects.toMutableMap()
        sessionIds.forEach { id ->
            if (projectId == null) updated.remove(id) else updated[id] = projectId
        }
        require(updated.size <= MAX_MEMBERS) { "项目会话数量达到上限" }
        if (updated == old.sessionProjects) return@synchronized false
        commit(old.copy(sessionProjects = updated))
        true
    }

    /** Only called after Session owner confirms a successful durable deletion. */
    fun forgetDeletedSessions(ids: Set<String>): Boolean = synchronized(lock) {
        val old = mutable.value
        val updated = old.sessionProjects - ids
        if (updated == old.sessionProjects) return@synchronized false
        commit(old.copy(sessionProjects = updated))
        true
    }

    private fun commit(value: LocalWorkProjectSnapshot) {
        val next = value.copy(revision = mutable.value.revision + 1)
        val encoded = json.encodeToString(LocalWorkProjectRecord.serializer(),
            LocalWorkProjectRecord(projects = next.projects,
                sessionProjects = next.sessionProjects, revision = next.revision)
        ).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES) { "项目目录超过存储上限" }
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        val staged = File(file.parentFile, file.name + ".pending-" + UUID.randomUUID())
        try {
            FileOutputStream(staged).use { stream ->
                stream.write(encoded)
                stream.fd.sync()
            }
            Files.move(staged.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = next
        } finally {
            Files.deleteIfExists(staged.toPath())
        }
    }

    private fun load(): LocalWorkProjectSnapshot {
        if (!file.exists()) return LocalWorkProjectSnapshot()
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "项目目录文件无效" }
        val record = json.decodeFromString(LocalWorkProjectRecord.serializer(), file.readText(Charsets.UTF_8))
        require(record.version == 1 && record.revision >= 0 &&
            record.projects.size <= MAX_PROJECTS && record.sessionProjects.size <= MAX_MEMBERS) {
            "项目目录版本或容量异常"
        }
        val projectIds = record.projects.map { it.id }
        val requestIds = record.projects.map { it.requestId }
        require(projectIds.distinct().size == projectIds.size &&
            requestIds.distinct().size == requestIds.size &&
            (projectIds + requestIds).all { it.matches(ID_PATTERN) }) { "项目身份重复或无效" }
        record.projects.forEach {
            require(it.name == checkedName(it.name)) { "项目名称格式异常" }
            require(it.root == normalizeRootText(it.root)) { "项目目录路径格式异常" }
            require(it.createdAtMs >= 0 && it.updatedAtMs >= it.createdAtMs) { "项目时间记录无效" }
        }
        require(record.projects.map { it.name.lowercase() }.distinct().size == record.projects.size) {
            "项目名称重复"
        }
        record.sessionProjects.forEach { (id, projectId) ->
            validateSession(id)
            require(projectId in projectIds) { "项目包含无效会话归属" }
        }
        return LocalWorkProjectSnapshot(record.projects, record.sessionProjects, record.revision)
    }

    private fun checkedName(raw: String) = raw.trim().also {
        require(it.length in 1..64 && it.none(Char::isISOControl)) { "项目名称须为 1—64 字，不能含控制字符" }
    }

    private fun checkedRoot(raw: String): String {
        val normalized = normalizeRootText(raw)
        val allowed = workspace.canonicalFile
        val path = File(allowed, normalized).canonicalFile
        require((path == allowed || path.toPath().startsWith(allowed.toPath())) &&
            path.isDirectory) { "项目目录必须是当前工作区内已存在的文件夹" }
        return path.relativeTo(allowed).invariantSeparatorsPath.takeIf { it.isNotEmpty() } ?: "."
    }

    private fun normalizeRootText(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/')
        require(trimmed.isNotEmpty() && trimmed.length <= 400 &&
            !File(trimmed).isAbsolute && trimmed.none { it.isISOControl() } &&
            trimmed.split('/').none { it == ".." }) { "项目目录必须是工作区内的相对路径" }
        return trimmed.trimEnd('/').ifEmpty { "." }
    }

    private fun validateSession(id: String) {
        require(id.matches(SESSION_PATTERN)) { "会话编号无效" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-f0-9-]{36}")
        val SESSION_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        const val MAX_PROJECTS = 128
        const val MAX_MEMBERS = 20_000
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

@Singleton
class LocalWorkProjectStore @Inject internal constructor(
    @ApplicationContext context: Context,
    sessionStorage: LocalSessionStorageRuntime,
    json: Json,
) {
    private val catalog = LocalWorkProjectCatalog(
        File(context.filesDir, "local-harness/work-projects.v1.json"),
        File(sessionStorage.files.workspace.path),
        json,
    )
    val snapshots = catalog.snapshots
    fun create(name: String, root: String) = catalog.create(name, root)
    fun importExisting(name: String, root: String, sessionId: String?) =
        catalog.importExisting(name, root, initialSessionId = sessionId)
    fun rename(id: String, name: String) = catalog.rename(id, name)
    fun update(id: String, name: String, root: String) = catalog.update(id, name, root)
    fun changeRoot(id: String, root: String) = catalog.changeRoot(id, root)
    fun move(id: String, newIndex: Int) = catalog.move(id, newIndex)
    fun delete(id: String) = catalog.delete(id)
    fun assign(ids: Set<String>, id: String?) = catalog.assign(ids, id)
    fun forgetDeletedSessions(ids: Set<String>) = catalog.forgetDeletedSessions(ids)
}
