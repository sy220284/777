package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.FeatherIcons

/**
 * The display catalogue is separate from plugin IDs and SKILL.md identities.
 * A localized title is presentation only: installation, invocation, updates and search
 * continue to use the original stable IDs.
 */
internal enum class ExtensionKind { PLUGIN, SKILL, CONNECTOR }
internal enum class ExtensionGroup { OFFICE, DEVELOPMENT, CREATIVE, AUTOMATION, GENERAL }

internal data class ExtensionPresentation(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color,
    val group: ExtensionGroup,
    val documentIcon: Int? = null,
)

private val localizedTitles = mapOf(
    "aspnet-core" to "ASP.NET Core 开发",
    "chatgpt-apps" to "ChatGPT 应用开发",
    "cli-creator" to "命令行工具创建",
    "cloudflare-deploy" to "Cloudflare 部署",
    "define-goal" to "目标定义",
    "figma-code-connect-components" to "Figma 组件连接",
    "figma-create-design-system-rules" to "Figma 设计规范",
    "figma-create-new-file" to "Figma 新建文件",
    "figma-generate-design" to "Figma 设计生成",
    "figma-generate-library" to "Figma 组件库生成",
    "figma-implement-design" to "Figma 设计实现",
    "figma-use" to "Figma 操作",
    "gh-address-comments" to "GitHub 审查意见处理",
    "gh-fix-ci" to "GitHub 构建修复",
    "hatch-pet" to "动态宠物制作",
    "jupyter-notebook" to "Jupyter 笔记本",
    "linear" to "Linear 项目管理",
    "migrate-to-codex" to "迁移到 Codex",
    "netlify-deploy" to "Netlify 部署",
    "notion-knowledge-capture" to "Notion 知识归档",
    "notion-meeting-intelligence" to "Notion 会议整理",
    "notion-research-documentation" to "Notion 研究文档",
    "notion-spec-to-implementation" to "Notion 需求实现",
    "openai-docs" to "OpenAI 文档查询",
    "playwright-interactive" to "Playwright 交互测试",
    "playwright" to "Playwright 网页测试",
    "render-deploy" to "Render 部署",
    "screenshot" to "屏幕截图",
    "security-best-practices" to "安全开发规范",
    "security-ownership-map" to "安全责任梳理",
    "security-threat-model" to "安全威胁分析",
    "sentry" to "Sentry 错误追踪",
    "speech" to "语音生成",
    "transcribe" to "语音转写",
    "vercel-deploy" to "Vercel 部署",
    "winui-app" to "Windows 应用开发",
    "yeet" to "代码发布与提交",
    "code-mode-orchestration" to "代码工作流",
    "mcp-bridge" to "MCP 服务桥接",
    "github-connector" to "GitHub 连接器",
    "android-runtime" to "终端与运行环境",
    "local-language-server" to "代码分析",
    "android-device" to "设备控制",
    "local-vision" to "视觉理解",
    "android-automation" to "自动化任务",
    "android-webhook" to "事件通知",
    "android-local-builtins" to "本地技能",
    "writing-polish" to "写作润色",
    "longform-outline" to "长文与小说大纲",
    "document-summary" to "文档提炼",
    "research-check" to "资料研究与核实",
    "code-review" to "代码审查",
    "imagegen" to "图片创作",
    "bug-investigation" to "故障定位",
    "data-insights" to "数据分析",
    "translation-localization" to "翻译与本地化",
    "minimax-media" to "MiniMax 媒体创作",
    "minimax-music-gen" to "MiniMax 音乐创作",
    "minimax-playlist" to "MiniMax 歌单策划",
    "pdf" to "PDF 文档处理",
    "doc" to "Word 文档处理",
    "docx" to "Word 文档处理",
    "word" to "Word 文档处理",
    "spreadsheet" to "Excel 电子表格",
    "xlsx" to "Excel 电子表格",
    "excel" to "Excel 电子表格",
    "slides" to "演示文稿制作",
    "pptx" to "演示文稿制作",
    "frontend-design" to "前端界面设计",
    "canvas-design" to "画布设计",
    "web-artifacts-builder" to "网页作品制作",
    "webapp-testing" to "网页应用测试",
    "report" to "报告生成",
    "manual-review" to "人工审查",
    "upgrade-demo" to "升级示例技能",
    "rollback-demo" to "版本恢复示例",
    "github" to "GitHub",
    "figma" to "Figma",
    "notion" to "Notion",
    "onedrive" to "OneDrive",
    "outlook" to "Outlook",
    "microsoft-teams" to "Microsoft Teams",
    "canva" to "Canva",
    "wps" to "WPS",
    "local-mcp" to "MCP 本地服务",
)

