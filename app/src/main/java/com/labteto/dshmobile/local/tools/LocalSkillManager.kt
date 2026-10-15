package com.labteto.dshmobile.local.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.parseLocalSkillMetadata
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Preset instructions use the existing SKILL.md model tool contract. */
internal data class LocalPresetSkill(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
    val installed: Boolean = false,
    val whenToUse: String = "",
)

internal object LocalPresetSkillCatalog {
    private val triggers = mapOf(
        "writing-polish" to "润色、改写和精修段落",
        "longform-outline" to "小说大纲、人物弧线、伏笔和章节规划",
        "document-summary" to "文档总结、会议纪要和待办提取",
        "research-check" to "资料查询、来源核实和事实查证",
        "code-review" to "代码审查、PR 和回归风险",
        "imagegen" to "图像生成、图片编辑、参考图及 UI 素材创作",
        "bug-investigation" to "故障排查、日志分析和崩溃定位",
        "data-insights" to "数据统计、报表和趋势分析",
        "translation-localization" to "翻译、术语统一和本地化",
    )
    val entries = listOf(
        LocalPresetSkill("writing-polish", "写作润色", "润色文字、调整逻辑与语气，并保留作者表达。", """
            用途：对已有文章、段落、文案进行改写、校对和语言优化。
            适用：润色、改写、统一语气、精简内容及文字精修。
            执行：识别原文用途和语气；修改歧义、重复、语病与结构；核查人物、数字、专有名词及原意。
            交付：可以直接使用的修订稿；必要时简述关键修改与仍需作者确定的地方。
            """.trimIndent()),
        LocalPresetSkill("longform-outline", "长文与小说大纲", "设计人物、冲突、章节推进与伏笔回收。", """
            用途：创作或完善小说、剧本及长篇叙事的整体结构。
            适用：故事大纲、人物弧线、章节规划与伏笔安排。
            执行：确认题材和已有设定；构造动机、阻力、选择、转折与后果；梳理章节时间线和角色知情范围；安排伏笔回收。
            交付：分阶段大纲、章节事件、人物弧线及连续性提示。
            """.trimIndent()),
        LocalPresetSkill("document-summary", "文档提炼", "整理文件结论、重点、行动事项和依据。", """
            用途：从用户提供的文件或会议资料中提取可回查的信息。
            适用：摘要、会议纪要、风险梳理、行动项与资料对比。
            执行：读取相关内容并标明读取范围；区分事实、观点与推断；提取日期、数字、结论、决策与责任信息。
            交付：核心结论、重要依据及待办事项；原文未提供的字段标为未指定。
            """.trimIndent()),
        LocalPresetSkill("research-check", "资料研究与核实", "优先核对权威资料，分辨事实、版本与推断。", """
            用途：核实技术文档、模型和 API 能力、外部事件、规则和可争议事实。
            执行：
            1. 明确问题、主体、时间范围与版本；若用户指定模型/账户/平台，不擅自替换。
            2. 通过 web_search 找到一手权威来源，实际以 web_fetch 阅读正文，不能以摘要代替原文；资料就在工作区时先读本地文件。
            3. 官方 OpenAI 问题优先开发者文档和帮助中心；其他服务商优先自身官方接口文档，并核对更新日期和适用地域。
            4. 检查参数名称、接口版本、权限/额度、客户端能力、示例是否仍适用；技术结论须区分“官方支持”和“当前 777 已实现”。
            5. 交叉复查冲突信息，引用具体页面或文件位置；无法联网、来源不足或未实际验证时明确说明。
            交付：结论 → 权威证据/日期 → 与当前实现的差异 → 仍需核实的事项；禁止编造来源或把代码静态检查说成实测。
            """.trimIndent()),
        LocalPresetSkill("code-review", "代码审查", "以真实缺陷和证据为中心审查 PR 与全量代码。", """
            用途：只读审查 PR、提交、代码模块、架构与回归风险；用户要求修复时再单独执行修改。
            执行：
            1. 先核对仓库规范、审查范围和合并目标；PR 使用 merge-base 的真实差异，必要时阅读相关主线行为与调用链。
            2. 逐项追踪入口、权限、数据流、并发、失败恢复、状态持久化及下游副作用，而非只看改动文本。
            3. 有可用测试时运行针对性回归；区分复现失败、静态推断、历史主线失败及未执行测试。
            4. 只报告改动引入且有可验证触发路径的问题；全量审计则明确问题是否先前已存在，不制造无依据的提醒。
            5. 用 P0（阻断发布）、P1（重要缺陷）、P2（一般缺陷）、P3（低影响问题）分级，给出文件行号、触发条件、影响及具体修复方向。
            6. 默认只读，不自行修改/提交/合并；若用户要求处理，则在授权能力内修改并再跑相关验证。
            交付：问题按严重度排序并附证据；无可信缺陷写“未发现确定性问题”，但不能冒充已覆盖全部运行场景。
            """.trimIndent()),
        LocalPresetSkill("imagegen", "图片创作", "使用 777 原生多模态能力创建和编辑真实图片。", """
            用途：生成图片、UI 素材、插画、图标或在已保存原图基础上进行编辑；不是文本提示词生成器。
            执行：
            1. 判断新图/原图编辑；明确主体、构图、风格、比例、参考素材及必须不变的细节。
            2. Work 模式先调用 media_image_options 获取已配置且有 API Key 的图像服务；用户指定服务或账户时严格遵守，不得自动换其他付费路由。
            3. 只有用户明确要求真实创作且经过原有工具审批，才使用 media_image_create 并传入实际 offer_id；原图编辑必须有当前会话已保存图片 ID，且由该服务商实际支持，不能冒充编辑。
            4. 工具只返回异步提交回执；通过 media_image_status 核对真实任务状态，再到当前会话媒体成果区确认文件。未成功时绝不能声称图片已生成。
            5. 长任务不重复提交以免重复扣费；需要批量时按用户授权逐个申请并跟进，避免静默并发收费。
            6. Chat 模式的 OpenAI 内建图像工具与独立媒体创作界面属于不同路径，按实际可用功能调用，不把一个模式的能力虚报为另一个模式已具备。
            交付：真实生成图片和可查看位置、实际服务商/模型、任务状态；无法执行时说明缺少哪项能力或权限。
            """.trimIndent()),
        LocalPresetSkill("bug-investigation", "故障定位", "追踪异常根因、提出修复并验证结果。", """
            用途：定位用户可见的错误、崩溃、超时与异常行为。
            适用：日志分析、失败复现、故障排查与修复回归。
            执行：收集现象和触发步骤；追踪日志、调用链及状态转移；对比假设；修复后按原场景与关键边界验证。
            交付：根因、证据、实际修复、回归结果以及仍待验证的条件。
            """.trimIndent()),
        LocalPresetSkill("data-insights", "数据分析", "统计指标、复核数据并解释趋势和异常。", """
            用途：从提供的数据中形成可核算、可追溯的分析结果。
            适用：数据清洗、统计、表格分析、指标比较和趋势解释。
            执行：确认来源、单位、时间范围与计算口径；使用可用工具计算并复核；分析趋势、差异和异常，区分相关性与因果。
            交付：关键指标、计算依据、可视化或表格及结论边界。
            """.trimIndent()),
        LocalPresetSkill("translation-localization", "翻译与本地化", "准确翻译文本并适配目标语言语境。", """
            用途：将文本转换为目标语言，保留意思、格式与专业术语。
            适用：多语言翻译、字幕文案、产品本地化和术语统一。
            执行：确定语言、受众、语气及用途；翻译并统一术语；核对数字、姓名、链接、格式和语义。
            交付：可直接使用的译文；必要时说明有歧义的表达及译法选择。
            """.trimIndent()),
    ).map { it.copy(whenToUse = triggers[it.id].orEmpty()) } + LocalMiniMaxSkillCatalog.entries
}

