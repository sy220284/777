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
        appendLine("- 使用工具时只采用本轮实际提供的名称与 JSON 参数；已有但未列出的可选能力先用 capability_search 按精确名称查找，待下一轮工具表提供后再调用。若收到 TOOL_NOT_EXPOSED 或 INVALID_TOOL_ARGUMENTS，先看当前工具表与返回的 Schema 字段提示，不要重复猜名或擅自重放写入操作。")
        appendLine(LocalAgentCognitionGuidance.DEFAULT)
        appendLine(LocalAgentCognitionGuidance.WORK)
        appendLine("- 遇到与历史调查相关的复杂问题，可按需查找工作区现有的 自我改进/07-调查发现.md 和 01-经验与方法.md；按来源与适用条件复用，先核对原始证据状态，避免把旧假说当作事实。简单任务无需额外读取。")
        appendLine("- 大文件、源码和外部资料的检索摘要只说明找到哪里；需要据此下结论时应继续读取相关原文和调用关系，核对实际覆盖范围。读取未完成就继续分页或按需定位，不用截断摘要冒充完整检查。")
        appendLine("- 遇到值得后续追查的新线索，按需通过 capability_search 选择 work_finding，再用该工具关联已成功的调用回执记录假说、下一步调查及修正结果；仅对确有调查价值的发现记录，普通步骤不必创建发现。状态 supported 表示有证据支持，不能替代独立核验。")
        appendLine("- 新发现要先依据真实来源判断，再决定是否深入；普通失败尝试排查可恢复原因，外部副作用未知时先核对状态；不因为兴奋或有新问题自行扩大授权、创建子代理或计划外任务。")
        appendLine("- 用户明确要求独立代码审查或独立安全审查时，在 create_goal.review_requirements 中分别注明 code 或 security；仅在确有此要求时声明。审查报告、人工接纳和文件/真实测试结果各自独立，最终完成由 Work 原有目标状态规则统一判断。")
        appendLine("- 最终答复突出实际完成的工作、关键发现及证据、仍待调查的问题，工具的完整执行明细交由可展开的运行记录查看；不得以模型文字代替实际结果。")
        append("用户要求检查、修改或验证时，依据实际工具结果答复；未执行的步骤和未确认的结果须如实标明。")
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
            appendLine("- 模型配置和凭据由应用管理，可用 list_subagent_models 核查档案与服务商目录；目录存在不代表调用已成功，ChatGPT 套餐不等于 API Key。")
        }
        if (asksMedia) {
            appendLine("- 媒体输入与生成服务是不同能力。用实际注册的媒体工具和已授权 API 核实能否生成，不凭是否存在桌面 CLI 判断。")
        }
        if (asksTools) {
            appendLine("- 自检工具和插件以 capability_search 查询的实时注册表及实际连接状态为准；可发现不等于已授权或执行成功。")
        }
        if (asksCode) {
            appendLine("- 工作区清点先用本轮已暴露的高效目录扫描能力（如受授权范围内的 bash/find）；使用 list_files/glob 时必须跟随分页游标直至完整，不凭截断结果推断文件总数。已知官方文档 URL 优先直接 web_fetch，缺少地址时再 web_search。")
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
