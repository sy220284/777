package com.labteto.dshmobile.local.chat

/**
 * Chat-only context policy.
 *
 * The model should see one canonical copy of a fact per turn. Durable history, relationship
 * memory and story continuity stay available to the product, but request-time context is selected,
 * de-duplicated and bounded here before it reaches generation.
 */
internal object ChatMemorySelector {
    fun shouldRecall(query: String): Boolean {
        val text = query.trim().lowercase()
        if (text.isBlank()) return false
        if (RESET_HINTS.any { text.contains(it) }) return false
        if (RECALL_HINTS.any { text.contains(it) }) return true
        if (text in LOW_INFORMATION_REPLIES) return false
        if (text.length <= 12 && CONTINUATION_ONLY_HINTS.any { text == it || text.startsWith(it) }) {
            return false
        }
        return text.length >= 8 && RELATIONSHIP_MEMORY_HINTS.any { text.contains(it) }
    }

    fun semanticQuery(query: String, personaName: String): String =
        listOf(query.trim(), personaName.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")

    private val LOW_INFORMATION_REPLIES = setOf(
        "嗯", "嗯嗯", "好", "好的", "行", "可以", "继续", "接着", "然后呢", "哈哈", "哈哈哈",
        "哦", "噢", "啊", "行吧", "好吧", "知道了", "再来", "别停",
    )
    private val CONTINUATION_ONLY_HINTS = listOf(
        "继续", "接着", "然后", "再来", "就这样", "别停", "嗯", "好", "行",
    )
    private val RECALL_HINTS = listOf(
        "还记得", "你记得", "记不记得", "以前", "之前", "上次", "第一次", "当时",
        "我跟你说过", "我和你说过", "我们什么时候", "你知道我", "你还知道",
    )
    private val RELATIONSHIP_MEMORY_HINTS = listOf(
        "喜欢", "讨厌", "习惯", "在意", "介意", "女朋友", "男朋友", "对象", "老婆", "老公",
        "前任", "暧昧", "关系", "在一起", "分手", "复合",
    )
    private val RESET_HINTS = listOf(
        "换个话题", "先不聊这个", "不聊这个", "别提这个", "别再提", "说正事", "到此为止",
    )
}

internal object ChatContextAssembler {
    fun assemble(
        dynamicPrompt: String,
        relationshipMemory: String,
        userInput: String,
        recentAssistantReplies: List<String> = emptyList(),
    ): String {
        val seen = mutableListOf<String>()
        semanticCore(userInput).takeIf(String::isNotBlank)?.let(seen::add)

        fun dedupe(block: String): String {
            if (block.isBlank()) return ""
            val kept = mutableListOf<String>()
            block.lineSequence().forEach { raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) return@forEach
                if (line.startsWith("【") && line.endsWith("】")) {
                    kept += line
                    return@forEach
                }
                val core = semanticCore(line)
                if (core.isBlank() || seen.none { prior -> semanticallySimilar(core, prior) }) {
                    kept += line
                    if (core.isNotBlank()) seen += core
                }
            }
            return kept.joinToString("\n").trim()
        }

        val primary = dedupe(dynamicPrompt)
        val memory = dedupe(relationshipMemory)
        val generationRule = buildString {
            appendLine("【本轮生成】")
            appendLine("优先回应用户最新输入；背景、摘要和已发生事件只用于保持连续，除非用户追问，不主动复述。")
            appendLine("避免重复上一轮已经表达过的观点或句式；优先增加新的反应、信息、动作或关系变化。")
            append("用户只给短回应时自然承接即可，不为“推进”强行制造新事件。")
            if (recentAssistantReplies.any(String::isNotBlank)) {
                append(" 最近几轮角色已经说过的内容视为已表达。")
            }
        }
        return listOf(primary, memory, generationRule)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    internal fun semanticallySimilar(left: String, right: String): Boolean {
        val a = semanticCore(left)
        val b = semanticCore(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (a.length >= 8 && b.length >= 8 && (a.contains(b) || b.contains(a))) return true
        val aa = bigrams(a)
        val bb = bigrams(b)
        if (aa.isEmpty() || bb.isEmpty()) return false
        val shared = aa.count(bb::contains)
        val containment = shared.toDouble() / minOf(aa.size, bb.size).toDouble()
        val union = aa.union(bb).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else shared / union
        return containment >= 0.78 || jaccard >= 0.68
    }

    private fun semanticCore(text: String): String {
        val stripped = text.trim()
            .removePrefix("-")
            .trim()
            .replace(
                Regex("""^(?:关注|近期印象|目标|行动|内在拉扯|在意|未完话题|未解冲突|已确认事实|事实|共同经历|关系状态|关系对象|用户关系偏好)[：:=]\s*"""),
                "",
            )
        return stripped.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
            .take(600)
    }

    private fun bigrams(text: String): Set<String> =
        if (text.length < 2) setOf(text)
        else (0 until text.length - 1).mapTo(linkedSetOf()) { text.substring(it, it + 2) }
}

internal data class ChatRepetitionResult(
    val text: String,
    val repeatedSegments: List<String>,
)

internal object ChatRepetitionGuard {
    fun filter(candidate: String, recentAssistantReplies: List<String>): ChatRepetitionResult {
        if (candidate.isBlank() || recentAssistantReplies.isEmpty()) {
            return ChatRepetitionResult(candidate, emptyList())
        }
        val recentSegments = recentAssistantReplies
            .takeLast(4)
            .flatMap(::segments)
            .filter { normalize(it).length >= MIN_REPEAT_CHARS }
        if (recentSegments.isEmpty()) return ChatRepetitionResult(candidate, emptyList())

        val repeated = mutableListOf<String>()
        val kept = segments(candidate).filter { segment ->
            val normalized = normalize(segment)
            val duplicate = normalized.length >= MIN_REPEAT_CHARS &&
                recentSegments.any { old -> ChatContextAssembler.semanticallySimilar(segment, old) }
            if (duplicate) repeated += segment
            !duplicate
        }
        val filtered = kept.joinToString("").trim()
        return if (repeated.isNotEmpty() && filtered.length >= MIN_RESULT_CHARS) {
            ChatRepetitionResult(filtered, repeated)
        } else {
            ChatRepetitionResult(candidate, repeated)
        }
    }

    private fun segments(text: String): List<String> =
        Regex("""[^。！？!?\n]+[。！？!?]?|\n+""")
            .findAll(text)
            .map { it.value }
            .filter(String::isNotBlank)
            .toList()

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private const val MIN_REPEAT_CHARS = 10
    private const val MIN_RESULT_CHARS = 4
}
