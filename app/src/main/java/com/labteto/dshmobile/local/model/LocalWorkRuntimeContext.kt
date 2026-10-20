package com.labteto.dshmobile.local.model

/** Current-route facts; specialised tool instructions are injected only for relevant tasks. */
internal fun withWorkRuntimeContext(
    context: String,
    workspacePath: String,
    model: String,
    baseUrl: String,
    profile: LocalModelProfile?,
    configuredProfiles: List<LocalModelProfile> = emptyList(),
    task: String = "",
): String {
    val routedModel = model.takeIf(String::isNotBlank) ?: profile?.model.orEmpty()
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, routedModel, baseUrl)
    }
    val asksModel = Regex("模型|model|路由|切换|认证|账户|账号|凭据|apikey|api.key|profile|额度|配额|quota|provider", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val asksMedia = Regex("媒体|多模态|图像生成|生成图片|视频生成|生成视频|语音生成|配音|音乐生成|生成音乐|image generation|video generation|audio generation", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val asksTools = Regex("工具|能力|插件|mcp|skill|技能|可用什么|自检|环境|capability|available tools", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val asksHistory = Regex("历史|之前|上次|过去|恢复|追溯|session|checkpoint|history", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val asksUsage = Regex("token|模型.{0,10}(消耗|用量|账单|统计|预算)|(?:消耗|用量|账单|统计|预算).{0,10}(模型|token|上下文)|上下文.{0,8}(用量|窗口|预算)|usage", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val asksCode = Regex("代码|工作区|文件|仓库|脚本|终端|构建|测试|github|git|npm|python|node|shell|bash|code|build", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val runtime = buildString {
        appendLine("【当前运行时事实】")
        appendLine("- 产品：777 本机 Harness · 工作模式")
        appendLine("- 工作区：$workspacePath")
        appendLine("- 当前路由模型：$routedModel")
        profile?.provider?.takeIf(String::isNotBlank)?.let { appendLine("- 模型提供方：$it") }
        profile?.let { appendLine("- 认证来源：${it.sourceLabel()}") }
        protocol?.let { appendLine("- 模型协议：${it.name}") }
        appendLine("- 能力以本轮附带的工具为准；缺少任务所需工具时可使用 capability_search 查找，调用前核实权限和实际可用性。")
        appendLine("- 多步骤工具任务在关键阶段可以先公开说明一句接下来要核查或处理的具体对象；按用户语言自然表达，避免重复口号、原始命令、内部工具标识、敏感参数及私有推理。正文结论保持完整，已完成与未完成只按真实工具回执判断。")
        if (asksModel) {
            if (configuredProfiles.isNotEmpty()) {
                val shown = configuredProfiles.take(16).joinToString("；") { saved ->
                    "${saved.model.take(64)}（profileId=${saved.id.take(100)}）"
                }
                appendLine("- 已保存模型档案（不含密钥）：$shown")
                if (configuredProfiles.size > 16) appendLine("- 另有 ${configuredProfiles.size - 16} 个档案，可用 list_subagent_models 查完整清单。")
            }
            appendLine("- 模型配置和凭据由应用管理，可用 list_subagent_models 核查档案与服务商目录；目录存在不代表调用已成功，ChatGPT 套餐不等于 API Key。")
        }
        if (asksMedia) {
            appendLine("- 媒体输入与生成服务是不同能力。用实际注册的媒体工具和已授权 API 核实能否生成，不凭是否存在桌面 CLI 判断。")
        }
        if (asksTools) {
            appendLine("- 自检工具和插件以 capability_search 查询的实时注册表及实际连接状态为准；可发现不等于已授权或执行成功。")
        }
        if (asksCode) {
            appendLine("- 需要本机工具操作流程时，先读取 .dsh/tools/777-runtime-guide.md；使用范围明确的文件与执行工具，以实际运行结果验证。")
        }
        if (asksHistory) {
            appendLine("- 较早精确事实用 session_event_search，完整旧工具输出按 call_id 用 tool_output_read 获取，优先复用已有结果。")
        }
        if (asksUsage) {
            appendLine("- Token/上下文账本以 environment_info 中的 TokenUsageAnalyticsStore 为准，按实际数据核算。")
        }
        append("用户要求检查、修改或验证时，依据实际工具结果答复；未执行的步骤和未确认的结果须如实标明。")
    }.trimEnd()
    return listOf(runtime, context.trim()).filter(String::isNotBlank).joinToString("\n\n")
}
