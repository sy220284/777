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
            1. 读取 .dsh/skills/minimax-media/MOBILE.md；按需读取 CLI.md 的对应媒体接口说明。
            2. 核对用户选定的媒体类型、风格、内容与已配置的服务权限；调用当前可用的生成入口。
            3. 对异步任务查询完成状态，成功后下载并检查真实产物。
            交付：媒体文件、可预览路径以及实际任务状态；需要额外媒体 API 权限时说明具体条件。
        """.trimIndent(), whenToUse = "MiniMax 图片、视频、语音及音乐 API 生成"),
        LocalPresetSkill("minimax-music-gen", "MiniMax 音乐创作", "创作歌曲、伴奏、曲风方案并生成音乐文件。", """
            用途：将主题、曲风、节奏、情绪和声音要求转化为歌曲或伴奏。
            适用：歌曲、伴奏、音乐片段、曲风探索或音乐生成任务。
            执行：
            1. 读取 .dsh/skills/minimax-music-gen/MOBILE.md，明确目标时长、曲风、是否有人声和歌词需求。
            2. 形成可执行的音乐生成参数，使用已配置且有权限的音乐生成能力。
            3. 查询真实生成状态，下载并检查输出音频。
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
