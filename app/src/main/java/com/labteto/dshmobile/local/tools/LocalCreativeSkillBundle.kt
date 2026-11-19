package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.parseLocalSkillMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * Installs the full original skill tree, including binary media, into the local
 * directory used by both the UI and the model. Bundled scripts are never executed.
 * SKILL.md is published last; upgrades preserve modified files and user settings.
 */
internal class LocalCreativeSkillBundle(
    private val workspace: LocalWorkspace,
    private val loadAssets: (String) -> Map<String, ByteArray>,
) {
    private val skillsDir get() = File(workspace.path, ".dsh/skills")

    fun install(preset: LocalPresetSkill) {
        val files = validatedAssets(preset.id)
        val original = files.getValue("SKILL.md").toString(Charsets.UTF_8)
        val document = appDocument(preset, original)
        val target = File(skillsDir, "${preset.id}/SKILL.md")
        require(Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "技能已经安装：${preset.id}"
        }
        materializeResources(preset.id, files, original)
        workspace.write(".dsh/skills/${preset.id}/SKILL.md", document)
        File(skillsDir, "${preset.id}/.bundled-baseline-sha256")
            .writeText(fingerprint(document) + "\n")
        versionMarker(preset.id).writeText(LocalCreativeSkillCatalog.bundleRevisions.getValue(preset.id) + "\n")
        guideMarker(preset.id).writeText(guideFingerprint(preset) + "\n")
    }

    fun refresh(preset: LocalPresetSkill) {
        val baseline = File(skillsDir, "${preset.id}/.bundled-baseline-sha256")
        // An existing user-imported skill without a bundled baseline stays user-owned.
        if (!Files.isRegularFile(baseline.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val assetRevision = LocalCreativeSkillCatalog.bundleRevisions.getValue(preset.id)
        val installedAssets = versionMarker(preset.id)
        val installedGuide = guideMarker(preset.id)
        val requiredGuide = guideFingerprint(preset)
        val assetsCurrent = Files.isRegularFile(installedAssets.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            installedAssets.readText(Charsets.UTF_8).trim() == assetRevision
        val guideCurrent = Files.isRegularFile(installedGuide.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            installedGuide.readText(Charsets.UTF_8).trim() == requiredGuide
        if (assetsCurrent && guideCurrent) return

        val previous = baseline.readText(Charsets.UTF_8).trim()
        require(Regex("[0-9a-f]{64}").matches(previous)) {
            "内置技能升级基线损坏：${preset.id}"
        }

        // Chinese labels/guides change independently from the source resources.
        // Reuse the saved upstream rules rather than reading megabytes of binary media.
        val upstream = File(skillsDir, "${preset.id}/UPSTREAM-SKILL.md")
        val original = if (assetsCurrent &&
            Files.isRegularFile(upstream.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            upstream.readText(Charsets.UTF_8)
        } else {
            val files = validatedAssets(preset.id)
            val source = files.getValue("SKILL.md").toString(Charsets.UTF_8)
            materializeResources(preset.id, files, source)
            source
        }
        val document = appDocument(preset, original)
        val current = workspace.readSkill(preset.id)
        val currentHash = fingerprint(current)
        val desiredHash = fingerprint(document)
        if (currentHash != previous && currentHash != desiredHash) {
            // Preserve custom rules, including user-edited Chinese names.
            installedAssets.writeText(assetRevision + "\n")
            installedGuide.writeText(requiredGuide + "\n")
            return
        }

        val disabled = !parseLocalSkillMetadata(preset.id, current).modelInvocable
        val next = if (disabled) {
            document.replaceFirst("\n---\n", "\ndisable-model-invocation: true\n---\n")
        } else document
        if (current != next) workspace.write(".dsh/skills/${preset.id}/SKILL.md", next)
        baseline.writeText(desiredHash + "\n")
        installedAssets.writeText(assetRevision + "\n")
        installedGuide.writeText(requiredGuide + "\n")
    }

    private fun versionMarker(id: String): File = File(skillsDir, "$id/.bundled-asset-version")

    private fun guideMarker(id: String): File = File(skillsDir, "$id/.bundled-guide-version")

    private fun guideFingerprint(preset: LocalPresetSkill): String =
        fingerprint(listOf(preset.id, preset.title, preset.description, preset.whenToUse, preset.instructions)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))

    private fun validatedAssets(id: String): Map<String, ByteArray> {
        require(id in LocalCreativeSkillCatalog.entries.map { it.id }) { "未知内置技能：$id" }
        val files = loadAssets(id)
        require("SKILL.md" in files && "LICENSE" in files && files.size >= 3) {
            "内置技能资源不完整：$id"
        }
        files.forEach { (path, content) ->
            require(path.isNotBlank() && path != "UPSTREAM-SKILL.md" &&
                path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                '\\' !in path && '\u0000' !in path && content.isNotEmpty()) {
                "内置技能资源路径或内容无效：$path"
            }
        }
        return files
    }

    /** The original unmodified source remains available for local audit. */
    private fun appDocument(preset: LocalPresetSkill, original: String): String {
        require(original.startsWith("---\n")) { "内置技能元数据不完整：${preset.id}" }
        val end = original.indexOf("\n---\n", startIndex = 4)
        require(end > 4) { "内置技能元数据缺少结束分隔符：${preset.id}" }
        val header = original.substring(4, end).lines().toMutableList()
        require(header.count { it.trim() == "name: ${preset.id}" } == 1 &&
            header.count { it.startsWith("description:") } == 1) {
            "内置技能名称或描述不正确：${preset.id}"
        }
        header.replaceAll { line ->
            if (line.startsWith("description:")) {
                "description: \"${preset.description.replace('"', '\'')}\""
            } else line
        }
        header.removeAll { it.startsWith("display-name:") || it.startsWith("when-to-use:") }
        header += "display-name: \"${preset.title}\""
        header += "when-to-use: \"${preset.whenToUse}\""
        val chineseGuide = "## 中文使用指南\n\n" + preset.instructions.trim() + "\n\n---\n\n"
        return "---\n" + header.joinToString("\n") + "\n---\n\n" +
            chineseGuide + original.substring(end + 5).trimStart('\n')
    }

    private fun materializeResources(id: String, files: Map<String, ByteArray>, original: String) {
        files.forEach { (relative, content) ->
            if (relative != "SKILL.md") publishResource(id, relative, content)
        }
        publishResource(id, "UPSTREAM-SKILL.md", original.toByteArray(Charsets.UTF_8))
    }

    private fun publishResource(id: String, relative: String, content: ByteArray) {
        val parts = relative.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) {
            "无效内置资源：$relative"
        }
        var parent = skillsDir
        for (segment in listOf(id) + parts.dropLast(1)) {
            require(!Files.isSymbolicLink(parent.toPath())) { "技能资源路径包含符号链接" }
            require(parent.isDirectory || parent.mkdirs()) { "技能资源目录不可创建" }
            parent = File(parent, segment)
        }
        require(!Files.isSymbolicLink(parent.toPath())) { "技能资源路径包含符号链接" }
        require(parent.isDirectory || parent.mkdir()) { "技能资源目录不可创建" }
        val target = File(parent, parts.last())
        require(!Files.isSymbolicLink(target.toPath())) { "技能资源不能是符号链接：$relative" }
        require(!target.exists() || target.isFile) { "技能资源路径被占用：$relative" }

        val marker = File(skillsDir, "$id/.bundled-resource-${fingerprint(relative.toByteArray(Charsets.UTF_8))}")
        require(!Files.isSymbolicLink(marker.toPath())) { "技能资源基线不能是符号链接" }
        val expected = fingerprint(content)
        val current = if (target.isFile) fingerprint(target.readBytes()) else null
        val known = if (marker.isFile) marker.readText(Charsets.UTF_8).trim() else null
        // An unknown difference is a user modification and remains untouched.
        if (current != null && current != expected && current != known) return
        if (current != expected) Files.write(target.toPath(), content)
        marker.writeText(expected + "\n")
    }

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fingerprint(document: String): String {
        val withoutUserToggle = document.replace(
            Regex("(?m)^disable-model-invocation: (?:true|false)\\r?\\n"), "",
        )
        return fingerprint(withoutUserToggle.toByteArray(Charsets.UTF_8))
    }
}