private val localizedDescriptions = mapOf(
    "aspnet-core" to "创建、调试与维护 ASP.NET Core 应用",
    "chatgpt-apps" to "开发和集成 ChatGPT 应用",
    "cli-creator" to "设计、实现并验证命令行工具",
    "cloudflare-deploy" to "将应用和站点部署到 Cloudflare",
    "define-goal" to "梳理任务目标和交付标准",
    "figma-code-connect-components" to "连接 Figma 设计组件和代码实现",
    "figma-create-design-system-rules" to "整理 Figma 设计系统的使用规范",
    "figma-create-new-file" to "在 Figma 中创建新文件",
    "figma-generate-design" to "从需求生成可编辑的 Figma 设计",
    "figma-generate-library" to "构建 Figma 组件库与设计变量",
    "figma-implement-design" to "将 Figma 设计落实为应用界面",
    "figma-use" to "在 Figma 中查找、读取和修改设计",
    "figma" to "读取和处理 Figma 设计文件",
    "gh-address-comments" to "处理 GitHub 代码审查反馈",
    "gh-fix-ci" to "定位并修复 GitHub 构建问题",
    "hatch-pet" to "生成、验证和打包动态宠物素材",
    "jupyter-notebook" to "创建和编辑 Jupyter 分析笔记",
    "linear" to "连接 Linear 管理项目任务",
    "migrate-to-codex" to "梳理并迁移 Codex 工作流程",
    "netlify-deploy" to "将网页项目部署到 Netlify",
    "notion-knowledge-capture" to "将资料和结论整理至 Notion",
    "notion-meeting-intelligence" to "在 Notion 中整理会议资料和行动项",
    "notion-research-documentation" to "将研究成果写入 Notion 文档",
    "notion-spec-to-implementation" to "从 Notion 需求规划实施步骤",
    "openai-docs" to "查询 OpenAI 官方开发文档",
    "playwright-interactive" to "交互式检查网页和浏览器操作",
    "playwright" to "使用浏览器自动化测试网页",
    "render-deploy" to "将应用部署到 Render",
    "screenshot" to "截取和检查屏幕图像",
    "security-best-practices" to "检查常见安全问题和改进措施",
    "security-ownership-map" to "梳理代码或模块的安全责任归属",
    "security-threat-model" to "识别系统可能面对的安全威胁",
    "sentry" to "定位和分析 Sentry 错误事件",
    "speech" to "将文本转换为可用语音",
    "transcribe" to "将语音录音转写为文本",
    "vercel-deploy" to "将站点和应用部署到 Vercel",
    "winui-app" to "设计与实现 Windows 桌面应用",
    "yeet" to "检查改动并完成代码提交流程",
    "code-mode-orchestration" to "组合多项工具处理开发任务",
    "mcp-bridge" to "连接并使用已配置的 MCP 工具服务",
    "github-connector" to "连接 GitHub 仓库并进行开发协作",
    "android-runtime" to "运行终端命令并处理本地文件",
    "local-language-server" to "查找代码、检查问题和分析项目",
    "android-device" to "在授权范围内读取和操作设备",
    "local-vision" to "分析图片、屏幕和视觉内容",
    "android-automation" to "管理任务与自动执行操作",
    "android-webhook" to "接收事件并触发已配置的操作",
    "pdf" to "阅读、整理和处理 PDF 文档",
    "docx" to "创建、编辑和整理 Word 文档",
    "doc" to "创建、编辑和整理 Word 文档",
    "word" to "创建、编辑和整理 Word 文档",
    "spreadsheet" to "处理 Excel 表格、数据和公式",
    "xlsx" to "处理 Excel 表格、数据和公式",
    "excel" to "处理 Excel 表格、数据和公式",
    "slides" to "制作和编辑演示文稿",
    "pptx" to "制作和编辑演示文稿",
    "frontend-design" to "设计网页界面与交互",
    "canvas-design" to "制作视觉设计和画布作品",
    "web-artifacts-builder" to "创建可交互的网页作品",
    "webapp-testing" to "检查网页应用的功能和交互",
    "report" to "整理资料并制作报告",
    "manual-review" to "按用户要求检查和复核内容",
)

private val proprietaryNames = setOf(
    "github", "figma", "notion", "onedrive", "outlook", "microsoft teams",
    "canva", "wps", "minimax", "tripo ai", "pdf", "excel", "word", "mcp",
)

