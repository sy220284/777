package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Replaces only a validated local skill package. The previous installed directory is kept
 * as an explicit, reversible revision rather than being silently overwritten.
 */
internal class LocalSkillPackageUpdater(private val workspace: LocalWorkspace) {
    private val systemRoot get() = File(workspace.path, ".dsh")
    private val skillsRoot get() = File(systemRoot, "skills")
    private val rollbackRoot get() = File(systemRoot, "skill-rollback")

    private fun validateId(id: String) {
        require(LOCAL_SKILL_ID_PATTERN.matches(id)) { "技能标识无效" }
    }

    private fun requireNoLinks(directory: File) {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "技能目录不存在或不安全"
        }
        Files.walk(directory.toPath()).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "技能目录包含符号链接，拒绝替换" }
                require(Files.isDirectory(path) || Files.isRegularFile(path)) {
                    "技能目录包含未知文件类型"
                }
            }
        }
    }

    private fun requireSafeTree(directory: File) {
        requireNoLinks(directory)
        val skill = File(directory, "SKILL.md")
        require(Files.isRegularFile(skill.toPath()) && !Files.isSymbolicLink(skill.toPath())) {
            "技能规则文件不存在"
        }
    }

    private fun move(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    fun canRollback(id: String): Boolean {
        validateId(id)
        val target = File(rollbackRoot, id)
        return !Files.isSymbolicLink(rollbackRoot.toPath()) &&
            !Files.isSymbolicLink(target.toPath()) &&
            target.isDirectory && File(target, "SKILL.md").isFile
    }

    fun replace(id: String, archive: ByteArray): String {
        validateId(id)
        require(!Files.isSymbolicLink(systemRoot.toPath()) &&
            !Files.isSymbolicLink(skillsRoot.toPath()) &&
            !Files.isSymbolicLink(rollbackRoot.toPath())) { "技能目录含不安全链接" }
        val active = File(skillsRoot, id)
        requireSafeTree(active)
        val scratch = Files.createTempDirectory(systemRoot.toPath(), ".skill-update-").toFile()
        try {
            // Use the existing installer as the sole ZIP and SKILL.md validation boundary.
            val stagedWorkspace = LocalWorkspace(scratch)
            val parsedId = LocalSkillPackageImporter(stagedWorkspace).install("update.zip", archive)
            require(parsedId == id) { "更新包的技能标识与原技能不一致" }
            val candidate = File(scratch, ".dsh/skills/$id")
            requireSafeTree(candidate)
            require(rollbackRoot.isDirectory || rollbackRoot.mkdirs()) {
                "无法创建技能回滚目录"
            }
            val previous = File(rollbackRoot, id)
            if (previous.exists()) requireSafeTree(previous)
            val displaced = File(systemRoot, ".previous-$id-${UUID.randomUUID()}")
            var parkedOldRevision = false
            var parkedActive = false
            try {
                if (previous.exists()) {
                    move(previous, displaced)
                    parkedOldRevision = true
                }
                move(active, previous)
                parkedActive = true
                move(candidate, active)
            } catch (failure: Exception) {
                if (parkedActive && !active.exists() && previous.exists()) move(previous, active)
                if (parkedOldRevision && !previous.exists() && displaced.exists()) move(displaced, previous)
                throw failure
            }
            if (displaced.exists()) {
                require(displaced.deleteRecursively()) { "旧版本清理失败，请检查技能存储" }
            }
            return id
        } finally {
            if (scratch.exists()) require(scratch.deleteRecursively()) {
                "技能更新临时文件清理失败"
            }
        }
    }


    /** Repair an interrupted two-directory swap before exposing skills after process restart. */
    fun recoverInterruptedTransactions() {
        if (!systemRoot.exists()) return
        require(!Files.isSymbolicLink(systemRoot.toPath()) &&
            !Files.isSymbolicLink(skillsRoot.toPath()) &&
            !Files.isSymbolicLink(rollbackRoot.toPath())) { "技能恢复目录含不安全链接" }
        require(skillsRoot.isDirectory || skillsRoot.mkdirs()) { "无法恢复技能目录" }
        require(rollbackRoot.isDirectory || rollbackRoot.mkdirs()) { "无法恢复技能历史目录" }

        val idSuffix = "([a-z][a-z0-9-]{1,47})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
        val parkedActivePattern = Regex("^\\.active-$idSuffix$")
        val parkedPreviousPattern = Regex("^\\.previous-$idSuffix$")
        val rootItems = systemRoot.listFiles().orEmpty()
        // An interrupted rollback parks current content before swapping directories.
        rootItems.forEach { parked ->
            val match = parkedActivePattern.matchEntire(parked.name) ?: return@forEach
            val id = match.groupValues[1]
            val active = File(skillsRoot, id)
            val previous = File(rollbackRoot, id)
            requireSafeTree(parked)
            when {
                !active.exists() -> move(parked, active)
                !previous.exists() -> move(parked, previous)
                else -> error("技能 $id 恢复时检测到重复的版本目录")
            }
        }
        // An interrupted update may have moved the former rollback revision aside.
        rootItems.forEach { parked ->
            val match = parkedPreviousPattern.matchEntire(parked.name) ?: return@forEach
            val id = match.groupValues[1]
            val active = File(skillsRoot, id)
            val previous = File(rollbackRoot, id)
            requireSafeTree(parked)
            when {
                !active.exists() && previous.exists() -> {
                    requireSafeTree(previous)
                    move(previous, active)
                    move(parked, previous)
                }
                !previous.exists() && active.exists() -> move(parked, previous)
                previous.exists() && active.exists() -> {
                    requireSafeTree(previous)
                    require(parked.deleteRecursively()) { "旧技能回滚记录清理失败" }
                }
                else -> error("技能 $id 恢复时缺少两个版本目录")
            }
        }
        // First-time updates have no older rollback revision parked aside.
        rollbackRoot.listFiles().orEmpty().forEach { previous ->
            if (!LOCAL_SKILL_ID_PATTERN.matches(previous.name)) return@forEach
            val active = File(skillsRoot, previous.name)
            if (!active.exists()) {
                requireSafeTree(previous)
                move(previous, active)
            }
        }
        // An update candidate only becomes visible after the final atomic directory move.
        systemRoot.listFiles().orEmpty().filter {
            it.name.startsWith(".skill-update-")
        }.forEach { abandoned ->
            requireNoLinks(abandoned)
            require(abandoned.deleteRecursively()) { "未完成的技能更新临时文件清理失败" }
        }
    }

    /** Confirmed uninstallation must also discard the hidden rollback revision. */
    fun forgetRollback(id: String) {
        validateId(id)
        val previous = File(rollbackRoot, id)
        if (!previous.exists()) return
        requireSafeTree(previous)
        require(previous.deleteRecursively()) { "技能旧版本删除失败" }
    }

    fun rollback(id: String): String {
        validateId(id)
        require(!Files.isSymbolicLink(systemRoot.toPath()) &&
            !Files.isSymbolicLink(skillsRoot.toPath()) &&
            !Files.isSymbolicLink(rollbackRoot.toPath())) { "技能目录含不安全链接" }
        val active = File(skillsRoot, id)
        val previous = File(rollbackRoot, id)
        requireSafeTree(active)
        requireSafeTree(previous)
        val parked = File(systemRoot, ".active-$id-${UUID.randomUUID()}")
        move(active, parked)
        try {
            move(previous, active)
            try {
                move(parked, previous)
            } catch (failure: Exception) {
                move(active, previous)
                move(parked, active)
                throw failure
            }
        } catch (failure: Exception) {
            if (!active.exists() && parked.exists()) move(parked, active)
            throw failure
        }
        return id
    }
}
