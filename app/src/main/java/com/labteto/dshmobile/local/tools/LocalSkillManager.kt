package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.parseLocalSkillMetadata
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

/** Preset instructions use the existing SKILL.md model tool contract. */
internal data class LocalPresetSkill(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
    val installed: Boolean = false,
)

internal object LocalPresetSkillCatalog {
    val entries = listOf(
        LocalPresetSkill("writing-polish", "写作润色", "改善文笔、逻辑和节奏，保留原意与风格。",
            """
            1. 先提取写作目的、受众、原文语气及不可更改的内容。
            2. 检查歧义、重复、语病、信息缺失与节奏；不得凭空添加事实或剧情。
            3. 给出能直接使用的修订稿，保留人物口吻和专有名词。
            4. 只在必要时说明关键修改与待作者决定的歧义。
            """.trimIndent()),
        LocalPresetSkill("longform-outline", "长文与小说大纲", "设计人物、冲突、章节推进与伏笔回收。",
            """
            1. 确定题材、读者、主线目标、篇幅、人物欲望和已确立世界规则。
            2. 用起因、行动、阻力、代价、转折、收束搭建连续推进的情节链。
            3. 记录主要人物动机、能力边界、重要选择及成长，并校验连续性。
            4. 交付分阶段大纲与关键伏笔回收表，避免重复事件和无代价转折。
            5. 对已有章节先核实原文；未知条件须标为待确定，不自行改动设定。
            """.trimIndent()),
        LocalPresetSkill("document-summary", "文档提炼", "提取材料结论、待办、风险和可复核依据。",
            """
            1. 使用当前可用的文件工具读取材料；若只读到部分内容，标注范围。
            2. 分清原文事实、作者观点、推断和未定事项。
            3. 先给核心结论，再按需要整理依据、行动事项、责任人与风险。
            4. 回查日期、数字、姓名与约束，保留文件及段落位置。
            5. 严禁杜撰未阅读的章节、引文或决定。
            """.trimIndent()),
        LocalPresetSkill("research-check", "资料研究与核实", "交叉查证事实、来源与时效性。",
            """
            1. 明确待核实主张和时间范围，分辨哪些结论要求最新资料。
            2. 优先使用官方和一手来源，用现有联网或文件工具取得证据。
            3. 逐项对比事实、推断、争议和无法验证的部分。
            4. 给出结论、可回查来源及其日期，指出适用边界。
            5. 缺少联网或原始证据时直接说明，不虚构来源或结论。
            """.trimIndent()),
        LocalPresetSkill("code-review", "代码审查", "核查改动正确性、安全边界及回归问题。",
            """
            1. 读取相关代码、调用链、测试和仓库权威规范，确认目标与基线。
            2. 沿输入、状态、执行、副作用、错误处理和恢复路径核对缺陷。
            3. 明确列出可复现问题、代码位置、触发条件与影响，按严重度排序。
            4. 使用现有测试工具验证关键问题，不得把静态阅读称为测试通过。
            5. 修复建议应指向根因并覆盖横向关联和纵向链路。
            """.trimIndent()),
        LocalPresetSkill("bug-investigation", "故障定位", "追踪异常根因并验证修复。",
            """
            1. 记录现象、复现条件、时间线、最近变动和期望结果。
            2. 用现有日志与代码工具追踪首个异常状态及上游来源。
            3. 分清报错表象与根因，设计能证伪不同假设的检查步骤。
            4. 处理后检查正常、异常、并发、取消及恢复路径。
            5. 最后区分已验证结果与仍待验证的部分。
            """.trimIndent()),
        LocalPresetSkill("data-insights", "数据分析", "核算指标、整理趋势与异常，说明统计口径。",
            """
            1. 明确数据来源、单位、口径、缺失值与分析时间范围。
            2. 用可用工具实际计算，检查重复记录、分母和汇总方式。
            3. 解释趋势、分组差异与异常，避免将相关性当成因果。
            4. 提供关键发现、支持数字、限制条件和下一步建议。
            5. 假设、预测和观测值必须清晰区分。
            """.trimIndent()),
        LocalPresetSkill("translation-localization", "翻译与本地化", "保持语义准确，适配目标语言的自然表达。",
            """
            1. 识别源语言、目标语言、用途、受众和语气。
            2. 忠实保留数字、专有名词、链接、格式、代码及限定条件。
            3. 使用地道目标语言，保持术语一致，避免逐字硬译。
            4. 对双关、文化背景和歧义提供简短说明或选项。
            5. 不添写原文没有的事实或立场。
            """.trimIndent()),
    )
}

