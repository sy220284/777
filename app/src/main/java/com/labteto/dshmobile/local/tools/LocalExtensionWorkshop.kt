package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persistent, workspace-local authoring and provenance for agent-created capabilities.
 * The live skill catalog and ToolRegistry remain the only invocation authorities.
 */
internal class LocalExtensionWorkshop(private val workspace: LocalWorkspace) {
    private val skills = LocalSkillStore(workspace)
    private val namePattern = Regex("[a-z][a-z0-9-]{1,47}")
    private val kinds = setOf("skill", "tool", "connector", "mcp", "plugin")
    private val phases = setOf("discovered", "created", "registered", "tested", "improved", "failed")
    private fun safeId(id: String): String {
        require(namePattern.matches(id)) { "扩展标识必须为小写英文字母、数字和连字符" }
        return id
    }
    private fun fullSha(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sha(text: String): String = fullSha(text).take(16)

    private fun revisionPath(id: String, revision: String) =
        ".dsh/extensions/skill-${safeId(id)}/revisions/${revision}.md"

    private fun ensureExperienceDocuments() {
        val docs = mapOf(
            "EXPERIENCE.md" to "# 复用经验\n\n保留经过实际复核的操作路径及其证据。\n",
            "IMPROVEMENTS.md" to "# 能力改进\n\n记录变更、原版本与新版本，避免仅凭生成成功认定改进完成。\n",
            "PROMPT-CHANGES.md" to "# 提示词优化\n\n记录提示词原文、修改原因与可复现的前后对比。\n",
            "ISSUES.md" to "# 遗留问题\n\n记录现象、失败原因、复现方式、修复及复验状态。\n",
        )
        for ((filename, initial) in docs) {
            val target = File(workspace.path, ".dsh/extensions/$filename")
            if (!target.exists()) workspace.write(".dsh/extensions/$filename", initial)
        }
    }

    /** Create an inspectable, restart-reusable MCP source package; execution is a separate approval. */
    fun createMcp(id: String, interpreter: String, source: String): String {
        safeId(id)
        require(interpreter in setOf("node", "python3")) { "本机 MCP 目前支持 node 或 python3 运行时" }
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= 128 * 1024) {
            "服务源码不能为空或超过 128 KB"
        }
        val root = ".dsh/extensions/mcp-$id"
        val filename = if (interpreter == "node") "server.js" else "server.py"
        val manifestPath = "$root/manifest.json"
        require(!File(workspace.path, manifestPath).exists()) { "同名 MCP 扩展已存在，请更新现有源码并重新校验" }
        val sourcePath = "$root/$filename"
        workspace.write(sourcePath, source)
        val manifest = buildJsonObject {
            put("schema_version", 1)
            put("server_id", id)
            put("interpreter", interpreter)
            put("source_path", sourcePath)
            put("source_sha256", fullSha(source))
        }
        workspace.write(manifestPath, manifest.toString() + "\n")
        ensureExperienceDocuments()
        require(launchCommand(id).first.size == 2) { "本地 MCP 启动配置无法重新加载" }
        return "本机 MCP 源码及启动清单已写入：$manifestPath。此时仅生成文件；调用 mcp_start_local，经权限审批并真实连接后才算注册。"
    }

    /** Revalidate every persisted field and source digest before handing off to the real MCP bridge. */
    fun launchCommand(id: String): Pair<List<String>, String> {
        safeId(id)
        val manifest = Json.parseToJsonElement(
            workspace.readRaw(".dsh/extensions/mcp-$id/manifest.json")
        ).jsonObject
        require(manifest["schema_version"]?.jsonPrimitive?.content == "1") { "MCP 清单版本不兼容" }
        require(manifest["server_id"]?.jsonPrimitive?.content == id) { "MCP 标识不一致" }
        val interpreter = requireNotNull(manifest["interpreter"]?.jsonPrimitive?.content)
        require(interpreter in setOf("node", "python3")) { "MCP 运行时不支持" }
        val sourcePath = requireNotNull(manifest["source_path"]?.jsonPrimitive?.content)
        val expected = ".dsh/extensions/mcp-$id/" +
            if (interpreter == "node") "server.js" else "server.py"
        require(sourcePath == expected) { "MCP 入口路径不符合安装清单" }
        val source = workspace.readRaw(sourcePath)
        require(fullSha(source) == manifest["source_sha256"]?.jsonPrimitive?.content) {
            "MCP 源码已改变，必须审核并重新生成匹配的清单后才能启动"
        }
        return listOf(interpreter, File(workspace.path, sourcePath).absolutePath) to workspace.path
    }

