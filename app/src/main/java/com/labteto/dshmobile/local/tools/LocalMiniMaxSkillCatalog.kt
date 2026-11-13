package com.labteto.dshmobile.local.tools

/** Only API-based skills with a meaningful phone execution path remain preinstalled. */
internal object LocalMiniMaxSkillCatalog {
    val retiredIds = setOf("minimax-android", "minimax-ios", "minimax-react-native", "minimax-flutter", "minimax-pdf", "minimax-gif")
    val retiredDesktopAdaptations = setOf("minimax-frontend", "minimax-fullstack", "minimax-shader", "minimax-pptx", "minimax-xlsx", "minimax-docx")

    val entries = listOf(
        LocalPresetSkill("minimax-media", "MiniMax 媒体创作", "利用已配置的 MiniMax 媒体 API 生成图片、视频、语音与音乐。", """
            用途：按用户描述创作图片、短视频、语音配音或音乐，并取得真实媒体文件。
            适用：用户明确要求媒体生成、查看任务进度或获取已有生成结果时。
            执行：
            1. 读取 .dsh/skills/minimax-media/MOBILE.md；优先使用 777 已实现的媒体 API 与成果档案，不再默认启动 Node / mmx-cli。
            2. MiniMax 生图在 Work 中用 media_image_options 选择用户指定账户、media_image_create 提交，并以 media_image_status 查询真实回执；没有配置媒体 API Key 时不冒充 M Plan 已授权。
            3. 其他媒体类型先核实是否已有原生 API 入口和执行权限；未接入时仅在用户明确同意且设备具备已安装 CLI 的情况下按 CLI.md 操作，不在任务中自动下载安装依赖。
            4. 异步生成等待真实结果并检查已保存的媒体档案；状态未知时不重复付费提交。
            交付：媒体文件、可预览路径以及实际任务状态；需要额外媒体 API 权限时说明具体条件。
        """.trimIndent(), whenToUse = "MiniMax 图片、视频、语音及音乐 API 生成"),
        LocalPresetSkill("minimax-music-gen", "MiniMax 音乐创作", "创作歌曲、伴奏、曲风方案并生成音乐文件。", """
            用途：将主题、曲风、节奏、情绪和声音要求转化为歌曲或伴奏。
            适用：歌曲、伴奏、音乐片段、曲风探索或音乐生成任务。
            执行：
            1. 读取 .dsh/skills/minimax-music-gen/MOBILE.md，确认时长、曲风、人声与歌词，并据此形成可执行参数。
            2. 先核实 777 当前是否注册了音乐生成工具；现有图像/语音 API 不能被冒充为音乐生成。没有原生音乐工具时，只能在用户明确允许且本机已具备受控 CLI 环境时走 CLI.md。
            3. 真实提交后保留任务标识，核实音频生成与保存结果；无执行条件只交付创作方案，不虚构文件。
            交付：可用音频文件和必要的创作参数；无法执行的环节标明当前状态。
        """.trimIndent(), whenToUse = "生成歌曲、伴奏或音乐片段"),
        LocalPresetSkill("minimax-playlist", "MiniMax 歌单策划", "设计主题歌单、曲风路线、播放顺序及封面概念。", """
            用途：根据场景、情绪、受众与偏好设计可直接使用的主题歌单。
            适用：歌单策划、曲目排序、音乐风格推荐和歌单封面设计。
            执行：
            1. 读取 .dsh/skills/minimax-playlist/MOBILE.md，确认主题、曲风与播放场景。
            2. 安排曲目或曲风组合、播放顺序与节奏变化；如需真实曲目资料，使用已提供的可靠来源核对。
            3. 需要封面或音频时按用户要求调用可用媒体工具，并验证产物。
            交付：歌单清单、推荐理由、播放顺序；根据任务附上已生成的媒体文件。
        """.trimIndent(), whenToUse = "主题歌单、播放列表与音乐风格策划"),
    )
}