internal val LOCAL_SKILL_ID_PATTERN = Regex("[a-z][a-z0-9-]{1,47}")

internal fun buildLocalSkillDocument(id: String, description: String, instructions: String): String {
    require(LOCAL_SKILL_ID_PATTERN.matches(id)) { "技能标识需由 2—48 位小写字母、数字或连字符组成，并以字母开头" }
    val summary = description.trim()
    val body = instructions.trim()
    require(summary.isNotEmpty() && summary.length <= 240 && '\n' !in summary && '\r' !in summary) {
        "技能描述需要 1—240 个字符且不能换行"
    }
    require(body.isNotEmpty() && body.length <= 12_000) { "技能说明需要 1—12000 个字符" }
    val scalar = summary.replace('"', '\'').replace(" #", " ＃")
    return listOf("---", "name: " + id, "description: \"" + scalar + "\"", "---",
        "# " + id, "", body, "").joinToString("\n")
}

/** Single writer for workspace SKILL.md files; both the UI and the model read these same files. */
internal class LocalSkillStore(private val workspace: LocalWorkspace) {
    private val skillsDir get() = File(workspace.path, ".dsh/skills")
    private val seedMarker = ".dsh/skills/.preset-seed-v1"

    fun installed(): List<LocalInstalledSkill> = workspace.skills().map { name ->
        val doc = workspace.readRaw(".dsh/skills/" + name + "/SKILL.md")
        val metadata = parseLocalSkillMetadata(name, doc)
        LocalInstalledSkill(name, metadata.description, metadata.modelInvocable)
    }

    fun presets(): List<LocalPresetSkill> {
        val names = workspace.skills().toSet()
        return LocalPresetSkillCatalog.entries.map { it.copy(installed = it.id in names) }
    }

    /** First launch only: users removing a bundled skill must not see it reappear on restart. */
    fun seedOnce() {
        if (File(workspace.path, seedMarker).exists()) return
        for (entry in LocalPresetSkillCatalog.entries) {
            if (!File(skillsDir, entry.id).exists()) install(entry.id)
        }
        workspace.write(seedMarker, "v1\n")
    }

    fun install(id: String) {
        val preset = LocalPresetSkillCatalog.entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("未知预置技能")
        addDocument(id, buildLocalSkillDocument(id, preset.description, preset.instructions))
    }

    fun create(id: String, description: String, instructions: String) {
        require(LocalPresetSkillCatalog.entries.none { it.id == id }) { "预置技能标识已保留，请使用安装功能" }
        addDocument(id, buildLocalSkillDocument(id, description, instructions))
    }

    private fun addDocument(id: String, document: String) {
        require(LOCAL_SKILL_ID_PATTERN.matches(id)) { "无效的技能标识" }
        val dir = File(skillsDir, id)
        require(!Files.isSymbolicLink(dir.toPath())) { "不能安装到符号链接目录" }
        val target = File(dir, "SKILL.md")
        require(!target.exists() && !Files.isSymbolicLink(target.toPath())) { "技能已经安装" }
        workspace.write(".dsh/skills/" + id + "/SKILL.md", document)
    }

    /** Remove only SKILL.md; never destroy extra files supplied by the user. */
    fun remove(id: String) {
        require(id.isNotBlank() && id != "." && id != ".." &&
            '/' !in id && '\\' !in id && '\u0000' !in id) { "无效的技能标识" }
        val dir = File(skillsDir, id)
        val target = File(dir, "SKILL.md")
        require(!Files.isSymbolicLink(dir.toPath()) && !Files.isSymbolicLink(target.toPath())) {
            "不能移除符号链接技能"
        }
        require(dir.canonicalFile.parentFile == skillsDir.canonicalFile &&
            target.isFile && target.canonicalFile.parentFile == dir.canonicalFile) { "技能未安装" }
        require(target.delete()) { "技能移除失败" }
        dir.delete()
    }
}

@Singleton
internal class LocalSkillManager @Inject constructor(storage: LocalSessionStorageRuntime) {
    private val store = LocalSkillStore(storage.files.workspace)
    fun initializeDefaults() = store.seedOnce()
    fun installed(): List<LocalInstalledSkill> = store.installed()
    fun presets(): List<LocalPresetSkill> = store.presets()
    fun install(id: String) = store.install(id)
    fun create(id: String, description: String, instructions: String) = store.create(id, description, instructions)
    fun remove(id: String) = store.remove(id)
}
