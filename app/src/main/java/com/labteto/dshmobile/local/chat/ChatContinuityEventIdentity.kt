package com.labteto.dshmobile.local.chat

/**
 * Event identity is NOT lexical similarity. These functions only identify a narrow, reversible
 * subject alias for old text-only threads. Durable provenance IDs decide actual resolution.
 *
 * Keep this separate from recall similarity: retrieving two related events must never merge them.
 */
internal object ChatContinuityEventIdentity {
    private val boilerplate = Regex(
        """(?:对方|用户|角色|是否|有没有|能否|可否|愿不愿意|要不要|同意|接受|认可|确认|尚未明确|还没明确|尚未|未明确|未完成|待确认|还没有|还没|还要|需要|记得|将要|已经|安排|约定|计划|那件事|这件事|的|了|与|和)""",
    )
    private val punctuation = Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+""")
    private val consent = Regex("""(?:我答应|答应你|同意|说定了|说好了)""")
    private val acknowledgment = Regex("""(?:答应|同意|说定|约定)""")

    /**
     * Alias means one concrete matter was rephrased, NOT that two subjects sound similar.
     * "同意保密" and "同意公开照片" share grammar but must remain separate events.
     */
    fun sameSubject(left: String, right: String): Boolean {
        val a = subject(left)
        val b = subject(right)
        if (a.length < 2 || b.length < 2) return false
        // Substring equality used to make "保密" the same slot as "保密公开照片".
        // Preserve old lexical aliases only when both normalized subjects are exact.
        return a == b
    }

    /** A user correction/removal must name the concrete matter, not just generic words. */
    fun matchesEvidenceSubject(fact: String, evidence: String): Boolean {
        val anchor = subject(fact)
        val text = subject(evidence)
        return anchor.length >= 2 && text.contains(anchor)
    }

    /**
     * A generic acceptance is unambiguous only if exactly one yes/no question is outstanding.
     * Multiple questions require the real exchange to identify the accepted subject as well.
     */
    fun confirmsQuestion(
        fact: String,
        turns: List<ChatPendingTurn>,
        outstandingQuestionCount: Int,
    ): Boolean {
        if (!Regex("""(?:是否|有没有|待确认|尚未明确|还没确认)""").containsMatchIn(fact)) return false
        return turns.any { turn ->
            consent.containsMatchIn(turn.userMessage) &&
                acknowledgment.containsMatchIn(turn.assistantMessage) &&
                (outstandingQuestionCount == 1 ||
                    matchesEvidenceSubject(fact, turn.userMessage + " " + turn.assistantMessage))
        }
    }

    /**
     * An event transition is accepted only after the caller checks exact event ID and
     * candidate omission. This method checks the real user utterance, not model-only prose.
     * Retrieval similarity may suggest facts but never closes or replaces an active event.
     */
    fun transitionGrounded(
        kind: ChatContinuityFactKind,
        fact: String,
        turns: List<ChatPendingTurn>,
        activeFactCount: Int,
        activeQuestionCount: Int,
    ): Boolean = when (kind) {
        ChatContinuityFactKind.EVENT -> false
        ChatContinuityFactKind.DECISION -> turns.any { turn ->
            cancellation.containsMatchIn(turn.userMessage) &&
                transitionSubjectMatches(fact, turn.userMessage, activeFactCount)
        }
        ChatContinuityFactKind.OPEN_THREAD -> turns.any { turn ->
            (completion.containsMatchIn(turn.userMessage) &&
                transitionSubjectMatches(fact, turn.userMessage, activeFactCount)) ||
                confirmsQuestion(fact, listOf(turn), activeQuestionCount)
        }
    }

    private fun transitionSubjectMatches(fact: String, user: String, activeCount: Int): Boolean {
        if (matchesEvidenceSubject(fact, user)) return true
        // The legacy text fact may include time/action framing absent from a direct
        // cancellation: "明天九点去城南" -> "城南不去了".
        val core = subject(fact).replace(topicNoise, "")
        return (core.length in 2..16 && user.contains(core)) ||
            (activeCount == 1 && genericClosure.matches(user.trim()))
    }

    private val cancellation = Regex("""(?:取消|作废|不去了|不再去|不去|撤销|延期|推迟|改成|换成)""")
    private val completion = Regex("""(?:解决了|说清了|说完了|完成了|处理好了|结束了|取消了|没事了|算了)""")
    private val genericClosure = Regex("""^(?:没事了|解决了|完成了|取消了|算了)[。！! ]*$""")
    private val topicNoise = Regex(
        """(?:今天|明天|后天|今晚|上午|中午|下午|晚上|[零一二两三四五六七八九十0-9]{1,3}(?:点|时)(?:半)?|出发|过去|前往|约定|计划|安排|决定|要|还|记得|需要|确认|去|到|与|和|的|了)""",
    )

    private fun subject(text: String): String =
        text.lowercase().replace(punctuation, "").replace(boilerplate, "").take(180)
}
