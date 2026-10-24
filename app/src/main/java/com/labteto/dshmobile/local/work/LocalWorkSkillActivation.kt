package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.LocalSkillMetadata
import java.util.Locale
import com.labteto.dshmobile.local.tools.LocalPinnedInvocation

private const val MAX_EAGER_SKILL_GUIDANCE_CHARS = 18_000

/** Selected skill instructions are loaded once per model step and never persisted in history. */
internal fun resolveLocalWorkSkillGuidance(
    workspace: LocalWorkspace,
    input: String,
    selectedInvocations: List<LocalPinnedInvocation> = emptyList(),
): String {
    // Explicit selection is the strongest instruction source. Reserve its budget
    // before auto-pinned instructions so a long pinned set cannot hide the user's choice.
    val explicitName = Regex("^@skill:([a-z][a-z0-9-]{1,47})(?:\\r?\\n|$)").find(input)?.groupValues?.get(1)
    val explicitDocument = explicitName?.let { name ->
        try {
            workspace.readSkill(name)
        } catch (error: Exception) {
            throw IllegalArgumentException("所选技能 ${name} 已移除或无法读取，请返回技能页重新选择。", error)
        }
    }
    var remainingChars = MAX_EAGER_SKILL_GUIDANCE_CHARS
    val explicitExcerpt = explicitDocument?.take(remainingChars)?.also { remainingChars -= it.length }
    val pinnedConnectors = selectedInvocations.filter {
        it.kind == LocalPinnedInvocation.Kind.CONNECTOR
    }.joinToString("、") { it.id }
    val pinnedSkills = selectedInvocations.filter {
        it.kind == LocalPinnedInvocation.Kind.SKILL && it.id != explicitName
    }.map { selection ->
        val document = try {
            workspace.readSkill(selection.id)
        } catch (error: Exception) {
            throw IllegalArgumentException("已固定技能 ${selection.id} 无法加载，请检查技能文件后重试。", error)
        }
        selection.id to document
    }
    // Selected SKILL.md is loaded directly for this model step; no extra skill(name)
    // discovery/tool call is required. Keep model history free of repeated instructions.
    // Keep several pinned skills from overwhelming each model step. Each skill can still
    // be retrieved in full through skill(name), including user-only skills explicitly pinned.
    val pinnedSkillInstructions = pinnedSkills.joinToString("\n\n") { (name, document) ->
        val excerpt = document.take(remainingChars.coerceAtLeast(0))
        remainingChars -= excerpt.length
        if (excerpt.length == document.length) {
            "[已固定技能：$name]\n$excerpt\n[技能结束]"
        } else {
            "[已固定技能：$name]\n$excerpt\n[内容较长；调用 skill(name=$name) 可读取完整文档]"
        }
    }
    val pinnedNote = if (pinnedConnectors.isEmpty() && pinnedSkills.isEmpty()) "" else """
        [用户明确固定的调用来源]
        以下连接器和技能由用户在聊天框中固定，后续轮次默认优先使用，直到用户自行取消。
        实际工具执行仍需满足连接状态与原有审批，明确选择不等于授予权限。
        ${if (pinnedConnectors.isNotEmpty()) "已选连接器：$pinnedConnectors" else ""}
        $pinnedSkillInstructions
        [固定来源结束]
    """.trimIndent()
    if (explicitName != null) {
        val document = checkNotNull(explicitDocument)
        val excerpt = checkNotNull(explicitExcerpt)
        val full = excerpt.length == document.length
        return """
            [本轮技能已加载：${explicitName}]
            用户明确选择了此技能。以下技能文件仅提供执行方法和默认约定；与本轮用户明确要求冲突时以用户要求为准，不得仅因技能规则拒绝执行。
            ${excerpt}
            ${if (full) "[技能规则结束]" else "[内容较长，已限制本轮自动注入；请调用 skill(name=$explicitName) 读取完整文档。不能假定未加载部分已阅读。]"}
            $pinnedNote
        """.trimIndent()
    }
    val requested = relevantSkillIdsForTask(input)
    val installed = workspace.skills().mapNotNull { name ->
        val metadata = try {
            workspace.skillMetadata(name)
        } catch (error: Exception) {
            if (name in requested) throw IllegalArgumentException(
                "任务所需技能 $name 无法读取，请检查技能文件后重试。", error)
            return@mapNotNull null
        }
        name to metadata
    }
    val eligibleCount = installed.count { it.second.modelInvocable }
    val lines = discoverLocalWorkSkills(input, requested, installed).map { (name, metadata) ->
        val details = metadata.whenToUse?.takeIf(String::isNotBlank)?.let { "；适用：$it" }.orEmpty()
        "$name：" + metadata.description + details
    }
    if (lines.isEmpty()) return pinnedNote
    return """
        [本地技能目录：共 $eligibleCount 项，本轮展示 ${lines.size} 项]
        下面按本轮相关性排序，仅提供目录；如需使用，通过 skill(name) 读取完整说明。
        ${lines.joinToString("\n")}
        ${if (lines.size < eligibleCount) "[目录未展示完全；调用 skill(offset=0) 浏览完整分页目录，或用 skill(query=关键词) 检索；目录顺序与本轮排序可能不同。]" else "可用 skill(query=关键词) 检索，或按 name 读取。"}
        [目录结束]
        $pinnedNote
    """.trimIndent()
}

