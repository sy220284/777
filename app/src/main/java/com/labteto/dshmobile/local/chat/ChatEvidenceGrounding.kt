package com.labteto.dshmobile.local.chat

/**
 * Only promote model-derived relationship evidence when the proposed fact can be traced back to
 * this turn's user/assistant text. Keeping the matcher outside the planner prevents evidence
 * validation from becoming another planner responsibility.
 */
internal fun evidenceGrounded(
    evidence: RelationshipEvidence,
    userMessage: String,
    assistantMessage: String,
): Boolean {
    val source = evidence.source.trim().lowercase()
    val evidenceText = normalizeChatEvidence(evidence.text)
    if (evidenceText.length < 2) return false

    val explicit = source == "user" || source == "explicit"
    val spoken = when (source) {
        "user", "explicit" -> listOf(userMessage)
        "observed", "dialogue" -> listOf(userMessage, assistantMessage)
        else -> return false
    }
    val evidenceBigrams = chatEvidenceBigrams(evidenceText)
    if (evidenceBigrams.isEmpty()) return false

    // Keep contradictory clauses separate. "她说周末有空，我还没约" supports the
    // first claim even though the second clause contains "没". Conversely,
    // "我不喜欢阿青" cannot support "喜欢阿青" via a substring match.
    return spoken.asSequence()
        .flatMap { it.split(CHAT_EVIDENCE_CLAUSE_BOUNDARIES).asSequence() }
        .map(::normalizeChatEvidence)
        .filter { it.length >= 2 }
        .any { clause ->
            if (explicit && clause.length <= 100 && evidenceText.length >= 4 &&
                CHAT_EVIDENCE_NEGATION.containsMatchIn(clause) !=
                    CHAT_EVIDENCE_NEGATION.containsMatchIn(evidenceText)
            ) return@any false
            if (clause.contains(evidenceText)) return@any true
            val clauseBigrams = chatEvidenceBigrams(clause)
            val shared = evidenceBigrams.count(clauseBigrams::contains)
            // Two generic overlaps cannot establish a new relationship fact.
            shared >= 2 && shared.toDouble() / evidenceBigrams.size >= 0.50
        }
}

private fun normalizeChatEvidence(value: String): String =
    value.trim().lowercase().replace(Regex("""[\s，。！？、,.!?；;：:"'“”‘’（）()\[\]【】]+"""), "")

private fun chatEvidenceBigrams(text: String): Set<String> =
    if (text.length < 2) emptySet()
    else (0 until text.length - 1).mapTo(linkedSetOf()) { index ->
        text.substring(index, index + 2)
    }

private val CHAT_EVIDENCE_CLAUSE_BOUNDARIES = Regex("""[，。！？、,.!?；;\n]+""")
private val CHAT_EVIDENCE_NEGATION = Regex("""(?:没有|没|不|未|拒绝|取消|撤销)""")
