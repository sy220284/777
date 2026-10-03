package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.resolveLocalModelProtocol

/**
 * Adds per-turn facts that the remote model cannot reliably infer from its own execution context.
 *
 * Keep credentials out of this block. The frozen route snapshot is authoritative for model/auth
 * identity, while the tools actually attached to each request remain authoritative for capability.
 */
internal fun withWorkRuntimeContext(
    context: String,
    workspacePath: String,
    model: String,
    baseUrl: String,
    profile: LocalModelProfile?,
): String {
    val routedModel = model.takeIf(String::isNotBlank) ?: profile?.model.orEmpty()
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, routedModel, baseUrl)
    }
    val runtime = buildString {
        appendLine("【当前运行时事实】")
        appendLine("- 产品：777 本机 Harness · 工作模式")
        appendLine("- 工作区：$workspacePath")
        appendLine("- 当前路由模型：$routedModel")
        profile?.provider?.takeIf(String::isNotBlank)?.let { appendLine("- 模型提供方：$it") }
        profile?.let { appendLine("- 认证来源：${it.sourceLabel()}") }
        protocol?.let { appendLine("- 模型协议：${it.name}") }
        appendLine("- 本轮请求实际附带的工具定义是当前工具可用性的权威事实源。")
        appendLine("- 未附带的按需能力仍然存在；需要联网/下载、后台进程、目标待办、技能、记忆管理、代理协作、会话追踪、GitHub、Android、视觉、MCP/LSP、自动化或 Webhook 时先用 capability_search 发现并启用。")
        appendLine("- 工作历史按“当前有效工作状态 + 最近因果链”投影；需要较早精确事实时用 session_event_search，需要旧工具完整原文时按 call_id 用 tool_output_read，不要为了找旧结果重复执行同一探查。")
        appendLine("- 探查优先使用 glob/grep/file_inspect/read 等窄结果工具；必须用 bash 时合并同类检查并限制输出范围，状态没有变化时不要重复运行相同命令。")
        appendLine("回答当前模型、认证来源、工作区或工具能力时，以以上运行时事实和实际工具结果为准，不用模型自身无法观测的平台信息覆盖它们；ChatGPT 套餐登录不能描述成 API Key。")
        append("用户要求自检、读取、修改、执行或验证时，必须先调用对应工具并依据结果回答；需要环境自检而 environment_info 未附带时先用 capability_search 启用，读取工作区文件使用 read。没有工具结果时不得声称已检查、正常、不可访问或已完成。")
    }.trimEnd()
    return listOf(runtime, context.trim()).filter(String::isNotBlank).joinToString("\n\n")
}
