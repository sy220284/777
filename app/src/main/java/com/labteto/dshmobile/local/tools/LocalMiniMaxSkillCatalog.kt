package com.labteto.dshmobile.local.tools

/** Mobile execution guides replace the desktop-host workflows. */
internal object LocalMiniMaxSkillCatalog {
    val retiredIds = setOf("minimax-android", "minimax-ios", "minimax-react-native", "minimax-flutter", "minimax-pdf", "minimax-gif")

    fun legacyInstructions(id: String): String = """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/${id}/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
    """.trimIndent()

    val entries = listOf(
        LocalPresetSkill("minimax-frontend", "MiniMax 前端设计", "网页、落地页、动画与前端实现。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-frontend/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "网页、落地页、动画与前端实现"),
        LocalPresetSkill("minimax-fullstack", "MiniMax 全栈开发", "前后端、数据库与完整应用开发。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-fullstack/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "前后端、数据库与完整应用开发"),
        LocalPresetSkill("minimax-shader", "MiniMax 着色器设计", "Shader、GLSL 与视觉效果。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-shader/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "Shader、GLSL 与视觉效果"),
        LocalPresetSkill("minimax-pptx", "MiniMax 演示文稿", "PPT、幻灯片与演示文稿。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-pptx/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "PPT、幻灯片与演示文稿"),
        LocalPresetSkill("minimax-xlsx", "MiniMax 表格处理", "Excel、表格、公式与数据分析。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-xlsx/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "Excel、表格、公式与数据分析"),
        LocalPresetSkill("minimax-docx", "MiniMax Word 文档", "Word、DOCX、报告与文档排版。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-docx/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "Word、DOCX、报告与文档排版"),
        LocalPresetSkill("minimax-media", "MiniMax 多媒体工具", "MiniMax 图片、视频、语音与音乐 API。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-media/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "MiniMax 图片、视频、语音与音乐 API"),
        LocalPresetSkill("minimax-music-gen", "MiniMax 音乐创作", "歌曲、伴奏与音乐生成。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-music-gen/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "歌曲、伴奏与音乐生成"),
        LocalPresetSkill("minimax-playlist", "MiniMax 歌单创作", "歌单、曲风与封面创作。", """
来源：MiniMax-AI/skills，基于固定版本 60aaae52 的设计资料，由 777 改造成手机执行流程。
先读取 .dsh/skills/minimax-playlist/MOBILE.md，按照其中手机端实际可用的文件、Python、Node 和预览流程执行；详细资料按该文档链接读取。
本技能不提供桌面 SDK、.NET、LibreOffice、桌面浏览器或 ffmpeg 流程。不要安装这些桌面宿主补齐流程，也不要宣称已经完成未执行的构建或渲染。
产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。
        """.trimIndent(), whenToUse = "歌单、曲风与封面创作"),
    )
}
