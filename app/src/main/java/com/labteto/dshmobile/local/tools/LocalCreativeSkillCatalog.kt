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
            title = "UI 设计与交互",
            description = "设计、重构与审计界面视觉、组件、排版、色彩、交互和动效；包含完整范例与检查规则。",
            instructions = "读取本技能的完整 SKILL.md 及按需加载的 references、examples 和 scripts；结合当前 Android 或网页项目的实际实现条件执行。",
            whenToUse = "界面设计、Android UI、组件、弹窗、交互、动画、视觉审计及设计系统",
        ),
        LocalPresetSkill(
            id = "finesse-brief",
            title = "工作台架构师",
            description = "定义工作台的产品目标、使用场景、模块、页面层级、数据模型和交付规格。",
            instructions = "阅读完整 SKILL.md，按需查阅 references，产出可由实现方直接使用的完整工作台规格。",
            whenToUse = "工作台、能力中心、功能结构、信息架构、产品定义、数据模型和页面层级",
        ),
        LocalPresetSkill(
            id = "finesse-term",
            title = "专业术语",
            description = "将口语化的界面和交互需求转为清晰可执行的专业描述，提供本地术语与配套场景。",
            instructions = "阅读完整 SKILL.md，按需检索 data 和 scripts 中的本地术语库，先完成用户任务再补充准确的术语。",
            whenToUse = "专业术语、需求表达、组件命名、交互验收、移动端和 H5 问题描述",
        ),
        LocalPresetSkill(
            id = "frame-smith",
            title = "视频审美与动效",
            description = "策划视频画面、镜头节奏、转场与动效，提供可复用的视觉规范和视频工程模板。",
            instructions = "阅读完整 SKILL.md 与 references、templates；涉及真实渲染时核对本机运行环境与已授权工具。",
            whenToUse = "视频设计、分镜、动态视觉、转场、短视频、动效、Remotion 和视频审美",
        ),
    )
}
