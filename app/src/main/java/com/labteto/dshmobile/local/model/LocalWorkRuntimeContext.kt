package com.labteto.dshmobile.local.model



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
    configuredProfiles: List<LocalModelProfile> = emptyList(),
): String {
    val routedModel = model.takeIf(String::isNotBlank) ?: profile?.model.orEmpty()
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, routedModel, baseUrl)
    }
    val runtime = buildString {
        appendLine(WORK_USER_DIRECTIVE_PRIORITY)
        appendLine()
        appendLine("【当前运行时事实】")
        appendLine("- 产品：777 本机 Harness · 工作模式")
        appendLine("- 工作区：$workspacePath")
        appendLine("- 当前路由模型：$routedModel")
        if (configuredProfiles.isNotEmpty()) {
            val shown = configuredProfiles.take(16).joinToString("；") { saved ->
                "${saved.model.take(64)}（profileId=${saved.id.take(100)}）"
            }
            appendLine("- 已保存模型档案（不包含密钥，凭据是否有效请调用 list_subagent_models）：$shown")
            if (configuredProfiles.size > 16) appendLine("- 另有 ${configuredProfiles.size - 16} 个模型档案，请使用 list_subagent_models 获取完整实时清单。")
        }
        profile?.provider?.takeIf(String::isNotBlank)?.let { appendLine("- 模型提供方：$it") }
        profile?.let { appendLine("- 认证来源：${it.sourceLabel()}") }
        protocol?.let { appendLine("- 模型协议：${it.name}") }
        appendLine("- 模型配置和 API Key 由应用统一管理；模型可使用已配置凭据发起授权请求，但不会收到原始密钥或账户令牌，也无需在 shell 环境变量或 mmx CLI 中寻找。")
        appendLine("- 可直接使用始终可见的 list_subagent_models 查询全部已配置模型、凭据状态、profileId 与模型能力；如需查询指定 API Key 在官方服务商的模型目录，传 include_provider_models=true 和 profile_id。目录只代表服务商返回的模型，不等于已通过调用验证或自动保存为可切换路由。")
        appendLine("- 多模态模型输入能力不等于图片、视频、语音生成 API 的授权或已提供智能体工具；媒体功能必须使用宿主应用实际注册的入口验证，不能因为找不到 CLI 就断言应用内的原生接口不可用。")
        appendLine("- 本轮请求实际附带的工具定义是当前工具可用性的权威事实源。")
        appendLine("- 实际附带的工具决定当前可调用能力；需要新工具或能力自检时用 capability_search 查询实时注册表，勿将本轮未选用误判为不可用。可发现不等于已连接、获授权或可执行；使用前核实状态，需审批时先走审批。")
        appendLine("- 使用本机工具或代码分析时，先按需用 read 查阅 .dsh/tools/777-runtime-guide.md 的当前使用流程；README.md 可包含用户专属说明；不要猜命令、在 PATH 放占位脚本或盲目下载安装。实时工具和权限仍以 capability_search 与当前工具定义为准。")
        appendLine("- 工作历史按“当前有效工作状态 + 最近因果链”投影；需要较早精确事实时用 session_event_search，需要旧工具完整原文时按 call_id 用 tool_output_read，不要为了找旧结果重复执行同一探查。")
        appendLine("- Token/上下文消耗分析优先使用 environment_info 中的 TokenUsageAnalyticsStore 结构化账本；不要用 bash 全量扫描 sessions/*.events.jsonl 或 usage/token_usage.jsonl 重新拼统计。")
        appendLine("- 优先使用范围明确的 glob/grep/file_inspect/read；需要 bash 时限制输出并合并同类操作，无新证据不重复同一检查。")
        appendLine("回答当前模型、认证来源、工作区或工具能力时，以以上运行时事实和实际工具结果为准，不用模型自身无法观测的平台信息覆盖它们；ChatGPT 套餐登录不能描述成 API Key。")
        append("用户要求自检、读取、修改、执行或验证时，必须先调用对应工具并依据结果回答；需要环境自检而 environment_info 未附带时先用 capability_search 启用，读取工作区文件使用 read。没有工具结果时不得声称已检查、正常、不可访问或已完成。")
    }.trimEnd()
    return listOf(runtime, context.trim()).filter(String::isNotBlank).joinToString("\n\n")
}
