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
        LocalPresetSkill("research-check", "资料研究与核实", "检索资料、核实主张、对比证据与时效。", """
            用途：研究问题并检验关键结论是否由可靠证据支持。
            适用：资料查询、官方文档、产品/技术调查与事实核验。
            执行：确定问题、版本与时效；优先检索并实际读取官方正文（OpenAI 问题优先官方开发者文档）；逐项核对证据与适用条件，避免依据搜索摘要猜测 API 细节。
            交付：结论、精确来源链接及日期、冲突观点和待核查事项。
            """.trimIndent()),
        LocalPresetSkill("code-review", "代码审查", "检查代码正确性、回归风险及实现质量。", """
            用途：沿业务调用链审查实现并发现有证据支持的问题。
            适用：代码审查、PR 评审、重构评估、架构和测试覆盖。
            执行：PR 审查按实际合并基点读取差异；全量审计按项目模块和调用链检查。追踪输入、状态、副作用、异常与测试路径，定位本次变更引入的真实缺陷；允许使用只读子代理并行核对。
            交付：P0—P3 级问题、精确代码位置、触发条件、修复建议与验证状态；没有可信发现时明确说明。
            """.trimIndent()),
        LocalPresetSkill("imagegen", "图片创作", "使用已配置的模型生成和编辑真实图片。", """
            用途：创建插画、UI 素材、图标、照片，以及根据参考图修改已有图片。
            适用：图片生成、参考图创作、原图局部编辑与批量素材需求。
            执行：整理主体、构图、风格和保持不变的元素；选择现有多模态创作能力实际支持的模型。编辑时传入真实原图，遵守用户指定账户和模型，不静默切换到其他付费服务。检查产出并按需调整。
            交付：在当前聊天展示、保存可预览的真实图片及必要的版本说明。
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
                    if (!target.exists() || target.readText() != text) workspace.write(path, text)
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
                if (!File(workspace.path, path).exists()) workspace.write(path, text)
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
