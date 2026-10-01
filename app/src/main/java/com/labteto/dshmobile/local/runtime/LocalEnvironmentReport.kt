package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.observability.AppLogEntry

/** Formats the user-visible environment summary without owning engine resources. */
internal object LocalEnvironmentReport {
    fun build(
        workspacePath: String,
        resources: HarnessResourceSnapshot,
        contextChars: Int,
        contextBudgetChars: Int,
        pendingInputs: Int,
        pendingInputLimit: Int,
        commands: List<String>,
        runtimeStatuses: List<String>,
        recentDiagnostics: List<AppLogEntry>,
    ): String = buildString {
        appendLine("安卓本机 Harness 环境")
        appendLine("工作区：$workspacePath")
        appendLine(
            "执行预算：模型 ${resources.activeModelRequests}/${resources.budget.maxModelRequests}；" +
                "智能体 ${resources.activeAgents}/${resources.budget.maxAgents}；" +
                "终端 ${resources.activeTerminals}/${resources.budget.maxTerminals}；" +
                "虚拟屏 ${resources.activeVirtualDisplays}/${resources.budget.maxVirtualDisplays}；" +
                "语言服务 ${resources.activeLanguageServers}/${resources.budget.maxLanguageServers}；" +
                "压力 ${resources.pressure.name.lowercase()}",
        )
        appendLine("上下文：$contextChars/$contextBudgetChars 字符")
        appendLine("待处理补充消息：$pendingInputs/$pendingInputLimit")
        appendLine("可执行命令：${if (commands.isEmpty()) "未检测到" else commands.joinToString()}")
        appendLine("内置运行时：${runtimeStatuses.joinToString("；")}")
        appendLine("Shell 与 process_exec 共享内置运行时 PATH/环境；Git hooks 默认禁用。")
        appendLine("限制：应用沙箱无法访问其他 App 私有目录；语言服务器等以实际检测结果为准。")
        val warnings = recentDiagnostics.filter { it.level == "W" || it.level == "E" }.takeLast(20)
        if (warnings.isNotEmpty()) {
            appendLine("最近诊断：")
            warnings.forEach { entry ->
                append("- ${entry.level}/${entry.tag}：")
                append(entry.message.replace("\n", " ").take(300))
                entry.throwableType?.let { type ->
                    append(" [$type")
                    entry.throwableMessage?.takeIf(String::isNotBlank)?.let {
                        append(": ${it.replace("\n", " ").take(160)}")
                    }
                    append("]")
                }
                appendLine()
            }
        }
        append("替代路径：优先使用内置 read/write/edit/glob/grep/web_* 与 json_query；web_fetch 大响应会自动落盘。外部文件可从输入栏附件导入工作区。")
    }
}