internal val LOCAL_SKILL_ID_PATTERN = Regex("[a-z][a-z0-9-]{1,47}")

internal fun buildLocalSkillDocument(id: String, description: String, instructions: String, whenToUse: String = "", displayName: String = ""): String {
    require(LOCAL_SKILL_ID_PATTERN.matches(id)) { "技能标识需由 2—48 位小写字母、数字或连字符组成，并以字母开头" }
    val title = displayName.trim()
    require(title.isEmpty() || (title.length <= 40 && title.any { it in '\u4e00'..'\u9fff' } && '\n' !in title && '\r' !in title)) {
        "技能显示名称需包含中文，且不超过40字"
    }
    val summary = description.trim()
    val body = instructions.trim()
    require(summary.isNotEmpty() && summary.length <= 240 && '\n' !in summary && '\r' !in summary) {
        "技能描述需要 1—240 个字符且不能换行"
    }
    require(body.isNotEmpty() && body.length <= 12_000) { "技能说明需要 1—12000 个字符" }
    val scalar = summary.replace('"', '\'').replace(" #", " ＃")
    require(whenToUse.length <= 240 && '\n' !in whenToUse && '\r' !in whenToUse) { "使用条件不能超过 240 字或换行" }
    val trigger = if (whenToUse.isBlank()) emptyList() else listOf("when-to-use: \"" + whenToUse.replace('"', '\'') + "\"")
    val displayField = if (title.isEmpty()) emptyList() else listOf("display-name: \"" + title.replace('"', '\'') + "\"")
    return (listOf("---", "name: " + id, "description: \"" + scalar + "\"") +
        displayField + trigger + listOf("---", "# " + id, "", body, "")).joinToString("\n")
}

