package com.labteto.dshmobile.local.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.parseLocalSkillMetadata
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

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
    /** Historical presets removed from the active directory on upgrade; user edits are archived. */
    val retiredIds = setOf(
        "document-summary", "data-insights", "translation-localization",
        "writing-polish", "bug-investigation", "longform-outline", "research-check",
    )
    private val triggers = mapOf(
        "code-review" to "代码审查、PR 和回归风险",
        "imagegen" to "图像生成、图片编辑、参考图及 UI 素材创作",
    )
    val entries = listOf(
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
    require(body.isNotEmpty()) { "技能说明不能为空" }
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

    /** Read the provenance from the same admitted skill directory; never follow a source-file symlink. */
    fun githubSourceEvidence(id: String): String? {
        require(LOCAL_SKILL_ID_PATTERN.matches(id)) { "技能编号无效" }
        requireInstalled(id)
        val source = File(skillsDir, "$id/.github-source.json")
        if (Files.notExists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.size(source.toPath()) in 1L..8192L) {
            "GitHub 技能来源记录损坏，请检查本地 .github-source.json"
        }
        return source.readText(Charsets.UTF_8)
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
                    val targetPath = target.toPath()
                    val existing = when {
                        Files.notExists(targetPath, LinkOption.NOFOLLOW_LINKS) -> null
                        Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS) -> target.readText()
                        else -> error("技能资源不可读取：$path")
                    }
                    val clean = existing == text || (
                        existing != null && marker.isFile &&
                            rawFingerprint(existing) == marker.readText().trim()
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
            val original = File(skillsDir, "${preset.id}/SKILL.md")
            val originalPath = original.toPath()
            val originalExists = Files.isRegularFile(originalPath, LinkOption.NOFOLLOW_LINKS)
            require(originalExists || Files.notExists(originalPath, LinkOption.NOFOLLOW_LINKS)) {
                "技能规则文件不可读取：${preset.id}"
            }
            val current = if (!originalExists) null else workspace.readSkill(preset.id)
            // Read the already materialized text: malformed long lines are damaged content,
            // not a reason to fail before the bundled repair can preserve a backup.
            val metadata = current?.let { parseLocalSkillMetadata(preset.id, it) }
            val disabled = metadata?.modelInvocable == false
            val desired = withModelInvocationDisabled(baseDocument, disabled)
            val baseline = File(skillsDir, "${preset.id}/.bundled-baseline-sha256")
            val previousBaseline = when {
                Files.notExists(baseline.toPath(), LinkOption.NOFOLLOW_LINKS) -> null
                Files.isRegularFile(baseline.toPath(), LinkOption.NOFOLLOW_LINKS) -> baseline.readText().trim()
                else -> error("技能升级基线不可读取：${preset.id}")
            }
            val clean = current != null && (
                // Known upstream version: upgrade only if the user never edited its content.
                (previousBaseline != null && skillFingerprint(current) == previousBaseline) ||
                    // Legacy installation without a baseline: don't erase an unknown user edit.
                    (previousBaseline == null && current == desired)
                )
            // Bundled upgrades may replace only known unchanged originals. Valid customized
            // rules must survive regardless of their length.
            val validCustomDocument = current != null &&
                current.lineSequence().firstOrNull()?.trim() == "---" &&
                metadata?.description?.isNotBlank() == true
            if (validCustomDocument && !clean && current != desired) {
                // A valid local override wins over bundled updates, including legacy
                // installations created before the fingerprint marker existed.
                continue
            }
            if (originalExists && current != null && !clean && current != desired) {
                // Preserve original bytes before repairing an invalid or damaged rule file.
                val backup = File(skillsDir, "${preset.id}/SKILL.pre-repair.md")
                if (Files.notExists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(originalPath, backup.toPath())
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
        archiveRetiredGeneralPresets()
        refreshInstalledBuiltIns()
    }

    /**
     * Retire the seven historical general presets once per device, preserving original
     * SKILL.md, user edits and accompanying files. The marker is committed last so an
     * interrupted migration can finish on the next startup.
     */
    private fun archiveRetiredGeneralPresets() {
        val marker = File(skillsDir, ".general-presets-retired-v1")
        if (marker.exists()) return
        val archive = File(workspace.path, ".dsh/retired-skills/general-presets-v1")
        for (id in LocalPresetSkillCatalog.retiredIds) {
            val source = File(skillsDir, id)
            if (Files.notExists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            require(Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                source.canonicalFile.parentFile == skillsDir.canonicalFile) {
                "旧技能目录不可安全归档：$id"
            }
            // A previously uninstalled skill may have remaining user attachments,
            // even though SKILL.md was removed. Archive these too.
            if (Files.exists(File(source, "SKILL.md").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                requireInstalled(id)
            }
            val destination = File(archive, id)
            require(Files.notExists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "旧技能归档位置已存在且源目录尚未迁移：$id"
            }
            require(archive.isDirectory || archive.mkdirs()) { "无法创建旧技能归档目录" }
            Files.move(source.toPath(), destination.toPath())
        }
        workspace.write(".dsh/skills/.general-presets-retired-v1", "done\n")
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

    /** Read Codex metadata from the admitted skill directory. This never invokes tools. */
    fun dependencies(id: String): List<CodexSkillDependency> {
        requireInstalled(id)
        val folder = File(skillsDir, id).canonicalFile
        val file = File(folder, "agents/openai.yaml")
        if (!file.exists()) return emptyList()
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile &&
            file.canonicalFile.toPath().startsWith(folder.toPath()) && file.length() <= 256 * 1024) {
            "技能依赖元数据位置或大小无效"
        }
        return parseCodexSkillDependencies(file.readText(Charsets.UTF_8))
    }

    fun readDocument(id: String): String {
        requireInstalled(id)
        return workspace.readRaw(".dsh/skills/$id/SKILL.md")
    }

    fun updateDocument(id: String, document: String) {
        requireInstalled(id)
        require(document.isNotBlank()) { "技能文件不能为空" }
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

// FEATURE_TREE: {"id":"skills.minimax-official-bundles","path":"第一层｜产品功能与界面 / 工具与扩展功能 / 技能 / 预置技能目录","owner":"TOOLS","entry":"LocalSkillStore","test":"app/src/test/java/com/labteto/dshmobile/local/tools/LocalMiniMaxSkillBundleTest.kt#installsOnlyMobileMediaSkillsAndUpdatesBuiltIns"}
@Singleton
internal class LocalSkillManager @Inject constructor(
    storage: LocalSessionStorageRuntime,
    @ApplicationContext context: Context,
    private val pinnedInvocations: LocalPinnedInvocationStore,
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
    fun initializeDefaults() {
        LocalSkillPackageUpdater(storageWorkspace).recoverInterruptedTransactions()
        store.seedOnce()
        // Persistent composer pins must not break the first Work turn after migration.
        // Keep pins for any same-ID skill that the user later installs explicitly.
        val active = store.installed().mapTo(hashSetOf()) { it.name }
        pinnedInvocations.current().filter {
            it.kind == LocalPinnedInvocation.Kind.SKILL &&
                it.id in LocalPresetSkillCatalog.retiredIds && it.id !in active
        }.forEach { pinnedInvocations.remove(it) }
    }
    fun installed(): List<LocalInstalledSkill> = store.installed()

    /** Existing native observer notices external skill installs, edits and removals. */
    fun changes(): kotlinx.coroutines.flow.Flow<Unit> =
        com.labteto.dshmobile.local.files.watchWorkspaceFileChanges(
            java.io.File(storageWorkspace.path),
        ) {
            val root = java.io.File(storageWorkspace.path, ".dsh/skills")
            val folders = root.listFiles().orEmpty().filter {
                it.isDirectory && !Files.isSymbolicLink(it.toPath())
            }
            listOf(".dsh/skills") + folders.map { ".dsh/skills/${it.name}" }
        }.mapToSkillRefresh()

    private fun kotlinx.coroutines.flow.Flow<com.labteto.dshmobile.local.files.LocalWorkspaceFileChange>
        .mapToSkillRefresh(): kotlinx.coroutines.flow.Flow<Unit> = map { Unit }

    fun githubSourceEvidence(id: String): String? = store.githubSourceEvidence(id)
    fun dependencies(id: String): List<CodexSkillDependency> = store.dependencies(id)
    fun presets(): List<LocalPresetSkill> = store.presets()
    fun install(id: String) = store.install(id)
    fun create(id: String, displayName: String, description: String, instructions: String) =
        store.create(id, displayName, description, instructions)
    fun importSkill(filename: String, bytes: ByteArray): String = LocalSkillPackageImporter(storageWorkspace).install(filename, bytes)
    fun importSkill(filename: String, input: java.io.InputStream, beforePublish: () -> Unit = {}): String =
        LocalSkillPackageImporter(storageWorkspace).install(filename, input, beforePublish)
    fun exportSkillPackage(id: String): ByteArray = LocalSkillPackageExporter(storageWorkspace).export(id)
    fun exportSkillPackageToFile(id: String, destination: File) =
        LocalSkillPackageExporter(storageWorkspace).exportToFile(id, destination)
    fun replaceSkillPackage(id: String, archive: ByteArray): String =
        LocalSkillPackageUpdater(storageWorkspace).replace(id, archive)
    fun replaceSkillPackage(id: String, archive: java.io.InputStream, beforePublish: () -> Unit = {}): String =
        LocalSkillPackageUpdater(storageWorkspace).replace(id, archive, beforePublish)
    fun canRollbackSkill(id: String): Boolean = LocalSkillPackageUpdater(storageWorkspace).canRollback(id)
    fun rollbackSkill(id: String): String = LocalSkillPackageUpdater(storageWorkspace).rollback(id)
    fun remove(id: String) {
        store.remove(id)
        LocalSkillPackageUpdater(storageWorkspace).forgetRollback(id)
    }
    fun readDocument(id: String) = store.readDocument(id)
    fun updateDocument(id: String, document: String) = store.updateDocument(id, document)
    fun setModelInvocable(id: String, enabled: Boolean) = store.setModelInvocable(id, enabled)
}
