package com.labteto.dshmobile.local.tools

/** Chinese activation metadata; original instructions/resources are loaded only when a skill is used. */
internal object LocalMiniMaxSkillCatalog {
    val entries = listOf(
        LocalPresetSkill("minimax-frontend", "MiniMax 前端设计", "网页、落地页、动画与前端实现。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-frontend/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "网页、落地页、动画与前端实现"),
        LocalPresetSkill("minimax-fullstack", "MiniMax 全栈开发", "前后端、数据库与完整应用开发。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-fullstack/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "前后端、数据库与完整应用开发"),
        LocalPresetSkill("minimax-android", "MiniMax 安卓开发", "Android 原生应用、Compose 与交互。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-android/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "Android 原生应用、Compose 与交互"),
        LocalPresetSkill("minimax-ios", "MiniMax 苹果应用", "iOS 应用、Swift 与界面实现。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-ios/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "iOS 应用、Swift 与界面实现"),
        LocalPresetSkill("minimax-react-native", "MiniMax 跨平台原生", "React Native 与跨平台手机应用。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-react-native/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "React Native 与跨平台手机应用"),
        LocalPresetSkill("minimax-flutter", "MiniMax Flutter 开发", "Flutter 页面、组件与跨平台应用。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-flutter/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "Flutter 页面、组件与跨平台应用"),
        LocalPresetSkill("minimax-shader", "MiniMax 着色器设计", "Shader、GLSL 与视觉效果。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-shader/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "Shader、GLSL 与视觉效果"),
        LocalPresetSkill("minimax-pptx", "MiniMax 演示文稿", "PPT、幻灯片与演示文稿。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-pptx/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "PPT、幻灯片与演示文稿"),
        LocalPresetSkill("minimax-xlsx", "MiniMax 表格处理", "Excel、表格、公式与数据分析。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-xlsx/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "Excel、表格、公式与数据分析"),
        LocalPresetSkill("minimax-pdf", "MiniMax PDF 文档", "PDF 创建、编辑与版式检查。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-pdf/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "PDF 创建、编辑与版式检查"),
        LocalPresetSkill("minimax-docx", "MiniMax Word 文档", "Word、DOCX、报告与文档排版。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-docx/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "Word、DOCX、报告与文档排版"),
        LocalPresetSkill("minimax-media", "MiniMax 多媒体工具", "MiniMax 图片、视频、语音与音乐 API。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-media/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "MiniMax 图片、视频、语音与音乐 API"),
        LocalPresetSkill("minimax-music-gen", "MiniMax 音乐创作", "歌曲、伴奏与音乐生成。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-music-gen/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "歌曲、伴奏与音乐生成"),
        LocalPresetSkill("minimax-gif", "MiniMax 动态贴纸", "GIF、动画与动态贴纸。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-gif/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "GIF、动画与动态贴纸"),
        LocalPresetSkill("minimax-playlist", "MiniMax 歌单创作", "歌单、曲风与封面创作。", """
来源：MiniMax-AI/skills 官方开源技能（MIT），固定版本 60aaae52。
使用时先读取工作区 .dsh/skills/minimax-playlist/upstream/SKILL.md，并按需读取其相邻 references、scripts 和模板；原文相对路径以 upstream 目录为基准。
777 手机端通过现有文件、终端和模型工具执行。先核实当前工具和运行环境，再选择原文中可执行的流程；不得将 Claude Code、桌面浏览器、Xcode、Android Studio、.NET 或 LibreOffice 视为已经安装。
本包提供方法、源码和配套文本资料；构建、渲染、安装依赖或调用外部 API 必须有实际执行结果。没有对应环境时，可完成源码或文档修改并清楚说明尚未运行的验证，不能宣称已构建或已渲染。
Python 和 Node 的脚本按实际依赖执行；Word 的 .NET 流程需可用环境，可按 OOXML 资料使用现有 Python 工具生成或编辑，但须另行检查文件结构和内容。
媒体调用需用户配置有效凭据及适用 API 权限。不要把聊天密钥复制进提示、代码、日志或产物；M Plan 凭据不代表图片、语音或音乐 API 权限。使用可用的凭据注入方式；缺少时明确告知配置需求。
字体和图片采用现有素材或核实许可后获取；不虚构本包拥有未附带的素材。所有交付应区分已执行结果与仍待设备、浏览器或外部 API 验证的部分。
        """.trimIndent(), whenToUse = "歌单、曲风与封面创作"),
    )
}
