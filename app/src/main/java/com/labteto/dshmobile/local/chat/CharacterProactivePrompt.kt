package com.labteto.dshmobile.local.chat

internal fun characterProactiveDirective(
    trigger: String,
    persona: PersonaProfile,
    state: ChatCharacterState,
    context: ChatContextState = ChatContextState(),
): String {
    // Automated messages must not bypass the same attention policy used for direct replies.
    val attention = context.attention
    val unfinished = state.unresolvedThreads
        .filter { ChatTurnContextPolicy.relevant(trigger, it, attention) }
        .takeLast(2).joinToString("；").take(220)
    val agenda = state.currentAgenda.takeIf {
        ChatTurnContextPolicy.relevant(trigger, it, attention)
    }.orEmpty().take(120)
    val lifeReason = state.lifeState.activeEvents
        .asReversed()
        .firstOrNull {
            it.active && it.source !in setOf("agenda", "concern", "unfinished", "persona") &&
                ChatTurnContextPolicy.relevant(trigger, it.summary, attention)
        }?.summary
    return """
        【定时主动互动】
        这是用户提前为当前角色设置的主动互动意图，时间到了。它只是幕后触发条件，不是用户刚发来的消息。
        触发意图：$trigger
        人物此刻可用的生活理由：${lifeReason ?: "没有明确的新生活事件"}
        曾经真实留下的未完事项：${unfinished.ifBlank { "无" }}
        人物已有的当前安排：${agenda.ifBlank { "无" }}
        现在由【${persona.name}】以自己的口吻自然发一条新消息；触发条件不必对用户解释。可以关心、分享或接续未完事项，但不要编造新发生的共同经历、用户许可或约定。表达随人物性格与此刻情境变化，不套用统一开场模板。
    """.trimIndent()
}
