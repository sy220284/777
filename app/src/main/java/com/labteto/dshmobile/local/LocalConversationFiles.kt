package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rebuild the current local session's file view from its durable event log.
 *
 * Only paths that still exist in the workspace are returned, so stale/deleted results never create
 * dead rows in the UI.
 */
internal fun localConversationFiles(
    events: Sequence<LocalSessionEventLog.Event>,
    workspaceFiles: List<LocalWorkspaceFile>,
): LocalConversationFiles {
    val existing = workspaceFiles.associateBy(LocalWorkspaceFile::path)
    val artifactSeq = linkedMapOf<String, Long>()
    val involvedSeq = linkedMapOf<String, Long>()

    fun remember(raw: String?, artifact: Boolean, seq: Long) {
        val path = normalizeLocalWorkspacePath(raw) ?: return
        if (path !in existing) return
        if (artifact) {
            artifactSeq[path] = maxOf(artifactSeq[path] ?: Long.MIN_VALUE, seq)
            involvedSeq.remove(path)
        } else if (path !in artifactSeq) {
            involvedSeq[path] = maxOf(involvedSeq[path] ?: Long.MIN_VALUE, seq)
        }
    }

    for (event in events) {
        when (event.type) {
            "user/message" -> {
                val text = event.data["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ATTACHMENT_PATH.findAll(text).forEach { remember(it.groupValues[1], false, event.sequence) }
            }
            "tool/call" -> {
                val name = event.data["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = event.data["arguments"] as? JsonObject ?: continue
                val artifactTool = isLocalArtifactTool(name)
                for (key in LOCAL_FILE_KEYS) {
                    if (key == "path" && name.lowercase() in LOCAL_DIRECTORY_PATH_TOOLS) continue
                    val path = args[key]?.jsonPrimitive?.contentOrNull
                    remember(path, artifactTool || key in LOCAL_ARTIFACT_PATH_KEYS, event.sequence)
                }
            }
            "tool/result" -> {
                val name = event.data["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = event.data["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                LOCAL_ARTIFACT_RESULT.findAll(text).forEach { match ->
                    remember(match.groupValues[1].substringBefore('（').trim(), true, event.sequence)
                }
                LOCAL_WRITE_RESULT.findAll(text).forEach { match ->
                    remember(match.groupValues[1].substringBefore('（').trim(), isLocalArtifactTool(name), event.sequence)
                }
                if (name.lowercase() in LOCAL_GLOB_TOOLS) {
                    text.lineSequence()
                        .map(String::trim)
                        .filter { it.isNotBlank() && !it.startsWith("未找到") }
                        .forEach { remember(it, false, event.sequence) }
                }
                if (name.lowercase() in LOCAL_SEARCH_TOOLS) {
                    text.lineSequence().forEach { line ->
                        LOCAL_SEARCH_RESULT.find(line)?.groupValues?.getOrNull(1)?.let {
                            remember(it, false, event.sequence)
                        }
                    }
                }
            }
        }
    }

    fun rows(source: Map<String, Long>): List<LocalWorkspaceFile> =
        source.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .mapNotNull { existing[it.key] }

    return LocalConversationFiles(
        artifacts = rows(artifactSeq),
        involved = rows(involvedSeq),
    )
}

private fun normalizeLocalWorkspacePath(raw: String?): String? {
    val value = raw?.trim()?.trim('"', '\'', '`')?.replace('\\', '/') ?: return null
    if (value.isBlank() || value.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(value)) return null
    val parts = ArrayDeque<String>()
    for (segment in value.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/").takeIf(String::isNotBlank)
}

private fun isLocalArtifactTool(name: String): Boolean {
    val tool = name.lowercase()
    return tool == "present" || "download" in tool || "export" in tool || "generate_file" in tool
}

private val LOCAL_FILE_KEYS = setOf(
    "path", "file_path", "filePath", "relative_path", "relativePath",
    "output_path", "outputPath", "destination", "destination_path", "destinationPath",
    "save_path", "savePath", "target_path", "targetPath",
)
private val LOCAL_ARTIFACT_PATH_KEYS = setOf(
    "output_path", "outputPath", "destination", "destination_path", "destinationPath",
    "save_path", "savePath",
)
private val LOCAL_DIRECTORY_PATH_TOOLS = setOf(
    "list", "list_files", "ls",
    "glob", "glob_files",
    "grep", "search", "search_text", "file_search",
)
private val LOCAL_GLOB_TOOLS = setOf("glob", "glob_files")
private val LOCAL_SEARCH_TOOLS = setOf("grep", "search", "search_text", "file_search")

private val ATTACHMENT_PATH = Regex("""(?m)^\s*-[^\n]*→\s*([^（\r\n]+)（""")
private val LOCAL_ARTIFACT_RESULT = Regex(
    """(?im)(?:工作区文件|下载完成|完整结果已保存|成果已确认)[:：]\s*([^\r\n]+)"""
)
private val LOCAL_WRITE_RESULT = Regex("""(?im)(?:已写入|已编辑)\s+([^\r\n]+)""")
private val LOCAL_SEARCH_RESULT = Regex("""^([^:\r\n]+):\d+:""")
