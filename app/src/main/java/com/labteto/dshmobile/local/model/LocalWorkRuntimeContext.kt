package com.labteto.dshmobile.local.model

/** Current-route facts; specialised tool instructions are injected only for relevant tasks. */
internal data class LocalWorkRuntimeContextParts(val stable: String, val dynamic: String)

/** Static model/session rules remain cacheable; task-only guidance must be appended at the turn tail. */
internal fun workRuntimeContextParts(
    context: String,
    workspacePath: String,
    model: String,
    baseUrl: String,
    profile: LocalModelProfile?,
    configuredProfiles: List<LocalModelProfile> = emptyList(),
    task: String = "",
): LocalWorkRuntimeContextParts {
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
    val asksPhoneFiles = Regex("手机.{0,8}(下载|文件)|下载目录|安装包|Download/|\\bapk\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)
    val stable = buildString {
        appendLine("【当前运行时事实】")
        appendLine("- 产品：777 本机 Harness · 工作模式")
        appendLine("- 工作区：$workspacePath")
        appendLine("- 当前路由模型：$routedModel")
        profile?.provider?.takeIf(String::isNotBlank)?.let { appendLine("- 模型提供方：$it") }
        profile?.let { appendLine("- 认证来源：${it.sourceLabel()}") }
        protocol?.let { appendLine("- 模型协议：${it.name}") }
        appendLine("- 按动作选工具：按文件名找用 glob，按内容找用 grep，读正文用 read，局部改动用 edit，需要 Shell 语法时用 bash。需要其他能力时通过 tool_search 查当前工具与参数，然后用 tool_call 执行。独立读取可以同批发起，后续动作依赖前一步时按顺序执行，例如编辑后再运行测试。")
        appendLine(LocalAgentCognitionGuidance.DEFAULT)
        appendLine(LocalAgentCognitionGuidance.WORK)
        appendLine("- 遇到与历史调查相关的复杂问题，可按需查找工作区现有的 自我改进/07-调查发现.md 和 01-经验与方法.md；核对原始证据和当前适用条件后复用。")
        appendLine("- 用检索摘要定位大文件、源码或外部资料。需要据此下结论时应继续读取相关原文和调用关系，核对实际覆盖范围。要求全量检查时读到分页终点；定向判断可在相关原文和调用关系已核实后收束。")
        appendLine("- 遇到值得后续追查的新线索，按需通过 tool_call 调用 work_finding，再用该工具关联已成功的调用回执记录假说、下一步调查及修正结果；发现过时用 retract 与 finding_id 撤回，保留原始回执且停止复用；用 supported 标记有来源支持的发现，有独立核验要求时按对应标准核对。")
        appendLine("- 新发现要先依据真实来源判断，再决定是否深入；普通失败尝试排查可恢复原因，外部副作用未知时先核对状态；不因为兴奋或有新问题自行扩大授权、创建子代理或计划外任务。")
        appendLine("- 用户明确要求独立代码审查或独立安全审查时，在 create_goal.review_requirements 中分别注明 code 或 security；仅在确有此要求时声明。审查报告、人工接纳和文件/真实测试结果各自独立，最终完成由 Work 原有目标状态规则统一判断。")
    }.trimEnd()
    val dynamic = buildString {
        if (asksModel) {
            if (configuredProfiles.isNotEmpty()) {
                val shown = configuredProfiles.take(16).joinToString("；") { saved ->
                    "${saved.model.take(64)}（profileId=${saved.id.take(100)}）"
                }
                appendLine("- 已保存模型档案（不含密钥）：$shown")
                if (configuredProfiles.size > 16) appendLine("- 另有 ${configuredProfiles.size - 16} 个档案，可用 list_subagent_models 查完整清单。")
            }
            appendLine("- 模型配置和凭据由应用管理，可用 list_subagent_models 核查档案与服务商目录；核实某个模型能否调用时，按当前认证来源和所选模型查看实际请求结果。ChatGPT 套餐与 API Key 是两种认证方式。")
        }
        if (asksMedia) {
            appendLine("- 生成或编辑媒体时查询已配置的生成服务。异步提交后凭原任务回执查询结果并获取成果。")
        }
        if (asksTools) {
            appendLine("- 用 tool_search 查工具定义，需要确认可用性时核对当前连接、授权与调用回执。")
        }
        if (asksCode) {
            appendLine("- 工作区清点先用本轮已暴露的高效目录扫描能力（如受授权范围内的 bash/find）；使用 list_files/glob 做全量清点时，沿返回游标读完再统计。已知官方文档 URL 优先直接 web_fetch，缺少地址时再 web_search。")
            appendLine("- 需要本机工具操作流程时，先读取 .dsh/tools/777-runtime-guide.md；使用范围明确的文件与执行工具，以实际运行结果验证。")
        }
        if (asksPhoneFiles) {
            appendLine("- Android 下载目录中的其他应用文件需由用户在 Work 文件页点击「导入手机文件」，通过系统选择器授权后读取返回的「导入文件/…」路径；交付成果继续保存到 Download/神言神语，不要求用户开放全盘文件权限。")
        }
        if (asksHistory) {
            appendLine("- 较早精确事实用 session_event_search，完整旧工具输出按 call_id 用 tool_output_read 获取，优先复用已有结果。")
        }
        if (asksUsage) {
            appendLine("- Token/上下文账本以 environment_info 中的 TokenUsageAnalyticsStore 为准，按实际数据核算。")
        }
    }.trimEnd()
    return LocalWorkRuntimeContextParts(
        stable = listOf(stable, context.trim()).filter(String::isNotBlank).joinToString("\n\n"),
        dynamic = dynamic,
    )
}

/** Compatibility surface for tests and call sites that need the complete rendered instruction. */
internal fun withWorkRuntimeContext(
    context: String,
    workspacePath: String,
    model: String,
    baseUrl: String,
    profile: LocalModelProfile?,
    configuredProfiles: List<LocalModelProfile> = emptyList(),
    task: String = "",
): String {
    val parts = workRuntimeContextParts(
        context = context,
        workspacePath = workspacePath,
        model = model,
        baseUrl = baseUrl,
        profile = profile,
        configuredProfiles = configuredProfiles,
        task = task,
    )
    return listOf(parts.stable, parts.dynamic).filter(String::isNotBlank).joinToString("\n\n")
}
