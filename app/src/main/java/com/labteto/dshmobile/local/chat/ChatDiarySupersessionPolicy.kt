package com.labteto.dshmobile.local.chat

internal object ChatDiarySupersessionPolicy {
    fun supersedes(
        existing: ChatDiaryEntry,
        candidate: ChatDiaryEntry,
    ): Boolean {
        if (!existing.active || existing.id == candidate.id) return false
        if (existing.subjectKey != candidate.subjectKey) return false
        if (existing.supersededBy != null) return false
        // Disclosure metadata cannot retract what this character remembers across modes.
        // A newer event must explicitly update, cancel or replace the earlier event.
        if (!STATE_CHANGE_SIGNAL.containsMatchIn(candidate.event)) return false
        if (samePlanningTopic(existing.event, candidate.event)) return true
        return topicSimilarity(existing.event, candidate.event) >= SUPERSEDE_TOPIC_SIMILARITY
    }

    fun repairLinks(entries: List<ChatDiaryEntry>): List<ChatDiaryEntry> {
        val activeById = entries.asSequence()
            .filter(ChatDiaryEntry::active)
            .associateBy(ChatDiaryEntry::id)
        return entries.map { entry ->
            val target = entry.supersededBy?.let(activeById::get)
            if (
                target == null ||
                !supersedes(entry.copy(supersededBy = null), target)
            ) {
                entry.copy(supersededBy = null)
            } else {
                entry
            }
        }
    }

    private fun samePlanningTopic(left: String, right: String): Boolean =
        PLANNING_TOPIC.containsMatchIn(left) &&
            PLANNING_TOPIC.containsMatchIn(right) &&
            PLANNING_ACTIONS.any { action ->
                action.containsMatchIn(left) && action.containsMatchIn(right)
            }

    private fun topicSimilarity(left: String, right: String): Double {
        val a = bigrams(canonicalTopic(normalize(left)))
        val b = bigrams(canonicalTopic(normalize(right)))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count(b::contains).toDouble()
        val containment = shared / minOf(a.size, b.size).toDouble()
        val union = a.union(b).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else shared / union
        return maxOf(containment, jaccard)
    }

    private fun canonicalTopic(text: String): String =
        text.replace(STATE_CHANGE_NOISE, "")
            .replace(REPEATED_CONFIRMATION_NOISE, "")
            .replace(AGREEMENT_VARIANTS, "约定")

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
            .take(1_200)

    private fun bigrams(text: String): Set<String> =
        if (text.length < 2) emptySet()
        else (0 until text.length - 1).mapTo(linkedSetOf()) { text.substring(it, it + 2) }

    private const val SUPERSEDE_TOPIC_SIMILARITY = 0.46
    private val REPEATED_CONFIRMATION_NOISE = Regex("""(?:再次|再一次|又一次|重新)""")
    private val AGREEMENT_VARIANTS = Regex("""(?:答应|确认|确定|说定|约定)""")
    private val PLANNING_TOPIC = Regex("""(?:约定|安排|见面|碰面|会合|出发|行程|计划)""")
    private val PLANNING_ACTIONS = listOf(
        Regex("""(?:见面|碰面|会合|碰头)"""),
        Regex("""(?:出发|旅行|旅游|露营|远足)"""),
        Regex("""(?:聚会|聚餐|吃饭|晚餐|午餐)"""),
        Regex("""(?:接人|接朋友|接送|接站)"""),
    )
    private val STATE_CHANGE_SIGNAL = Regex(
        """(?:取消|撤销|改为|改成|改到|改在|推迟|提前|不再|不用|不要了|结束|已经解决|没事了|分开|分手|复合|重新确定|替换|更新为)""",
    )
    private val STATE_CHANGE_NOISE = Regex(
        """(?:取消|撤销|改为|改成|改到|改在|推迟|提前|不再|不用|不要了|结束|已经解决|没事了|分开|分手|复合|重新确定|替换|更新为|原来|之前|现在|当前|最新)""",
    )
}