/** Single writer for workspace SKILL.md files; both the UI and the model read these same files. */
internal class LocalSkillStore(
    private val workspace: LocalWorkspace,
    private val bundleFiles: ((String) -> Map<String, String>)? = null,
) {
    private val skillsDir get() = File(workspace.path, ".dsh/skills")
    private val seedMarker = ".dsh/skills/.preset-seed-v1"

    fun installed(): List<LocalInstalledSkill> = workspace.skills().map { name ->
        try {
            val metadata = workspace.skillMetadata(name)
            val doc = workspace.readSkill(name)
            val display = metadata.displayName
                ?: LocalPresetSkillCatalog.entries.firstOrNull { it.id == name }?.title
                ?: doc.lineSequence().firstOrNull { it.trimStart().startsWith("# ") }
                    ?.trim()?.removePrefix("# ")?.trim()
                    ?.takeIf { it.length in 1..40 && it.any { c -> c in '\u4e00'..'\u9fff' } }
                ?: metadata.description.substringBefore('，').substringBefore('。').trim()
                    .takeIf { it.length in 2..30 && it.any { c -> c in '\u4e00'..'\u9fff' } }
                ?: "自定义技能（$name）"
            LocalInstalledSkill(name, metadata.description, metadata.modelInvocable, display)
        } catch (error: Exception) {
            com.labteto.dshmobile.observability.AppLog.warn("LocalSkills", "技能条目无法读取：$name", error)
            LocalInstalledSkill(name, "无法读取，请修复或删除此技能", false, "自定义技能（$name）")
        }
    }

    fun presets(): List<LocalPresetSkill> {
        val names = workspace.skills().toSet()
        return LocalPresetSkillCatalog.entries.map { it.copy(installed = it.id in names) }
    }

    /** Installed official presets track app releases; removed and custom skills remain untouched. */
    private fun refreshInstalledBuiltIns() {
        val installedIds = workspace.skills().toSet()
        for (preset in LocalPresetSkillCatalog.entries) {
            if (preset.id !in installedIds) continue
            requireInstalled(preset.id)
            if (preset.id in bundledIds && bundleFiles != null) {
                val files = bundleFiles(preset.id)
                require("MOBILE.md" in files && "LICENSE" in files && "SOURCE.md" in files) {
                    "MiniMax 技能资源不完整：${preset.id}"
                }
                files.forEach { (relative, text) ->
                    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                        '\\' !in relative && relative != "SKILL.md") { "技能资源路径无效" }
                    val path = ".dsh/skills/${preset.id}/$relative"
                    val target = File(workspace.path, path)
                    val marker = File(skillsDir, "${preset.id}/.bundled-resource-${rawFingerprint(relative)}")
                    val existing = runCatching { target.readText() }.getOrNull()
                    val clean = existing == text || (
                        marker.isFile && rawFingerprint(existing.orEmpty()) == marker.readText().trim()
                    )
                    // Never overwrite an untracked user-edited MOBILE.md, CLI.md or other asset.
                    if (existing == null || clean) {
                        if (existing != text) workspace.write(path, text)
                        marker.writeText(rawFingerprint(text) + "\n")
                    }
                }
            }
            val baseDocument = buildLocalSkillDocument(
                preset.id, preset.description, preset.instructions, preset.whenToUse, preset.title,
            )
            val path = ".dsh/skills/${preset.id}/SKILL.md"
            val current = runCatching { workspace.readSkill(preset.id) }.getOrNull()
            val disabled = runCatching { !workspace.skillMetadata(preset.id).modelInvocable }.getOrDefault(false)
            val desired = withModelInvocationDisabled(baseDocument, disabled)
            val baseline = File(skillsDir, "${preset.id}/.bundled-baseline-sha256")
            val previousBaseline = runCatching { baseline.readText().trim() }.getOrNull()
            val clean = current != null && (
                // Known upstream version: upgrade only if the user never edited its content.
                (previousBaseline != null && skillFingerprint(current) == previousBaseline) ||
                    // Legacy installation without a baseline: don't erase an unknown user edit.
                    (previousBaseline == null && current == desired)
                )
            val validCustomDocument = current != null &&
                current.toByteArray(Charsets.UTF_8).size <= 24_000 &&
                current.lineSequence().firstOrNull()?.trim() == "---" &&
                parseLocalSkillMetadata(preset.id, current).description.isNotBlank()
            if (validCustomDocument && !clean && current != desired) {
                // A valid local override wins over bundled updates, including legacy
                // installations created before the fingerprint marker existed.
                continue
            }
            if (current != null && !clean && current != desired) {
                val raw = runCatching { File(skillsDir, "${preset.id}/SKILL.md").readText() }.getOrNull()
                if (raw != null && raw != desired) {
                    val backup = File(skillsDir, "${preset.id}/SKILL.pre-repair.md")
                    if (!backup.exists()) backup.writeText(raw)
                }
            }
            if (current != desired) workspace.write(path, desired)
            baseline.writeText(skillFingerprint(baseDocument) + "\n")
        }
    }

    private fun withModelInvocationDisabled(document: String, disabled: Boolean): String =
        if (disabled) document.replaceFirst("\n---\n", "\ndisable-model-invocation: true\n---\n")
        else document

    private fun rawFingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun skillFingerprint(document: String): String {
        // Invocation is a separate user preference, not a modification of the rule body.
        val canonical = document.replace(
            Regex("(?m)^disable-model-invocation: (?:true|false)\\r?\\n"), "",
        )
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** First launch only: users removing a bundled skill must not see it reappear on restart. */
    fun seedOnce() {
        if (!File(workspace.path, seedMarker).exists()) {
            LocalPresetSkillCatalog.entries.filterNot { it.id in bundledIds }.forEach { preset ->
                if (!workspace.skills().contains(preset.id)) install(preset.id)
            }
            workspace.write(".dsh/skills/.preset-seed-v1", "seeded\n")
        }
        // Newly bundled image workflow is added once without restoring later user deletions.
        if (!File(skillsDir, ".imagegen-seed-v1").exists()) {
            if (!workspace.skills().contains("imagegen")) install("imagegen")
            workspace.write(".dsh/skills/.imagegen-seed-v1", "seeded\n")
        }
        // A separate one-time migration preserves deletions of the original presets.
        if (bundleFiles != null && !File(skillsDir, ".minimax-seed-v1").exists()) {
            LocalMiniMaxSkillCatalog.entries.forEach { preset ->
                if (!workspace.skills().contains(preset.id)) install(preset.id)
            }
            workspace.write(".dsh/skills/.minimax-seed-v1", "seeded\n")
        }
        migrateMiniMaxMobileSkills()
        archiveRetiredMiniMaxAdaptations()
        refreshInstalledBuiltIns()
    }

    /** Retire unavailable hosts and upgrade only untouched bundled instructions. */
    private fun migrateMiniMaxMobileSkills() {
        if (bundleFiles == null || File(skillsDir, ".minimax-mobile-v2").exists()) return
        val archivePath = ".dsh/retired-skills/minimax-mobile-v2"
        workspace.write("$archivePath/README.md", "已退出的桌面技能保留在此处，不再进入技能目录。用户修改和附属文件均保留。\n")
        for (id in LocalMiniMaxSkillCatalog.retiredIds) {
            if (!workspace.skills().contains(id)) continue
            requireInstalled(id)
            val destination = File(workspace.path, "$archivePath/$id")
            require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "旧技能归档位置已存在：$id" }
            Files.move(File(skillsDir, id).toPath(), destination.toPath())
        }
        for (preset in LocalMiniMaxSkillCatalog.entries) {
            if (!workspace.skills().contains(preset.id)) continue // Keep the user's deletions.
            requireInstalled(preset.id)
            val files = bundleFiles(preset.id)
            require("MOBILE.md" in files && "LICENSE" in files && "SOURCE.md" in files) { "手机技能资源不完整" }
            files.forEach { (relative, text) ->
                require(relative.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                    '\\' !in relative && relative != "SKILL.md") { "技能资源路径无效" }
                val target = File(workspace.path, ".dsh/skills/${preset.id}/$relative")
                if (!target.exists()) workspace.write(".dsh/skills/${preset.id}/$relative", text)
            }
        }
        workspace.write(".dsh/skills/.minimax-mobile-v2", "seeded\n")
    }

    /** Retire six desktop-adapted presets while preserving every user file and edit. */
    private fun archiveRetiredMiniMaxAdaptations() {
        if (bundleFiles == null) return
        val marker = File(skillsDir, ".minimax-retired-desktop-v3")
        if (marker.exists()) return
        val archivePath = ".dsh/retired-skills/minimax-desktop-v3"
        for (id in LocalMiniMaxSkillCatalog.retiredDesktopAdaptations) {
            if (!workspace.skills().contains(id)) continue
            requireInstalled(id)
            val destination = File(workspace.path, "$archivePath/$id")
            require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) {
                "已存在同名归档技能：$id"
            }
            destination.parentFile?.mkdirs()
            Files.move(File(skillsDir, id).toPath(), destination.toPath())
        }
        workspace.write(".dsh/skills/.minimax-retired-desktop-v3", "done\n")
    }

    private val bundledIds = LocalMiniMaxSkillCatalog.entries.map { it.id }.toSet()

    fun install(id: String) {
        val preset = LocalPresetSkillCatalog.entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("未知的预置技能")
        require(!workspace.skills().contains(id)) { "同名技能已经存在" }
        if (id in bundledIds) {
            val files = requireNotNull(bundleFiles) { "当前环境未提供 MiniMax 技能资源" }(id)
            require("MOBILE.md" in files && "LICENSE" in files && "SOURCE.md" in files) {
                "MiniMax 技能资源不完整"
            }
            // Publish SKILL.md last. Preserve supporting files edited by the user after removal.
            files.forEach { (relative, text) ->
                require(relative.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                    '\\' !in relative && relative != "SKILL.md") { "技能资源路径无效" }
                val path = ".dsh/skills/$id/$relative"
                val target = File(workspace.path, path)
                if (!target.exists()) workspace.write(path, text)
                if (target.readText() == text) {
                    File(skillsDir, "$id/.bundled-resource-${rawFingerprint(relative)}")
                        .writeText(rawFingerprint(text) + "\n")
                }
            }
        }
        val bundledDocument = buildLocalSkillDocument(id, preset.description, preset.instructions, preset.whenToUse, preset.title)
        addDocument(id, bundledDocument)
        File(skillsDir, "$id/.bundled-baseline-sha256").writeText(skillFingerprint(bundledDocument) + "\n")
    }

    fun create(id: String, displayName: String, description: String, instructions: String) {
        require(LocalPresetSkillCatalog.entries.none { it.id == id }) { "预置技能标识已保留，请使用安装功能" }
        require(displayName.isNotBlank()) { "请填写技能中文名称" }
        addDocument(id, buildLocalSkillDocument(id, description, instructions, displayName = displayName))
    }

    private fun addDocument(id: String, document: String) {
        require(LOCAL_SKILL_ID_PATTERN.matches(id)) { "无效的技能标识" }
        val dir = File(skillsDir, id)
        require(!Files.isSymbolicLink(dir.toPath())) { "不能安装到符号链接目录" }
        val target = File(dir, "SKILL.md")
        require(!target.exists() && !Files.isSymbolicLink(target.toPath())) { "技能已经安装" }
        workspace.write(".dsh/skills/" + id + "/SKILL.md", document)
    }


    private fun requireInstalled(id: String) {
        require(id.isNotBlank() && id != "." && id != ".." && '/' !in id && '\\' !in id && '\u0000' !in id) {
            "无效的技能标识"
        }
        val dir = File(skillsDir, id)
        val target = File(dir, "SKILL.md")
        require(dir.canonicalFile.parentFile == skillsDir.canonicalFile &&
            !Files.isSymbolicLink(dir.toPath()) && !Files.isSymbolicLink(target.toPath()) &&
            target.isFile && target.canonicalFile.parentFile == dir.canonicalFile) {
            "技能未安装或路径不安全"
        }
    }

    fun readDocument(id: String): String {
        requireInstalled(id)
        return workspace.readRaw(".dsh/skills/$id/SKILL.md").also {
            require(it.toByteArray(Charsets.UTF_8).size <= 24_000) {
                "技能说明超过 24 KB，请通过工作区文件工具编辑"
            }
        }
    }

    fun updateDocument(id: String, document: String) {
        requireInstalled(id)
        require(document.isNotBlank() && document.toByteArray(Charsets.UTF_8).size <= 24_000) {
            "技能文件不能为空，且不能超过 24 KB"
        }
        require(document.lineSequence().firstOrNull()?.trim() != "---" ||
            document.lineSequence().drop(1).take(95).any { it.trim() == "---" }) {
            "技能元数据缺少结束分隔符"
        }
        workspace.write(".dsh/skills/$id/SKILL.md", document.trimEnd() + "\n")
    }

    fun setModelInvocable(id: String, enabled: Boolean) {
        val original = readDocument(id)
        val lines = original.lines().toMutableList()
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
            require(end > 0) { "技能元数据格式无效" }
            val flag = lines.subList(1, end).indexOfFirst {
                it.trim().startsWith("disable-model-invocation:")
            }
            if (flag >= 0) lines[flag + 1] = "disable-model-invocation: ${!enabled}"
            else lines.add(end, "disable-model-invocation: ${!enabled}")
            updateDocument(id, lines.joinToString("\n"))
        } else if (!enabled) {
            updateDocument(id, "---\ndisable-model-invocation: true\n---\n" + original)
        }
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