/** Select dynamic SKILL.md metadata once; never eagerly load unselected instructions. */
// FEATURE_TREE: {"id":"tools.skill.live-catalog","path":"第一层｜产品功能与界面 / 工具与扩展功能 / 技能 / 全目录发现与运行中变更感知（未合并）","entry":"discoverLocalWorkSkills","owner":"WORK","test":"app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkSkillActivationTest.kt#catalogReportsTailAndRefreshesPinnedBodyAndDisabledMetadata"}
internal fun discoverLocalWorkSkills(
    input: String,
    requested: Set<String>,
    installed: List<Pair<String, LocalSkillMetadata>>,
    limit: Int = 8,
): List<Pair<String, LocalSkillMetadata>> {
    val task = input.lowercase(Locale.ROOT)
    if (task.isBlank() || limit <= 0) return emptyList()
    val delimiters = Regex("[，、。；;,|/\\s]+")
    val ignored = setOf(
        "with", "from", "into", "using", "about", "that", "this", "your", "the", "and", "for",
        "to", "in", "on", "of", "use", "skill", "me", "is", "as", "at", "by", "an", "or", "it", "be",
    )
    fun matches(term: String, allowShortTag: Boolean = false): Boolean {
        val value = term.trim().lowercase(Locale.ROOT)
        if (value.length < 2) return false
        if (value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
            // Keep PR/UI and user-defined tags case-insensitive, while grammatical
            // words in trigger sentences must not become topic matches.
            if ((!allowShortTag && value.length < 3) || value in ignored) return false
            return Regex("(?<![a-z0-9])" + Regex.escape(value) + "(?![a-z0-9])").containsMatchIn(task)
        }
        return task.contains(value)
    }
    fun score(name: String, metadata: LocalSkillMetadata): Int {
        if (!metadata.modelInvocable) return 0
        if (name in requested) return 1_000
        val hints = metadata.whenToUse.orEmpty().split(delimiters).filter { it.length >= 2 }
        val exactWhen = metadata.whenToUse?.trim().orEmpty()
        val keywords = (metadata.displayName.orEmpty() + " " + metadata.description)
            .split(delimiters).filter { it.length >= 3 }.distinct()
        return (if (matches(exactWhen, allowShortTag = true)) 70 else 0) +
            hints.count { matches(it, allowShortTag = true) }.coerceAtMost(3) * 30 +
            keywords.count(::matches).coerceAtMost(3) * 8
    }
    return installed.asSequence().distinctBy { it.first }
        .filter { it.second.modelInvocable }
        .map { (name, meta) -> Triple(name, meta, score(name, meta)) }
        .sortedWith(compareByDescending<Triple<String, LocalSkillMetadata, Int>> { it.third }
            .thenBy { it.first })
        .take(limit)
        .map { it.first to it.second }.toList()
}

/** Intent hints rank the catalog; absence of a fixed keyword must not hide installed skills. */
internal fun relevantSkillIdsForTask(input: String): Set<String> {
    val rules = listOf(
        "writing-polish" to "润色|改写|精修|文案优化|校对|polish|rewrite|proofread",
        "longform-outline" to "大纲|小说|章节|故事规划|剧本|长篇|伏笔|outline|novel",
        "document-summary" to "总结文档|总结文件|摘要|会议纪要|提炼|总结材料|summarize|summary",
        "research-check" to "调研|核实资料|资料研究|事实查证|来源|文献|research|fact.check",
        "code-review" to "代码审查|审计代码|审计一遍|检查代码|检查PR|审查PR|评审|\\bPR\\b|review|pull[\\s-]*request",
        "bug-investigation" to "故障|报错|异常|崩溃|排查|bug|crash|debug|error",
        "data-insights" to "数据分析|数据统计|报表|趋势分析|指标|统计口径|analytics|data.insight",
        "translation-localization" to "翻译|本地化|译文|术语统一|translate|translation|localization",
        "imagegen" to "生成图片|图片生成|修图|编辑图片|参考图|绘制图片|图像创作|image.gen|image.edit",
        "minimax-media" to "图片生成|生成图片|生成视频|视频生成|配音|生成语音|媒体生成|image.gen|video.gen",
        "minimax-music-gen" to "生成音乐|生成歌曲|伴奏|作曲|音乐创作|music.gen",
        "minimax-playlist" to "歌单|播放列表|playlist",
    )
    val matched = rules.asSequence().filter { (_, terms) ->
        Regex(terms, RegexOption.IGNORE_CASE).containsMatchIn(input)
    }.map { it.first }.toSet()
    // Generic image requests belong to the multi-provider imagegen workflow.
    // Use MiniMax's specialised media guidance only when the user names MiniMax or
    // actually requests a video, speech or other specialised output.
    val miniMaxExplicit = Regex("minimax|海螺|hailuo|视频|语音|配音|音乐", RegexOption.IGNORE_CASE)
        .containsMatchIn(input)
    return if ("imagegen" in matched && !miniMaxExplicit) matched - "minimax-media" else matched
}
