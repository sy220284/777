package com.labteto.dshmobile.local.tools

/**
 * Four design/motion skills bundled from the checked-in docs/skills snapshots.
 * Original rules and supporting files ship in the APK. Skills give instructions,
 * not additional Android or tool permissions.
 */
internal object LocalCreativeSkillCatalog {
    // Source directory Git trees: bump only when an included asset changes.
    // This prevents re-reading 4.68 MB of assets on every app startup.
    val bundleRevisions = mapOf(
        "finesse-ui" to "5b855340d2de0c59f9ff6f5457eb68e5dd05c4d0",
        "finesse-brief" to "cf14bf229a3c3840451e0b0a40cd2d869a4a8d2d",
        "finesse-term" to "7f28994fcb2c67b23d4ef4987db13b82ef1f746e",
        "frame-smith" to "56a15608eea8d3e19002109596b582b2c9d975d6",
    )
    val entries = listOf(
        LocalPresetSkill(
            id = "finesse-ui",
            title = "界面视觉与交互设计",
            description = "适用范围：网页、移动端界面、工作台、组件、弹窗及视觉体系的设计、重构与审查。使用方式：提供页面或组件及目标，按设计规则规划方案、制作效果并检查交互和可访问性。",
            instructions = """
                **适用范围：** 网页与移动端界面的视觉设计、组件规范、弹窗和表单、交互流程、动画效果、界面重构与质量审查。
                **使用方式：**
                1. 指定需要设计或审查的页面、组件、截图或项目，并说明用户群体、目标及限制条件。
                2. 先确认原有设计语言、平台规范和交互路径，再按任务选取本技能的设计原则、参考案例及检测方法。
                3. 输出可实施的设计方案或实际界面成果，检查布局、色彩、信息层级、触控、无障碍及动效效果。
                **使用边界：** 原技能侧重网页和移动网页；用于 Android 原生界面时，应把设计方法落实到原生组件与交互规范。
            """.trimIndent(),
            whenToUse = "界面视觉设计、配色排版、组件、弹窗、网页和移动端交互、设计系统、视觉审计与动效优化",
        ),
        LocalPresetSkill(
            id = "finesse-brief",
            title = "工作台产品规划",
            description = "适用范围：个人工作台、业务后台、数据面板及智能体控制台的功能与结构规划。使用方式：说明服务对象和核心任务，生成包含模块、页面层级、数据来源与实施范围的完整产品规格。",
            instructions = """
                **适用范围：** 个人工作台、管理后台、业务系统、数据看板和智能体控制台的产品定位、模块设计、信息结构及数据规划。
                **使用方式：**
                1. 描述产品要服务的人、要管理的对象，以及最常完成的任务。
                2. 让技能梳理首屏信息、核心模块、页面层级、数据字段与来源、首次使用体验和实施范围。
                3. 交付一份完整中文工作台规格，供界面设计及实际开发使用；如需界面成品，再调用界面视觉与交互设计技能。
                **使用边界：** 原技能主要面向网页与移动网页产品规格；用于原生 App 时应按实际系统能力调整通知、导航和数据接入设计。
            """.trimIndent(),
            whenToUse = "产品定位、工作台方案、功能拆分、信息架构、三级菜单、数据模型、冷启动与产品规格",
        ),
        LocalPresetSkill(
            id = "finesse-term",
            title = "界面术语与需求表达",
            description = "适用范围：网页与移动端界面、组件、交互、状态及动效的需求表述和术语查询。使用方式：提供原话或问题现象，匹配中文术语并改写成设计、开发和验收都能直接使用的要求。",
            instructions = """
                **适用范围：** 界面布局、触控交互、组件命名、动效、状态反馈、适配、无障碍等需求沟通与验收表达。
                **使用方式：**
                1. 直接描述想要的效果、遇到的界面问题，或粘贴看不懂的专业术语。
                2. 依据本地术语库匹配准确概念，并结合实际场景把原话改写成清楚可执行的中文需求。
                3. 需要协作时输出统一组件名称、交互条件与验收标准，优先解释与当前问题直接相关的术语。
                **使用边界：** 负责需求表达与概念澄清；具体界面设计、代码修改和成果验证需调用相应能力。
            """.trimIndent(),
            whenToUse = "界面术语查询、需求转写、组件命名、交互描述、移动端体验问题与验收要求",
        ),
        LocalPresetSkill(
            id = "frame-smith",
            title = "视频分镜与动效设计",
            description = "适用范围：产品宣传片、片头、动态展示、竖屏短视频的分镜、节奏、转场及动效方案。使用方式：提供主题、时长、画幅和素材，编排镜头时间线，按已配置的视频工具制作或交付可执行工程。",
            instructions = """
                **适用范围：** 宣传片、片头动画、产品演示、动态视觉作品与竖屏短视频的分镜、镜头节奏、画面风格、转场和效果设计。
                **使用方式：**
                1. 提供视频主题、使用场景、目标受众、时长、横竖画幅和可用素材。
                2. 选择合适的画面效果，编排分镜及逐段时间线，明确文字、镜头运动、转场和输出规格。
                3. 若设备已具备所需视频运行环境，可交付相应视频工程并按实际工具渲染和验收；否则先交付可实施的分镜与动效方案。
                **使用边界：** 本技能主要提供视频创意指导和 Remotion 工程编排；真实视频渲染需要设备已有对应运行工具与授权。
            """.trimIndent(),
            whenToUse = "视频分镜、宣传片、短视频、镜头节奏、转场动效、动态视觉及视频方案审查",
        ),
    )
}