// FEATURE_TREE: {"id":"skills.minimax-official-bundles","path":"第一层｜产品功能与界面 / 工具与扩展功能 / 技能 / 预置技能目录","owner":"TOOLS","entry":"LocalSkillStore","test":"app/src/test/java/com/labteto/dshmobile/local/tools/LocalMiniMaxSkillBundleTest.kt#upgradeInstallsCompleteBundlesAndPreservesEditsAndDeletions"}
@Singleton
internal class LocalSkillManager @Inject constructor(
    storage: LocalSessionStorageRuntime,
    @ApplicationContext context: Context,
) {
    private val storageWorkspace = storage.files.workspace
    private val store = LocalSkillStore(storageWorkspace) { id ->
        val files = linkedMapOf<String, String>()
        fun readDirectory(relative: String) {
            val path = "minimax-skills/$id" + if (relative.isEmpty()) "" else "/$relative"
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                files[relative] = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else children.forEach { child -> readDirectory(if (relative.isEmpty()) child else "$relative/$child") }
        }
        readDirectory("")
        files
    }
    fun initializeDefaults() = store.seedOnce()
    fun installed(): List<LocalInstalledSkill> = store.installed()
    fun presets(): List<LocalPresetSkill> = store.presets()
    fun install(id: String) = store.install(id)
    fun create(id: String, displayName: String, description: String, instructions: String) =
        store.create(id, displayName, description, instructions)
    fun importSkill(filename: String, bytes: ByteArray): String = LocalSkillPackageImporter(storageWorkspace).install(filename, bytes)
    fun remove(id: String) = store.remove(id)
    fun readDocument(id: String) = store.readDocument(id)
    fun updateDocument(id: String, document: String) = store.updateDocument(id, document)
    fun setModelInvocable(id: String, enabled: Boolean) = store.setModelInvocable(id, enabled)
}
