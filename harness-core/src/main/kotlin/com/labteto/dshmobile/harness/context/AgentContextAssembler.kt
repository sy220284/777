package com.labteto.dshmobile.harness.context

data class AgentContextMemory(
    val scope: String,
    val kind: String,
    val content: String,
)

/**
 * Deterministic, platform-neutral assembly of ephemeral model context.
 *
 * Storage and retrieval stay outside the core. This class owns only the bounded text that is
 * allowed to enter a model request, so Android and future hosts cannot drift in formatting or
 * budget rules.
 */
class AgentContextAssembler(
    private val maxRuleChars: Int = 3_000,
    private val maxMemoryItems: Int = 6,
    private val maxSingleMemoryChars: Int = 800,
    private val maxHandoffChars: Int = 3_500,
) {
    fun compose(
        rules: String,
        memories: List<AgentContextMemory>,
        handoffSummary: String?,
    ): String = buildString {
        val cleanRules = rules.trim().take(maxRuleChars)
        if (cleanRules.isNotEmpty()) {
            appendLine("【用户长期规则】")
            appendLine(cleanRules)
        }

        val boundedMemories = memories.take(maxMemoryItems)
        if (boundedMemories.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("【相关长期记忆】")
            boundedMemories.forEach { memory ->
                append("- [")
                    .append(memory.scope.lowercase())
                    .append('/')
                    .append(memory.kind.lowercase())
                    .append("] ")
                    .appendLine(memory.content.trim().take(maxSingleMemoryChars))
            }
            appendLine("若长期记忆与用户本轮明确新要求冲突，以本轮新要求为准。")
        }

        val handoff = handoffSummary?.trim()?.take(maxHandoffChars).orEmpty()
        if (handoff.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("【上一会话交接】")
            appendLine(handoff)
        }
    }.trim()
}
