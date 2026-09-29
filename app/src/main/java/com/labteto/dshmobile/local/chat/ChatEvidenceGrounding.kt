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

    val sourceText = when (source) {
        "user", "explicit" -> normalizeChatEvidence(userMessage)
        "observed", "dialogue" -> normalizeChatEvidence(userMessage + assistantMessage)
        else -> return false
    }
    if (sourceText.length < 2) return false
    if (sourceText.contains(evidenceText) || evidenceText.contains(sourceText)) return true

    val evidenceBigrams = chatEvidenceBigrams(evidenceText)
    val sourceBigrams = chatEvidenceBigrams(sourceText)
    if (evidenceBigrams.isEmpty() || sourceBigrams.isEmpty()) return false
    val shared = evidenceBigrams.count(sourceBigrams::contains)
    return shared >= 2 && shared.toDouble() / evidenceBigrams.size >= 0.25
}

private fun normalizeChatEvidence(value: String): String =
    value.trim().lowercase().replace(Regex("""[\s，。！？、,.!?；;：:"'“”‘’（）()\[\]【】]+"""), "")

private fun chatEvidenceBigrams(text: String): Set<String> =
    if (text.length < 2) emptySet()
    else (0 until text.length - 1).mapTo(linkedSetOf()) { index ->
        text.substring(index, index + 2)
    }