/** Raw identifiers remain available to search and in the details panel. */
internal fun extensionPresentation(
    id: String,
    rawName: String,
    rawDescription: String,
    kind: ExtensionKind,
): ExtensionPresentation {
    val key = id.lowercase()
    val name = rawName.trim()
    val englishBrand = name.lowercase() in proprietaryNames
    val title = when {
        containsHan(name) -> name
        englishBrand -> name
        localizedTitles.containsKey(key) -> localizedTitles.getValue(key)
        kind == ExtensionKind.SKILL && containsHan(rawDescription) ->
            rawDescription.trim().substringBefore('，').substringBefore('。').take(16)
        kind == ExtensionKind.SKILL -> "自定义技能"
        kind == ExtensionKind.CONNECTOR -> "外部连接器"
        else -> "本地插件"
    }
    val description = when {
        kind == ExtensionKind.PLUGIN && localizedDescriptions.containsKey(key) ->
            localizedDescriptions.getValue(key)
        containsHan(rawDescription) -> rawDescription.trim()
        localizedDescriptions.containsKey(key) -> localizedDescriptions.getValue(key)
        kind == ExtensionKind.SKILL -> "本地技能，可在详情中查看执行规则"
        kind == ExtensionKind.CONNECTOR -> "连接外部服务并使用其已提供的工具"
        else -> "已注册的本地扩展能力"
    }
    val group = when {
        key.contains("image") || key.contains("vision") || key.contains("figma") ||
            key.contains("canva") || key.contains("design") || key.contains("media") ||
            key.contains("music") || key.contains("playlist") -> ExtensionGroup.CREATIVE
        key.contains("code") || key.contains("git") || key.contains("runtime") ||
            key.contains("language-server") || key.contains("webapp") || key.contains("frontend") ||
            key.contains("bug") -> ExtensionGroup.DEVELOPMENT
        key.contains("automation") || key.contains("webhook") || key.contains("device") ->
            ExtensionGroup.AUTOMATION
        key.contains("writing") || key.contains("outline") || key.contains("document") ||
            key.contains("research") || key.contains("report") || key.contains("translation") ||
            key.contains("data") || key.contains("pdf") || key.contains("word") ||
            key.contains("doc") || key.contains("excel") || key.contains("sheet") ||
            key.contains("slides") || key.contains("pptx") || key.contains("outlook") ||
            key.contains("onedrive") || key.contains("notion") || key.contains("teams") ->
            ExtensionGroup.OFFICE
        else -> ExtensionGroup.GENERAL
    }
    val icon = when (group) {
        ExtensionGroup.OFFICE -> when {
            key.contains("data") || key.contains("sheet") || key.contains("excel") ->
                FeatherIcons.Gauge
            key.contains("translation") -> FeatherIcons.Globe
            key.contains("research") -> FeatherIcons.Search
            key.contains("writing") || key.contains("outline") -> FeatherIcons.Edit3
            key.contains("teams") -> FeatherIcons.Users
            key.contains("onedrive") -> FeatherIcons.Folder
            key.contains("outlook") -> FeatherIcons.MessageCircle
            else -> FeatherIcons.FileText
        }
        ExtensionGroup.DEVELOPMENT -> when {
            key.contains("runtime") -> FeatherIcons.Terminal
            key.contains("git") -> FeatherIcons.GitBranch
            key.contains("bug") -> FeatherIcons.AlertTriangle
            else -> FeatherIcons.Code
        }
        ExtensionGroup.CREATIVE -> when {
            key.contains("music") || key.contains("playlist") -> FeatherIcons.Sparkles
            key.contains("figma") || key.contains("canva") || key.contains("design") -> FeatherIcons.Palette
            else -> FeatherIcons.Image
        }
        ExtensionGroup.AUTOMATION -> when {
            key.contains("device") -> FeatherIcons.Device
            key.contains("webhook") -> FeatherIcons.Zap
            else -> FeatherIcons.Clock
        }
        ExtensionGroup.GENERAL -> when (kind) {
            ExtensionKind.SKILL -> FeatherIcons.BookOpen
            ExtensionKind.CONNECTOR -> FeatherIcons.Globe
            ExtensionKind.PLUGIN -> FeatherIcons.Tool
        }
    }
    val accent = when (group) {
        ExtensionGroup.OFFICE -> Color(0xFF34866A)
        ExtensionGroup.DEVELOPMENT -> Color(0xFF526FB2)
        ExtensionGroup.CREATIVE -> Color(0xFF9862B5)
        ExtensionGroup.AUTOMATION -> Color(0xFFB57A3C)
        ExtensionGroup.GENERAL -> Color(0xFF62758A)
    }
    val documentIcon = when (key) {
        "pdf" -> R.drawable.ic_ui_filepdf_light
        "spreadsheet", "xlsx", "excel" -> R.drawable.ic_ui_fileexcel_light
        "doc", "docx", "word" -> R.drawable.ic_ui_fileword_light
        else -> null
    }
    return ExtensionPresentation(title, description, icon, accent, group, documentIcon)
}

private fun containsHan(value: String): Boolean = value.any { it.code in 0x4e00..0x9fff }
