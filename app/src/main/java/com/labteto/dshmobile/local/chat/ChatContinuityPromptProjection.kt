package com.labteto.dshmobile.local.chat

/**
 * Prompt selection is deliberately independent from event persistence.
 * An old promise may be absent from this turn's prompt without being deleted or resolved.
 */
internal object ChatContinuityPromptProjection {
    internal const val RELATED_EVENT_BUDGET = 10
    internal const val RECENT_EVENT_BUDGET = 10

    fun select(active: List<String>, userMessage: String): List<String> {
        if (active.isEmpty()) return emptyList()
        val related = active.filter {
            ChatTurnContextPolicy.relevant(userMessage, it)
        }.takeLast(RELATED_EVENT_BUDGET)
        val recent = active.takeLast(RECENT_EVENT_BUDGET)
        return (related + recent).distinct()
    }
}
