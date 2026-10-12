package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

/**
 * One conversation-scoped attention projection for reply, recall and proactive prompts.
 * Facts remain in durable transcript/continuity; attention never deletes or rewrites them.
 */
@Serializable
data class ChatAttentionState(
    val lastUserSubject: String = "",
    /** The user's last explicit request to stop discussing a topic. Never means stop an action. */
    val pausedSubject: String = "",
    val updatedSequence: Long = -1L,
)

internal object ChatTurnContextPolicy {
    private val genericTopicChange = Regex(
        """^(?:(?:我们)?(?:先|暂时|现在)?(?:换个话题|换个主题|聊点别的|说点别的|不聊这个|先不聊这个|别提这个|别再提这个|到此为止|说正事))(?:吧|了|啦|。|！|!)?$""",
    )
    private val namedTopicChange = Regex(
        """^(?:(?:我|我们)?(?:先|暂时|现在)?(?:别|不要|不想|先不|不再)(?:再)?(?:聊|谈|提|讨论|说说))(?<topic>[^，。！？!?]{1,28}?)(?:了|啦|吧)?$""",
    )
    private val stopTalking = Regex("""^(?:别再提|不要再提|不想再聊|先不聊|换个话题|别提这个|到此为止)""")
    private val weakResponses = setOf(
        "嗯", "嗯嗯", "好", "好的", "行", "可以", "继续", "接着", "然后呢",
        "哦", "噢", "啊", "好吧", "行吧", "哈哈", "哈哈哈",
    )
    private val references = listOf(
        "那件事", "那后来", "那之后", "后来怎么样", "最后怎么样",
        "刚才", "之前", "上次", "记得", "答应过", "说好的", "照旧", "原来",
    )
    private val separator = Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+""")

    /** Topic changes require an actual conversational directive, not arbitrary negated verbs. */
    fun redirectedTopic(message: String): String? {
        val text = message.trim().trimEnd('。', '！', '!', ' ')
        if (genericTopicChange.matches(text) || stopTalking.matches(text) &&
            (text == "别再提" || text == "不要再提")) return "*"
        val named = namedTopicChange.matchEntire(text) ?: return null
        return named.groups["topic"]?.value?.trim()?.trimEnd('了', '啦', '吧')
            ?.takeIf(String::isNotBlank) ?: "*"
    }

    fun isTopicRedirect(message: String): Boolean = redirectedTopic(message) != null

    fun isContinuation(message: String): Boolean {
        val text = message.trim()
        return text in weakResponses || references.any(text::contains)
    }

    /** Applies only committed user turns; editing/branching restores its saved snapshot. */
    fun advance(attention: ChatAttentionState, userMessage: String, sequence: Long): ChatAttentionState {
        if (sequence <= attention.updatedSequence) return attention
        val message = userMessage.trim()
        if (message.isEmpty()) return attention
        val redirect = redirectedTopic(message)
        if (redirect != null) return attention.copy(
            lastUserSubject = "", pausedSubject = redirect.take(80), updatedSequence = sequence,
        )
        if (message in weakResponses) return attention.copy(updatedSequence = sequence)
        val resumed = attention.pausedSubject.isNotBlank() &&
            attention.pausedSubject != "*" &&
            normalize(message).contains(normalize(attention.pausedSubject)) &&
            (references.any(message::contains) || message.contains("继续") || message.contains("再聊"))
        return attention.copy(
            lastUserSubject = message.take(260),
            pausedSubject = if (resumed) "" else attention.pausedSubject,
            updatedSequence = sequence,
        )
    }

    fun effectiveQuery(message: String, attention: ChatAttentionState): String {
        if (isTopicRedirect(message)) return ""
        if (message.trim() in weakResponses) return attention.lastUserSubject
        if (isContinuation(message) && attention.lastUserSubject.isNotBlank()) {
            return "${message.trim()} ${attention.lastUserSubject}".take(650)
        }
        return message.trim()
    }

    /**
     * Relevance is only a *request projection*, never a fact deletion or a final truth judgment.
     * An explicit topic redirect removes stale soft facts for this turn, not the hard scene.
     */
    fun relevant(message: String, fact: String, attention: ChatAttentionState = ChatAttentionState()): Boolean {
        if (isTopicRedirect(message) || fact.isBlank()) return false
        val query = effectiveQuery(message, attention)
        val a = normalize(query)
        val b = normalize(fact)
        if (a.length < 3 || b.length < 3) return false
        val paused = normalize(attention.pausedSubject)
        if (paused.isNotEmpty() && paused != "*" && b.contains(paused) && !a.contains(paused)) return false
        val pairsA = a.windowed(2).toSet()
        val pairsB = b.windowed(2).toSet()
        if (pairsA.isEmpty() || pairsB.isEmpty()) return false
        val common = pairsA.count(pairsB::contains)
        return a.contains(b) || b.contains(a) && a.length >= 4 ||
            (common >= 2 && common.toDouble() / minOf(pairsA.size, pairsB.size) >= 0.28)
    }

    /** A story checkpoint is only a search input after relevance to the actual user turn. */
    fun storyLines(story: String?, userMessage: String, attention: ChatAttentionState): String {
        if (story.isNullOrBlank() || isTopicRedirect(userMessage)) return ""
        return story.lineSequence().filter { line ->
            val text = line.trim()
            text.isNotBlank() && relevant(userMessage, text, attention)
        }.take(6).joinToString("\n").take(900)
    }

    private fun normalize(text: String): String = text.lowercase().replace(separator, "")
}
