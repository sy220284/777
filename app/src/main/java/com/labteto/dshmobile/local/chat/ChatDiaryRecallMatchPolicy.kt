package com.labteto.dshmobile.local.chat

internal data class ChatDiaryMatchScore(
    val semantic: Int,
    val total: Int,
)

/** Specific-vs-broad recall gating, independent from diary persistence and write refinement. */
internal object ChatDiaryRecallMatchPolicy {
    fun isRecallMatch(
        score: ChatDiaryMatchScore,
        broad: Boolean,
        query: String,
    ): Boolean {
        val hasSpecificAnchor = broad && hasSpecificRecallAnchor(query)
        return (broad && !hasSpecificAnchor) || score.semantic >= MIN_RECALL_SEMANTIC_SCORE
    }

    private fun hasSpecificRecallAnchor(query: String): Boolean {
        val core = normalize(query)
            .replace(SPECIFIC_RECALL_NOISE, "")
            .trim()
        return core.length >= 2 && core !in GENERIC_RECALL_CORES
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
            .take(1_200)

    private const val MIN_RECALL_SEMANTIC_SCORE = 24
    private val SPECIFIC_RECALL_NOISE = Regex(
        """(?:你还记得|还记得|你记得|记不记得|以前|之前|上次|第一次|当时|我跟你说过|我和你说过|你知道我|你还知道|那件事|那件|那次|那天|那时候|那个|这个|什么|吗|么|呢)""",
    )
    private val GENERIC_RECALL_CORES = setOf("我", "事", "事情", "这事", "那事")
}