    fun createSkill(id: String, displayName: String, description: String, instructions: String): String {
        safeId(id)
        skills.create(id, displayName, description, instructions)
        val installed = workspace.readModelSkill(id)
        require(installed.contains(instructions.trim())) { "技能安装后读取内容不一致" }
        ensureExperienceDocuments()
        return "已安装并复读校验技能：$id；来源 .dsh/skills/$id/SKILL.md。执行效果尚未验证，可直接使用 skill(name=$id) 读取。"
    }

    /** Save the exact previous version before a user-approved skill update. */
    fun reviseSkill(id: String, document: String): String {
        safeId(id)
        val before = skills.readDocument(id)
        val revision = sha(before)
        val path = revisionPath(id, revision)
        val file = File(workspace.path, path)
        if (!file.exists()) workspace.write(path, before)
        skills.updateDocument(id, document)
        val now = skills.readDocument(id)
        require(now == document.trimEnd() + "\n") { "技能更新结果未与提交文档一致" }
        ensureExperienceDocuments()
        return "技能 $id 更新并复读成功；原版本备份 $revision，可调用 skill_rollback 回退。新版本执行效果尚未验证。"
    }

    fun rollbackSkill(id: String, revision: String): String {
        safeId(id)
        require(Regex("[a-f0-9]{16}").matches(revision)) { "版本编号无效" }
        val backup = workspace.readRaw(revisionPath(id, revision))
        require(sha(backup) == revision) { "版本文件校验失败" }
        // Save current version using the same revision system, enabling reversible rollback.
        val current = skills.readDocument(id)
        val currentRevision = sha(current)
        val currentPath = revisionPath(id, currentRevision)
        if (!File(workspace.path, currentPath).exists()) workspace.write(currentPath, current)
        skills.updateDocument(id, backup)
        require(skills.readDocument(id) == backup.trimEnd() + "\n") { "回退内容校验失败" }
        return "技能 $id 已回退至 $revision；回退前版本 $currentRevision 已保存。"
    }

    /**
     * Record evidence pointers only. A user/agent-provided 'tested' label is not independent
     * runtime verification and never registers or authorizes a tool.
     */
    fun record(kind: String, id: String, phase: String, summary: String, evidencePath: String): String {
        require(kind in kinds && phase in phases) { "扩展类别或阶段无效" }
        safeId(id)
        require(summary.isNotBlank() && summary.length <= 1200) { "记录摘要需在 1—1200 字内" }
        require(evidencePath.isBlank() || (evidencePath.length <= 240 &&
            !evidencePath.startsWith("/") && ".." !in evidencePath.split('/'))) { "证据路径须在工作区内" }
        if (phase == "tested") require(evidencePath.isNotBlank()) { "标记测试过必须附工作区内证据文件" }
        if (evidencePath.isNotBlank()) workspace.readRaw(evidencePath)
        ensureExperienceDocuments()
        val target = ".dsh/extensions/$kind-$id/RECORD.md"
        val exists = File(workspace.path, target).isFile
        val old = if (exists) workspace.readRaw(target) else "# 扩展记录：$kind / $id\n\n"
        val clean = summary.trim().replace("\r", "").replace("\n", " ")
        val entry = "- 阶段：$phase；说明：$clean；证据文件：" +
            (evidencePath.ifBlank { "尚未提交" }) + "；可信度：待独立核验\n"
        require((old + entry).toByteArray(Charsets.UTF_8).size <= 200_000) { "扩展记录过大，请整理归档" }
        workspace.write(target, old + entry)
        return "已写入 $target；本次为记录声明，不代表工具已经注册或业务调用成功。"
    }

    fun inspect(kind: String, id: String, liveToolNames: Set<String>, installedPlugins: Set<String>): String {
        require(kind in kinds)
        safeId(id)
        val documented = ".dsh/extensions/$kind-$id/RECORD.md"
        val history = if (File(workspace.path, documented).isFile) workspace.readRaw(documented)
            else "尚无扩展记录"
        val actual = when (kind) {
            "skill" -> if (id in workspace.skills()) "SKILL.md 已安装；实际效果仍需测试" else "SKILL.md 尚未安装"
            "plugin" -> if (id in installedPlugins) "App 当前插件实例已安装" else "App 尚未安装该原生插件"
            "mcp", "connector", "tool" -> {
                val exact = liveToolNames.filter { it == id || it.startsWith("mcp_${id}_") }
                if (exact.isEmpty()) "当前工具注册表没有对应工具" else
                    "当前已注册：${exact.sorted().joinToString(", ")}；注册不等于真实调用成功"
            }
            else -> error("未知类型")
        }
        return "实时检查：$actual\n工作区记录（自述，非执行凭据）：\n${history.takeLast(7000)}"
    }
}
