package com.labteto.dshmobile.local.chat

internal fun characterProactiveDirective(
    trigger: String,
    persona: PersonaProfile,
    state: ChatCharacterState,
): String {
    val lifeReason = characterLifeProactiveReason(persona, state)
    val unfinished = state.unresolvedThreads.takeLast(2).joinToString("；").take(220)
    val agenda = state.currentAgenda.take(120)
    return """
        【定时主动互动】
        这是用户提前为当前角色设置的主动互动意图，时间到了。它只是幕后触发条件，不是用户刚发来的消息。
        触发意图：$trigger
        人物此刻可用的生活理由：${lifeReason ?: "没有明确的新生活事件"}
        曾经真实留下的未完事项：${unfinished.ifBlank { "无" }}
        人物已有的当前安排：${agenda.ifBlank { "无" }}
        现在由【${persona.name}】主动给用户发一条新消息，延续当前人物、关系和故事。
    """.trimIndent()
}
