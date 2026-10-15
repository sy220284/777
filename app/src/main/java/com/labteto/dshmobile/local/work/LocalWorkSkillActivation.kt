package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.tools.LocalPinnedInvocation

/** Selected skill instructions are loaded once per model step and never persisted in history. */
internal fun resolveLocalWorkSkillGuidance(
    workspace: LocalWorkspace,
    input: String,
    selectedInvocations: List<LocalPinnedInvocation> = emptyList(),
): String {
    val pinnedNote = selectedInvocations.asSequence().mapNotNull { selection ->
        when (selection.kind) {
            LocalPinnedInvocation.Kind.CONNECTOR -> "连接器：${selection.id}"
            LocalPinnedInvocation.Kind.SKILL -> selection.id.takeIf { it in workspace.skills() }
                ?.let { "技能：$it（先调用 skill(name) 加载完整规则）" }
        }
    }.take(12).toList().takeIf { it.isNotEmpty() }?.joinToString("\n")?.let { choices ->
        """
            [用户明确固定的调用来源]
            下列连接器或技能由用户在聊天框中主动选择，后续轮次默认优先使用，直至用户移除；不必重新让模型猜测和搜索。只有当前已连接、已注册且获授权的工具才能实际执行，不要假装调用成功。
            $choices
            [固定来源结束]
        """.trimIndent()
    }.orEmpty()
    val marker = Regex("^@skill:([a-z][a-z0-9-]{1,47})(?:\\r?\\n|$)")
    val match = marker.find(input)
    if (match != null) {
        val name = match.groupValues[1]
        val document = try {
            workspace.readSkill(name)
        } catch (error: Exception) {
            throw IllegalArgumentException("所选技能 ${name} 已移除或无法读取，请返回技能页重新选择。", error)
        }
        return """
            [本轮技能已加载：${name}]
            用户明确选择了此技能。以下技能文件仅提供执行方法和默认约定；与本轮用户明确要求冲突时以用户要求为准，不得仅因技能规则拒绝执行。
            ${document}
            [技能规则结束]
            $pinnedNote
        """.trimIndent()
    }

    val requested = relevantSkillIdsForTask(input)
    val task = input.lowercase()
    val lines = workspace.skills().asSequence().mapNotNull { name ->
        val meta = runCatching { workspace.skillMetadata(name) }.getOrNull()
            ?.takeIf { it.modelInvocable } ?: return@mapNotNull null
        val trigger = meta.whenToUse.orEmpty().trim()
        val keywords = (meta.displayName.orEmpty() + " " + meta.description)
            .split(Regex("[，、。；;\\s]+"))
            .map(String::trim).filter { it.length >= 3 }
        val customMatch = (trigger.length >= 2 && task.contains(trigger.lowercase())) ||
            keywords.any { task.contains(it.lowercase()) }
        if (name !in requested && !customMatch) return@mapNotNull null
        val details = meta.whenToUse?.takeIf(String::isNotBlank)?.let { "；适用：$it" }.orEmpty()
        name to "$name：${meta.description}$details"
    }.sortedByDescending { (name, _) -> name in requested }
        .take(8).map { it.second }.toList()
    if (lines.isEmpty()) return pinnedNote
    return """
        [与本轮任务相关的本地技能]
        下面的技能可提供专项执行方法；如需使用，通过 skill(name) 读取完整说明。
        ${lines.joinToString("\n")}
        [目录结束]
        $pinnedNote
    """.trimIndent()
}

/** Narrow intent routing prevents unrelated skills from entering every work request. */
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
        "minimax-media" to "图片生成|生成图片|生成视频|视频生成|配音|生成语音|媒体生成|image.gen|video.gen",
        "minimax-music-gen" to "生成音乐|生成歌曲|伴奏|作曲|音乐创作|music.gen",
        "minimax-playlist" to "歌单|播放列表|playlist",
    )
    return rules.asSequence().filter { (_, terms) ->
        Regex(terms, RegexOption.IGNORE_CASE).containsMatchIn(input)
    }.map { it.first }.toSet()
}
