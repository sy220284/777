package com.labteto.dshmobile.local.chat

/**
 * The only assembly boundary for model-facing chat context in direct, group and proactive modes.
 *
 * Source owners decide what is true and what this character knows. This boundary decides whether
 * that already-validated content belongs in the current reply. It never changes durable memory.
 */
internal enum class ChatContextLayer {
    LIVE,               // Current bodily condition, actual scene and current interaction.
    HISTORICAL,         // Old events, shared experiences and story checkpoints.
    RECALL,             // Search results and diarized historical material.
    INSTRUCTION,        // Role, audience, group announcement and authorized timer intent.
}

internal data class ChatContextPart(
    val layer: ChatContextLayer,
    val text: String,
)

internal object ChatUnifiedTurnContext {
    fun compose(
        userMessage: String,
        context: ChatContextState,
        parts: List<ChatContextPart>,
    ): String {
        val seen = hashSetOf<String>()
        return parts.asSequence()
            .filter { it.text.isNotBlank() }
            .filter { part ->
                part.layer == ChatContextLayer.LIVE ||
                    part.layer == ChatContextLayer.INSTRUCTION ||
                    allowsHistoricalRecord(userMessage, context, part.text)
            }
            // Deduplicate exactly equivalent blocks, never conflate different promises.
            .filter { part -> seen.add(part.text.trim().replace(Regex("""\s+"""), " ")) }
            .joinToString("\n\n") { it.text.trim() }
    }

    fun allowsHistoricalRecord(
        userMessage: String,
        context: ChatContextState,
        content: String,
    ): Boolean {
        if (content.isBlank() || ChatTurnContextPolicy.isTopicRedirect(userMessage)) return false
        val attention = context.attention
        // A generic "换个话题" suppresses implicit old-memory injections until a new
        // substantive subject exists. A new explicit historical question can still recall.
        if (attention.pausedSubject == "*" && ChatTurnContextPolicy.isWeakReply(userMessage) &&
            attention.lastUserSubject.isBlank()) return false
        val paused = attention.pausedSubject.takeIf { it.isNotBlank() && it != "*" }
            ?: return true
        if (content.contains(paused, ignoreCase = true) &&
            !userMessage.contains(paused, ignoreCase = true)) {
            return false
        }
        return true
    }
}
