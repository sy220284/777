package com.labteto.dshmobile.ui.screens.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Derive a readable action from the actual tool/call arguments, never from the result text.
 * This is a display-only description; raw commands and full results stay in the tool receipt.
 */
internal fun naturalWorkCommandAction(argumentsPreview: String?): String? {
    val source = argumentsPreview ?: return null
    val data = runCatching { Json.parseToJsonElement(source) }.getOrNull() as? JsonObject
        ?: return null
    val value = listOf("command", "cmd", "shell_command", "script", "command_line")
        .firstNotNullOfOrNull { data[it] } ?: return null
    val command = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .joinToString(" ")
        else -> null
    }?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null

    // Capability checks must not be presented as installations or successful operations.
    val checked = Regex("""(?:^|[\s;&|])(?:command\s+-v|which)\s+([a-z0-9._+-]+)""")
        .findAll(command).map { it.groupValues[1] }.distinct().toList()
    if (checked.isNotEmpty()) return if (checked.size <= 3) {
        "检查 " + checked.joinToString("、") + " 是否可用"
    } else {
        "检查 " + checked.size + " 项系统工具是否可用"
    }
    if ("command -v" in command || "type -a" in command) return "检查系统工具是否可用"
    if (Regex("""\b(?:curl|wget|ffmpeg|jq|npm|pip3?|node|python3?|git|rg)\s+--?version\b""")
            .containsMatchIn(command)) return "核对工具版本"

    fun has(pattern: String): Boolean = Regex(pattern).containsMatchIn(command)
    return when {
        has("""\bgit\s+status\b""") -> "查看仓库改动状态"
        has("""\bgit\s+diff\b""") -> "核对代码改动"
        has("""\bgit\s+(?:log|branch|show)\b""") -> "查看仓库提交与分支信息"
        has("""\bgit\s+add\b""") -> "整理待提交的文件"
        has("""\bgit\s+commit\b""") -> "提交代码变更"
        has("""\b(?:rg|grep)\s+""") -> "搜索文件内容"
        has("""\b(?:find|ls|pwd|tree)\b""") -> "查看文件与目录"
        has("""\b(?:cat|sed|head|tail)\s+""") -> "读取文件内容"
        has("""\b(?:curl|wget)\s+""") -> "读取网络资源"
        has("""\b(?:unzip|tar)\s+""") -> "查看或处理压缩文件"
        has("""\b(?:gradlew?|npm|pnpm|yarn|pytest)\b.*\b(?:test|check|lint)\b""") ->
            "运行项目检查"
        has("""\b(?:ffmpeg|ffprobe)\s+""") -> "检查或处理音视频文件"
        has("""\b(?:python3?|node)\s+""") -> if ("json" in command) {
            "解析与核对数据"
        } else {
            "运行项目脚本"
        }
        else -> "运行本地任务"
    }
}
