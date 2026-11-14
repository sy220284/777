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
    "android-runtime" to "运行终端命令并处理本地文件",
    "local-language-server" to "查找代码、检查问题和分析项目",
    "android-device" to "在授权范围内读取和操作设备",
    "local-vision" to "分析图片、屏幕和视觉内容",
    "android-automation" to "管理任务与执行自动化操作",
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
    val englishBrand = name.lowercase() in proprietaryNames ||
        name.startsWith("MiniMax ") || name.startsWith("WPS ") ||
        name.startsWith("GitHub ") || name.startsWith("PDF ") ||
        name.startsWith("Excel ") || name.startsWith("Word ")
    val title = when {
        containsHan(name) -> name
        englishBrand -> name
        localizedTitles.containsKey(key) -> localizedTitles.getValue(key)
        kind == ExtensionKind.SKILL -> "自定义技能"
        kind == ExtensionKind.CONNECTOR -> "外部连接器"
        else -> "本地插件"
    }
    val description = when {
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
