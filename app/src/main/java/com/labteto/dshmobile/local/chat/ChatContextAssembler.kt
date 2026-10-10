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
        if (SHORT_RELATIONSHIP_RECALL_HINTS.any { text.contains(it) }) return true
        if (IMPLICIT_CONTINUITY_HINTS.any { text.contains(it) }) return true
        if (text.length <= 12 && CONTINUATION_ONLY_HINTS.any { text == it || text.startsWith(it) }) {
            return false
        }
        return text.length >= 8 && RELATIONSHIP_MEMORY_HINTS.any { text.contains(it) }
    }

    fun semanticQuery(
        query: String,
        personaName: String,
        context: ChatContextState = ChatContextState(),
    ): String {
        val text = query.trim().lowercase()
        val needsReferent = shouldRecall(query) && IMPLICIT_CONTINUITY_HINTS.any(text::contains)
        // Use public conversation evidence, never the character's private impressions or thoughts.
        val referent = if (needsReferent) {
            context.pendingTurns.sortedBy(ChatPendingTurn::sequence)
                .lastOrNull { it.userMessage.isNotBlank() && it.userMessage.trim() != query.trim() }
                ?.userMessage
                ?: context.continuity.unfinished.lastOrNull()
                ?: context.continuity.decisions.lastOrNull()
                ?: context.continuity.recentEvents.lastOrNull()
        } else null
        return listOf(query.trim(), personaName.trim(), referent.orEmpty().trim())
            .filter(String::isNotBlank).distinct().joinToString(" ")
    }

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
    private val SHORT_RELATIONSHIP_RECALL_HINTS = listOf(
        "喜欢我", "爱我", "讨厌我", "在意我", "介意我", "什么关系", "算什么关系",
        "女朋友", "男朋友", "对象", "老婆", "老公", "前任", "在一起", "分手", "复合",
    )
    private val RELATIONSHIP_MEMORY_HINTS = listOf(
        "喜欢", "讨厌", "习惯", "在意", "介意", "女朋友", "男朋友", "对象", "老婆", "老公",
        "前任", "暧昧", "关系", "在一起", "分手", "复合",
    )
    private val IMPLICIT_CONTINUITY_HINTS = listOf(
        "那件事", "那后来", "后来怎么样", "最后怎么样", "最后怎么", "答应过", "说好了",
        "原来的安排", "照原来", "照旧", "按原来", "还是原来", "别像上次",
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
        val userLine = userInput.trim().takeIf(String::isNotBlank)

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
                val duplicatesUserText = userLine?.let { current ->
                    semanticallySimilar(line, current)
                } == true
                val conflictsEarlierFact = seen.any { prior -> factConflicts(line, prior) }
                if (core.isBlank() || (!duplicatesUserText && !conflictsEarlierFact)) {
                    kept += line
                    if (core.isNotBlank()) seen += line
                }
            }
            return kept.joinToString("\n").trim()
        }

        val primary = dedupe(dynamicPrompt)
        val memory = dedupe(relationshipMemory)
        val recentBeatTags = ChatRoleplayNoveltyScanner.tags(recentAssistantReplies.takeLast(4))
        val generationRule = buildString {
            appendLine("【本轮生成】")
            appendLine("优先当前输入；历史仅用于连续，不主动复述。")
            append("避免重复已表达内容；短回应自然承接，需要推进时加入新的反应、信息或动作。")
            if (recentBeatTags.isNotEmpty()) {
                appendLine()
                append("近期已用节拍：${recentBeatTags.joinToString("、")}。优先换一种表达或互动方式。")
            }
        }
        return listOf(primary, memory, generationRule)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    /**
     * Shared request-level fact precedence used by both dynamic prompt assembly and historical
     * checkpoint pruning. The newer/current fact is passed as [right].
     */
    internal fun factConflicts(left: String, right: String): Boolean {
        if (semanticallySimilar(left, right)) return true
        if (sameStructuredFactSlot(left, right)) return true
        val oldSchedule = normalizeScheduleFact(left) ?: return false
        val currentSchedule = normalizeScheduleFact(right) ?: return false
        return oldSchedule == currentSchedule
    }

    private fun sameStructuredFactSlot(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (hasField(a, "时间") && hasField(b, "时间")) return true
        if (hasField(a, "地点") && hasField(b, "地点")) return true
        if (hasRelationshipStateField(a) && hasRelationshipStateField(b)) return true
        return false
    }

    private fun hasField(text: String, label: String): Boolean =
        Regex("""(?:^|[｜|])\s*$label\s*[=:：]""").containsMatchIn(text) ||
            text.contains("当前硬场景") && text.contains("$label=")

    private fun hasRelationshipStateField(text: String): Boolean =
        text.contains("关系状态：") ||
            text.contains("保存时的关系：") ||
            Regex("""(?:^|[｜|])\s*关系\s*[=:：]""").containsMatchIn(text)

    private fun normalizeScheduleFact(text: String): String? {
        val normalized = text.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
            .replace(
                Regex("""^(?:已定|当前有效决定|决定|待续事项|待续|近期关键事件|近期事件|近期)"""),
                "",
            )
            .replace("明天上午", "明天")
            .replace("明日上午", "明天")
            .replace("明早", "明天")
            .replace("明天早上", "明天")
            .replace("明天晚上", "明天")
            .replace("明晚", "明天")
            .replace("今天上午", "今天")
            .replace("今天早上", "今天")
            .replace("今早", "今天")
            .replace("今天晚上", "今天")
            .replace("今晚", "今天")
        if (!SCHEDULE_FACT_HINT.containsMatchIn(normalized)) return null
        val clockNormalized = normalized
            .replace(ARABIC_CLOCK, "<时>")
            .replace(CHINESE_CLOCK, "<时>")
        return clockNormalized.takeIf { it != normalized }
    }

    internal fun semanticallySimilar(left: String, right: String): Boolean {
        val a = semanticCore(left)
        val b = semanticCore(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val lengthRatio = minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length).toDouble()
        if (
            a.length >= 8 &&
            b.length >= 8 &&
            lengthRatio >= 0.72 &&
            (a.contains(b) || b.contains(a))
        ) return true
        val aa = bigrams(a)
        val bb = bigrams(b)
        if (aa.isEmpty() || bb.isEmpty()) return false
        val shared = aa.count(bb::contains)
        val containment = shared.toDouble() / minOf(aa.size, bb.size).toDouble()
        val union = aa.union(bb).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else shared / union
        return (containment >= 0.78 && lengthRatio >= 0.72) || jaccard >= 0.68
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

    private val SCHEDULE_FACT_HINT = Regex(
        """(?:今天|今晚|明天|明早|后天|早上|上午|中午|下午|傍晚|晚上|夜里|出发|见面|碰面|集合|去|回|到)""",
    )
    private val ARABIC_CLOCK = Regex("""(?:\d{1,2}[:：]\d{1,2}|\d{1,2}点(?:半|一刻|三刻)?)""")
    private val CHINESE_CLOCK = Regex("""[零〇一二两三四五六七八九十]{1,4}点(?:半|一刻|三刻)?""")

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
                recentSegments.any { old ->
                    samePolarity(segment, old) &&
                        ChatContextAssembler.semanticallySimilar(segment, old)
                }
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

    private fun samePolarity(left: String, right: String): Boolean =
        hasNegation(left) == hasNegation(right)

    private fun hasNegation(text: String): Boolean =
        NEGATION_MARKER.containsMatchIn(normalize(text))

    private val NEGATION_MARKER = Regex("""(?:不再|不用|不要|别再|别|没有|没|未|不|取消|撤销|停止|拒绝|否认)""")

    private const val MIN_REPEAT_CHARS = 10
    private const val MIN_RESULT_CHARS = 4
}
