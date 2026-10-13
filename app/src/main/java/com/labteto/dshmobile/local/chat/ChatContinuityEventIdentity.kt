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
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        // Explicit concrete objects/places can acquire descriptive modifiers.
        if (shorter.length >= 2 && longer.contains(shorter)) return true
        return false
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

    private fun subject(text: String): String =
        text.lowercase().replace(punctuation, "").replace(boilerplate, "").take(180)
}
