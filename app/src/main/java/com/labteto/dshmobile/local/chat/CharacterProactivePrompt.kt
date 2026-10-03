package com.labteto.dshmobile.local.chat

internal fun characterProactiveDirective(
    trigger: String,
    persona: PersonaProfile,
    state: ChatCharacterState,
): String {
    val lifeReason = characterLifeProactiveReason(persona, state)
    return """
        【定时主动互动】
        这是用户提前为当前角色设置的主动互动意图，时间到了。它只是幕后触发条件，不是用户刚发来的消息。
        触发意图：$trigger
        人物此刻可用的生活理由：${lifeReason ?: "没有明确的新生活事件"}
        现在由【${persona.name}】主动给用户发一条新消息，延续当前人物、关系和故事。
        优先从真实生活理由、共同物、未完事项或已有情绪中选择一个自然切口；如果没有明确理由，就发轻量普通消息，不编造重大事件。
        不要提“定时任务”“自动化”“触发”“系统提醒”等机制，也不要编造用户刚刚说过触发意图里的文字。
        只输出角色此刻真正会发给用户的消息，不加说明、标题、分析或幕后旁白。
    """.trimIndent()
}
