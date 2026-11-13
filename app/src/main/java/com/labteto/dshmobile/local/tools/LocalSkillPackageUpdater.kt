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

    private fun requireSafeTree(directory: File) {
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
