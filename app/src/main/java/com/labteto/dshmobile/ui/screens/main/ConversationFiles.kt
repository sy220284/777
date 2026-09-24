package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.OtherNode
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
    // Compatibility field: execution-touched files are intentionally never populated.
    val involved: List<ConversationFileRef> = emptyList(),
) {
    val isEmpty: Boolean get() = artifacts.isEmpty()
}

/**
 * Final user-facing deliverables for the current session.
 *
 * This projection intentionally trusts only the explicit deliverables/presented event. Tool names,
 * arguments, result metadata and result text are execution detail and are never mined for files.
 */
internal fun conversationFileIndex(
    nodes: List<ChatNode>,
    cwd: String?,
): ConversationFileIndex {
    val artifacts = linkedMapOf<String, ConversationFileRef>()

    nodes.filterIsInstance<OtherNode>()
        .filter { it.type == "deliverables/presented" }
        .forEach { node ->
            val files = (node.data as? JsonObject)?.get("files") as? JsonArray
            files.orEmpty().forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val raw = primitiveString(obj["path"])
                val path = workspaceRelativePath(raw, cwd) ?: return@forEach
                val next = ConversationFileRef(path = path, tool = "deliverable", seq = node.seq)
                val old = artifacts[path]
                if (old == null || next.seq >= old.seq) artifacts[path] = next
            }
        }

    return ConversationFileIndex(
        artifacts = artifacts.values.sortedWith(
            compareByDescending<ConversationFileRef> { it.seq }.thenBy { it.path },
        ),
        involved = emptyList(),
    )
}

private fun primitiveString(value: JsonElement?): String? =
    runCatching { value?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

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
