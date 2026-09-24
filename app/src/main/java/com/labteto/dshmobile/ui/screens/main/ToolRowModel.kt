package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.graphics.vector.ImageVector
import com.labteto.dshmobile.ui.components.FeatherIcons

/**
 * Legacy tool-row shape kept for compatibility with components/tests that still reference it.
 *
 * Presentation policy no longer derives summaries from tool arguments. Paths, queries, commands,
 * URLs, file names and unknown tool names are execution detail and must never become UI fallback
 * text. New conversation surfaces should use the semantic operation policy instead.
 */
internal enum class ToolRowVariant(val title: String) {
    Search("Search"),
    Read("Read"),
    Bash("Bash"),
    Write("Write"),
    Edit("Edit"),
    Code("Code"),
    Other("Tool call"),
}

private val TOOL_VARIANTS: Map<String, ToolRowVariant> = mapOf(
    "bash" to ToolRowVariant.Bash,
    "pwsh" to ToolRowVariant.Bash,
    "shell" to ToolRowVariant.Bash,
    "run_shell" to ToolRowVariant.Bash,
    "process_exec" to ToolRowVariant.Bash,
    "terminal_open" to ToolRowVariant.Bash,
    "terminal_send" to ToolRowVariant.Bash,
    "terminal_write" to ToolRowVariant.Bash,
    "pty-send" to ToolRowVariant.Bash,
    "read" to ToolRowVariant.Read,
    "web_fetch" to ToolRowVariant.Read,
    "web_search" to ToolRowVariant.Search,
    "grep" to ToolRowVariant.Search,
    "glob" to ToolRowVariant.Search,
    "write" to ToolRowVariant.Write,
    "edit" to ToolRowVariant.Edit,
    "run_code" to ToolRowVariant.Code,
    "cordis_package_inspect" to ToolRowVariant.Read,
    "cordis_runtime_inspect" to ToolRowVariant.Read,
    "cordis_run" to ToolRowVariant.Other,
    "cordis_stop" to ToolRowVariant.Other,
    "cordis_undefine" to ToolRowVariant.Other,
)

internal data class ToolRowModel(
    val variant: ToolRowVariant,
    val title: String,
    val summary: String?,
    val filePath: String?,
)

internal fun classifyTool(toolName: String): ToolRowVariant =
    TOOL_VARIANTS[toolName.lowercase()] ?: ToolRowVariant.Other

internal fun ToolRowVariant.featherIcon(): ImageVector = when (this) {
    ToolRowVariant.Search -> FeatherIcons.Search
    ToolRowVariant.Read -> FeatherIcons.FileText
    ToolRowVariant.Bash -> FeatherIcons.Terminal
    ToolRowVariant.Write -> FeatherIcons.FilePlus
    ToolRowVariant.Edit -> FeatherIcons.Edit3
    ToolRowVariant.Code -> FeatherIcons.Code
    ToolRowVariant.Other -> FeatherIcons.Tool
}

/**
 * Intentionally ignores arguments, cwd and presenter title.
 *
 * Those fields can contain sensitive/noisy execution details. Keeping them in the signature avoids
 * breaking older call sites while making the safe behavior the default.
 */
@Suppress("UNUSED_PARAMETER")
internal fun toolRowModel(
    toolName: String,
    argumentsJson: String?,
    cwd: String?,
    viewTitle: String? = null,
): ToolRowModel {
    val variant = classifyTool(toolName)
    return ToolRowModel(
        variant = variant,
        title = variant.title,
        summary = null,
        filePath = null,
    )
}

internal fun relativizeToCwd(text: String, cwd: String?): String {
    val root = cwd?.takeIf { it.isNotBlank() } ?: return text
    val normalizedRoot = root.trimEnd('\\', '/')
    if (!text.startsWith(normalizedRoot, ignoreCase = true)) return text
    return text.substring(normalizedRoot.length).trimStart('\\', '/').ifBlank { text }
}

internal fun basename(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
