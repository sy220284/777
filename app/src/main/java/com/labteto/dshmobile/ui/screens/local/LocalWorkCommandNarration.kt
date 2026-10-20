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


/**
 * Build a concise activity title from verified tool identity and bounded argument categories.
 * Never show free-form queries, file paths, shell commands, or results on the timeline.
 * The verb identifies the operation being attempted, not a claim that it succeeded.
 */
internal fun naturalWorkToolAction(toolName: String?, argumentsPreview: String?): String? {
    val name = toolName?.lowercase().orEmpty()
    if (name.isBlank()) return null
    if (com.labteto.dshmobile.ui.agentOperationKind(name) ==
        com.labteto.dshmobile.ui.AgentOperationKind.Execute
    ) return naturalWorkCommandAction(argumentsPreview)

    val data = argumentsPreview?.let { preview ->
        runCatching { Json.parseToJsonElement(preview) }.getOrNull() as? JsonObject
    }
    val pathHint = listOf("path", "file_path", "filename")
        .mapNotNull { key -> data?.get(key)?.toString() }
        .joinToString(" ").lowercase()
    val queryHint = listOf("query", "q", "search", "search_query")
        .mapNotNull { key -> data?.get(key)?.toString() }
        .joinToString(" ").lowercase()
    val hint = "$pathHint $queryHint"
    // Broad categories only: no user-authored query or private path is echoed.
    val subject = when {
        Regex("""(?:timeline|transcript|workstep|workreply|工作步骤|时间线|回复显示)""")
            .containsMatchIn(hint) -> "工作回复时间线"
        Regex("""(?:aesthetic|设计技能|ui.design|ui设计|visual.skill)""")
            .containsMatchIn(hint) -> "视觉与 UI 设计技能"
        Regex("""(?:skill|技能)""").containsMatchIn(hint) -> "技能配置"
        Regex("""(?:androidtest|regression|test\.kt|测试|回归)""")
            .containsMatchIn(hint) -> "相关测试"
        Regex("""(?:视觉|ui/|/ui|design|界面|layout)""")
            .containsMatchIn(hint) ->
            if (pathHint.endsWith(".kt\"") || pathHint.contains(".kt")) "界面代码"
            else "界面设计资料"
        Regex("""(?:log|diagnostic|日志)""").containsMatchIn(hint) -> "诊断日志"
        else -> null
    }
    val fileSubject = when (subject) {
        "工作回复时间线" -> "工作回复时间线代码"
        "相关测试" -> "相关测试文件"
        "技能配置" -> "技能配置文件"
        else -> subject
    }

    return when {
        name.contains("github") && name.contains("search") ->
            "检索" + (subject ?: "仓库代码与资料")
        name.contains("github") && (name.contains("fetch_pr_patch") ||
            name.contains("get_pr_diff") || name.contains("fetch_pr_file_patch")) ->
            "审查拉取请求的代码改动"
        name.contains("github") && name.contains("list_pr_changed_filenames") ->
            "核对拉取请求涉及的文件"
        name.contains("github") && (name.contains("fetch_workflow") ||
            name.contains("check_run") || name.contains("workflow_run")) ->
            "检查构建和自动化测试进度"
        name.contains("github") && name.contains("get_pr_info") ->
            "查看拉取请求状态与改动"
        name.contains("github") && name.contains("fetch_file") ->
            "查看" + (fileSubject ?: "仓库代码文件")
        name.contains("github") && (name.contains("update_file") ||
            name.contains("create_file")) ->
            (if (name.contains("create_file")) "新增" else "修改") + (fileSubject ?: "仓库文件")
        name.contains("figma") && (name.contains("get") || name.contains("read") ||
            name.contains("search")) -> "查看视觉设计稿"
        name == "web_search" -> "检索" + (subject ?: "网络资料")
        name in setOf("web_fetch", "http_request") -> "读取网页内容"
        name in setOf("read", "read_file", "file_inspect") ->
            "读取" + (subject ?: "文件内容")
        name in setOf("grep", "search", "glob", "find", "list_files") ->
            "查找" + (subject ?: "文件内容")
        name in setOf("edit", "edit_file", "apply_patch", "patch") ->
            "修改" + (subject ?: "文件内容")
        else -> null
    }
}
