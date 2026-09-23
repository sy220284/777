package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class ConversationFileRef(
    val path: String,
    val tool: String,
    val seq: Long,
)

internal data class ConversationFileIndex(
    val artifacts: List<ConversationFileRef> = emptyList(),
    val involved: List<ConversationFileRef> = emptyList(),
) {
    val isEmpty: Boolean get() = artifacts.isEmpty() && involved.isEmpty()
}

/**
 * Files the current session actually touched.
 *
 * The workspace protocol exposes the directory tree, but it does not expose a separate
 * conversation-file projection. Rebuild that view from durable tool calls and their result
 * metadata instead of guessing from timestamps in the workspace.
 */
internal fun conversationFileIndex(
    nodes: List<ChatNode>,
    cwd: String?,
): ConversationFileIndex {
    val results = nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
    val artifacts = linkedMapOf<String, ConversationFileRef>()
    val involved = linkedMapOf<String, ConversationFileRef>()

    fun remember(raw: String?, artifact: Boolean, tool: String, seq: Long) {
        val path = workspaceRelativePath(raw, cwd) ?: return
        val ref = ConversationFileRef(path = path, tool = tool, seq = seq)
        if (artifact) {
            artifacts[path] = newer(artifacts[path], ref)
            involved.remove(path)
        } else if (path !in artifacts) {
            involved[path] = newer(involved[path], ref)
        }
    }

    nodes.filterIsInstance<ToolCallNode>().forEach { call ->
        val args = runCatching { WireJson.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
        val result = results[call.callId]
        val artifactTool = isArtifactTool(call.name, args)

        args?.let { objectArgs ->
            for (key in FILE_KEYS) {
                if (key == "path" && call.name.lowercase() in DIRECTORY_PATH_TOOLS) continue
                primitiveString(objectArgs[key])?.let { value ->
                    remember(value, artifactTool || key in ARTIFACT_PATH_KEYS, call.name, call.seq)
                }
            }
            primitiveStrings(objectArgs["paths"]).forEach { remember(it, artifactTool, call.name, call.seq) }
            primitiveStrings(objectArgs["files"]).forEach { remember(it, artifactTool, call.name, call.seq) }
        }

        val meta = result?.meta as? JsonObject
        if (meta != null) {
            primitiveString(meta["path"])?.let { remember(it, artifactTool, call.name, result.seq) }
            primitiveStrings(meta["paths"]).forEach { remember(it, artifactTool, call.name, result.seq) }
            (meta["diffs"] as? JsonArray).orEmpty().forEach { item ->
                primitiveString((item as? JsonObject)?.get("path"))?.let {
                    remember(it, artifactTool, call.name, result.seq)
                }
            }
            (meta["files"] as? JsonArray).orEmpty().forEach { item ->
                primitiveString((item as? JsonObject)?.get("path"))?.let {
                    remember(it, artifactTool, call.name, result.seq)
                }
            }
        }

        result?.let { settled ->
            resultText(settled.content).forEach { text ->
                ARTIFACT_RESULT_PATH.findAll(text).forEach { match ->
                    val rawPath = match.groupValues[1].substringBefore('（').trim()
                    remember(rawPath, true, call.name, settled.seq)
                }
            }
        }
    }

    return ConversationFileIndex(
        artifacts = artifacts.values.sortedWith(compareByDescending<ConversationFileRef> { it.seq }.thenBy { it.path }),
        involved = involved.values.sortedWith(compareByDescending<ConversationFileRef> { it.seq }.thenBy { it.path }),
    )
}

private fun newer(old: ConversationFileRef?, next: ConversationFileRef): ConversationFileRef =
    if (old == null || next.seq >= old.seq) next else old

private fun isArtifactTool(name: String, args: JsonObject?): Boolean {
    val tool = name.lowercase()
    if (tool in ARTIFACT_TOOLS) return true
    if ("download" in tool || "export" in tool || "generate_file" in tool) return true
    if (tool == "str_replace_editor") {
        return primitiveString(args?.get("command")) == "create"
    }
    return false
}

private fun primitiveString(value: JsonElement?): String? =
    runCatching { value?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

private fun primitiveStrings(value: JsonElement?): List<String> =
    (value as? JsonArray).orEmpty().mapNotNull(::primitiveString)

private fun resultText(value: JsonElement?): List<String> {
    val blocks = value as? JsonArray ?: return emptyList()
    return blocks.mapNotNull { block ->
        val obj = block as? JsonObject ?: return@mapNotNull null
        if (primitiveString(obj["type"]) != "text") return@mapNotNull null
        primitiveString(obj["text"])
    }
}

private fun workspaceRelativePath(raw: String?, cwd: String?): String? {
    var value = raw?.trim()?.trim('"', '\'', '`')?.replace('\\', '/') ?: return null
    if (value.isBlank() || value.contains("://") || value.startsWith("~")) return null

    val root = cwd?.trim()?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val absolute = value.startsWith("/") || WINDOWS_ABSOLUTE.containsMatchIn(value)
    if (absolute) {
        val absoluteRoot = root ?: return null
        val comparableValue = value.lowercase()
        val comparableRoot = absoluteRoot.lowercase()
        value = when {
            comparableValue == comparableRoot -> return null
            comparableValue.startsWith("$comparableRoot/") -> value.substring(absoluteRoot.length + 1)
            else -> return null
        }
    }

    val parts = ArrayDeque<String>()
    value.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/").takeIf { it.isNotBlank() }
}

private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:/")
private val ARTIFACT_RESULT_PATH = Regex(
    "(?im)(?:\\u5de5\\u4f5c\\u533a\\u6587\\u4ef6|\\u4e0b\\u8f7d\\u5b8c\\u6210|" +
        "\\u5b8c\\u6574\\u7ed3\\u679c\\u5df2\\u4fdd\\u5b58|\\u6210\\u679c\\u5df2\\u786e\\u8ba4|" +
        "saved to|downloaded to|workspace file)[:：]\\s*([^\\r\\n]+)",
)

private val FILE_KEYS = setOf(
    "file_path", "filePath", "path", "relative_path", "relativePath",
    "output_path", "outputPath", "destination", "destination_path", "destinationPath",
    "save_path", "savePath", "target_path", "targetPath",
)

private val ARTIFACT_PATH_KEYS = setOf(
    "output_path", "outputPath", "destination", "destination_path", "destinationPath",
    "save_path", "savePath",
)

private val ARTIFACT_TOOLS = setOf(
    "write", "write_file", "create_file", "download_file", "present", "copy_file",
)

private val DIRECTORY_PATH_TOOLS = setOf(
    "list", "list_files", "ls",
    "glob", "glob_files",
    "grep", "search", "search_text", "file_search",
)
