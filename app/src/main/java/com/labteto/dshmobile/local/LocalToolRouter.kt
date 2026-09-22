package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps the model-facing tool surface small without hiding capabilities permanently.
 *
 * Core conversation/file/web tools stay visible. Android, vision, runtime, MCP, LSP, automation and
 * webhook tools are discovered on demand through capability_search and remain enabled for the
 * current turn only.
 */
internal object LocalToolRouter {
    private val optionalExact = setOf(
        "runtime_command_status",
        "process_exec",
        "terminal_open",
        "terminal_write",
        "terminal_read",
        "terminal_status",
        "terminal_close",
        "schedule_task",
        "schedule_recurring_task",
        "scheduled_task_list",
        "cancel_scheduled_task",
    )

    fun isOptional(name: String): Boolean =
        name in optionalExact ||
            name.startsWith("android_") ||
            name.startsWith("vision_") ||
            name.startsWith("mcp_") ||
            name.startsWith("lsp_") ||
            name.startsWith("webhook_")

    fun visibleSchemas(tools: List<HarnessTool>, enabledOptional: Set<String>): JsonArray =
        JsonArray(
            tools
                .filter { tool -> !isOptional(tool.name) || tool.name in enabledOptional }
                .map(HarnessTool::schema),
        )

    fun search(
        tools: List<HarnessTool>,
        query: String,
        limit: Int = 32,
    ): List<HarnessTool> {
        val normalized = query.trim().lowercase()
        require(normalized.isNotEmpty()) { "能力搜索内容不能为空" }
        val terms: Set<String> = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { match -> match.value }
            .take(24)
            .toSet()
        val scored = mutableListOf<Pair<HarnessTool, Int>>()
        for (tool in tools) {
            if (!isOptional(tool.name)) continue
            val haystack = buildString {
                append(tool.name.lowercase())
                append(' ')
                append(description(tool).lowercase())
                append(' ')
                append(familyTags(tool.name))
            }
            var score = 0
            for (term in terms) {
                score += when {
                    tool.name.equals(term, ignoreCase = true) -> 100
                    tool.name.contains(term, ignoreCase = true) -> 25
                    haystack.contains(term) -> 10
                    else -> 0
                }
            }
            if (score > 0) scored += tool to score
        }
        scored.sortWith(
            compareByDescending<Pair<HarnessTool, Int>> { pair -> pair.second }
                .thenBy { pair -> pair.first.name },
        )
        return scored
            .take(limit.coerceIn(1, 48))
            .map { pair -> pair.first }
    }

    fun description(tool: HarnessTool): String =
        tool.schema["function"]?.jsonObject
            ?.get("description")?.jsonPrimitive?.contentOrNull
            .orEmpty()

    private fun familyTags(name: String): String = when {
        name.startsWith("android_") ->
            "android 安卓 手机 设备 应用 界面 无障碍 点击 输入 滑动 通知 剪贴板 虚拟屏"
        name.startsWith("vision_") ->
            "vision 视觉 图片 图像 截图 屏幕 识别"
        name.startsWith("mcp_") ->
            "mcp 外部工具 服务 连接 扩展"
        name.startsWith("lsp_") ->
            "lsp 语言服务器 代码 定义 引用 符号 重命名 诊断"
        name.startsWith("webhook_") ->
            "webhook 回调 外部触发 监听"
        name.startsWith("terminal_") || name == "process_exec" || name == "runtime_command_status" ->
            "runtime 运行时 进程 终端 命令 shell git python node"
        name.startsWith("schedule_") || name.startsWith("scheduled_") || name == "cancel_scheduled_task" ->
            "automation 自动化 定时 周期 后台 任务"
        else -> ""
    }
}
