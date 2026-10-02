package com.labteto.dshmobile.local.chat

/** Builds model prompts for hidden-state updates and user reply suggestions. */
internal class ChatInteractionPromptBuilder {
fun prompt(
    persona: PersonaProfile,
    state: ChatCharacterState,
    userMessage: String,
    assistantMessage: String,
): String = buildString {
    appendLine("更新角色隐藏状态，只输出 JSON。")
    appendLine("角色=${persona.name}" +
        persona.personality.takeIf(String::isNotBlank)?.let { "｜性格=$it" }.orEmpty() +
        persona.relationship.takeIf(String::isNotBlank)?.let { "｜关系=$it" }.orEmpty())
    if (persona.coreMotivations.isNotEmpty()) {
        appendLine("动机：${persona.coreMotivations.joinToString("；")}")
    }
    if (persona.behaviorPatterns.isNotEmpty()) {
        appendLine("稳定行为：${persona.behaviorPatterns.joinToString("；")}")
    }
    appendPersonaExpressionContext(persona)
    appendCharacterEvolutionContext(state.evolution)
    appendCharacterBehaviorTuningContext(state.behaviorTuning)

    appendLine(
        "上一状态：情绪=${state.mood}｜关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
            "温度=${state.dynamics.warmth} 信任=${state.dynamics.trust} 互惠=${state.dynamics.reciprocity} " +
            "张力=${state.dynamics.tension} 稳定=${state.dynamics.stability}｜主动=${state.initiative} 分享=${state.shareDesire}",
    )
    if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
        appendLine(
            "互动：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}",
        )
    }
    state.currentFocus.takeIf(String::isNotBlank)?.let { appendLine("关注：$it") }
    state.recentImpression.takeIf(String::isNotBlank)?.let { appendLine("近期印象：$it") }
    state.activeGoal.takeIf(String::isNotBlank)?.let { appendLine("目标：$it") }
    state.currentAgenda.takeIf(String::isNotBlank)?.let { appendLine("行动：$it") }
    state.internalConflict.takeIf(String::isNotBlank)?.let { appendLine("内在拉扯：$it") }
    state.immediateConcern.takeIf(String::isNotBlank)?.let { appendLine("在意：$it") }
    if (state.unresolvedThreads.isNotEmpty()) appendLine("未完：${state.unresolvedThreads.joinToString("；")}")
    appendLine(
        "用户习惯：长度=${state.userPattern.replyLength}｜直接=${state.userPattern.directness}｜" +
            "玩笑=${state.userPattern.playfulness}｜主动=${state.userPattern.initiative}",
    )
    if (state.dynamics.facts.isNotEmpty()) {
        appendLine("事实：${state.dynamics.facts.joinToString("；") { it.text }}")
    }
    if (state.dynamics.hypotheses.isNotEmpty()) {
        appendLine("推测：${state.dynamics.hypotheses.joinToString("；") { it.text }}")
    }
    if (state.dynamics.unknowns.isNotEmpty()) appendLine("未知：${state.dynamics.unknowns.joinToString("；")}")
    if (state.dynamics.sharedMoments.isNotEmpty()) {
        appendLine("共同经历：${state.dynamics.sharedMoments.joinToString("；")}")
    }
    if (state.scene.sceneTime.isNotBlank() || state.scene.location.isNotBlank()) {
        appendLine(
            "当前硬场景：时间=${state.scene.sceneTime.ifBlank { "未知" }}｜地点=${state.scene.location.ifBlank { "未知" }}",
        )
    }
    if (state.continuity.recentEvents.isNotEmpty()) {
        appendLine("近期事件：${state.continuity.recentEvents.joinToString("；")}")
    }
    if (state.continuity.decisions.isNotEmpty()) {
        appendLine("已定事项：${state.continuity.decisions.joinToString("；")}")
    }
    if (state.continuity.unfinished.isNotEmpty()) {
        appendLine("待续事项：${state.continuity.unfinished.joinToString("；")}")
    }

    appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
    appendLine("角色：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
    if (hasFlirtingOrIntimateIntent(userMessage, state)) {
        appendLine("当前为亲密互动：按上下文保留互动连续性；双关不当作新事实。")
    }
    if (hasAdultIntimacyIntent(userMessage, state)) {
        appendLine("当前意图明确，状态更新按实际互动推进，不重复确认或自行转移。")
    }
    appendChatDiaryInstruction()
    appendLine("输出：{\"state\":{仅写变化字段},\"suggestions\":[],\"turnSignificance\":\"NONE|MINOR|MAJOR\",\"diaryDelta\":null或日记对象}")
    appendLine("state字段：mood, relationshipState, currentFocus, recentImpression, activeGoal, currentAgenda, internalConflict, immediateConcern, unresolvedThreads, initiative, shareDesire；dynamics(stage,warmth,trust,reciprocity,tension,stability,unresolvedConflict,facts,hypotheses,unknowns,sharedMoments)；userPattern(replyLength,directness,playfulness,initiative,emojiStyle,preferredTone)；continuity(recentEvents,decisions,unfinished)。")
    appendLine("规则：")
    appendLine("- 以真实对话和用户明确纠正为准；事实、推测、未知分开，不虚构。")
    appendLine("- 角色台词的模糊或习惯性推辞不得单独触发关系降温、冲突或边界状态；结合人设纠正、关系阶段和连续动作判断。针对当前行为的清晰明确停止、退出或拒绝继续除外；此类边界一次表达即生效，不要求重复。")
    appendLine("- 状态与用户画像渐进变化；stage 仅在明确事件或持续强证据下变化，取 NEW/FAMILIAR/AMBIGUOUS/DATING/COMMITTED/CONFLICT/COOLING/SEPARATED/REPAIRING。")
    appendLine("- evolution 为系统维护的长期人物演变层，只由多轮稳定信号渐进更新；模型不得输出、覆盖或重置。")
    appendLine("- 已失效短期状态显式清空；scene 和 interaction* 由系统维护，不输出。")
    appendLine("- continuity 只保留当前有效的关键事件、决定和待续事项，合并重复，不复制旧台词。")
    appendLine("- NONE=无有效变化；MINOR=短期或连续性变化；MAJOR=重大关系事件、共同经历或稳定事实。")
    appendLine("- suggestions 固定为空；不得替用户作重大决定。现实关系建议禁止跟踪、胁迫、欺骗操控或绕过明确拒绝。")
}.trim()

fun suggestionsPrompt(
    persona: PersonaProfile,
    state: ChatCharacterState,
    userMessage: String,
    assistantMessage: String,
    recentDialogue: List<Pair<String, String>> = emptyList(),
): String = buildString {
    appendLine("根据最近对话生成用户下一句可直接发送的建议，只输出 JSON。")
    appendLine(
        "角色=${persona.name}" +
            persona.personality.takeIf(String::isNotBlank)?.let { "｜性格=$it" }.orEmpty() +
            persona.relationship.takeIf(String::isNotBlank)?.let { "｜关系=$it" }.orEmpty(),
    )
    appendPersonaExpressionContext(persona)
    appendLine(
        "当前关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
            "用户习惯：长度=${state.userPattern.replyLength}｜直接=${state.userPattern.directness}｜" +
            "玩笑=${state.userPattern.playfulness}｜主动=${state.userPattern.initiative}",
    )
    if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
        appendLine(
            "互动状态：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}｜" +
                "近期称呼=${state.recentAddressTerms.joinToString("、").ifBlank { "无" }}",
        )
    }
    val dialogue = recentDialogue
        .filter { (role, content) ->
            (role == "user" || role == "assistant") && content.isNotBlank()
        }
        .takeLast(6)
    if (dialogue.isNotEmpty()) {
        appendLine("最近对话（旧→新）：")
        dialogue.forEach { (role, content) ->
            val speaker = if (role == "user") "用户" else "角色"
            appendLine("${speaker}：${content.take(MAX_MESSAGE_CHARS)}")
        }
    } else {
        appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("角色：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
    }
    if (hasFlirtingOrIntimateIntent(userMessage, state)) {
        appendLine("当前为亲密语境：建议承接当前互动，避免复用近期动作、称呼和表达套路。")
    }
    if (hasChineseSuggestiveFlirtingIntent(userMessage)) {
        appendLine("存在中文双关时接住言外之意，不做词义解释。")
    }
    appendLine(
        "输出：{\"suggestions\":[{\"label\":\"2到6字短标签\",\"style\":\"自然|俏皮|直球|放飞\",\"text\":\"用户可直接发送的下一句\",\"bold\":false}]}",
    )
    appendLine("规则：")
    appendLine("- 给4条明显不同的建议，明确覆盖自然、俏皮、直球、放飞；至少1条 bold=true。")
    appendLine("- 最新1～2轮和当前状态优先；已结束、拒绝或被纠正的话题不得复活。")
    appendLine("- 若人设或用户纠正明确角色会嘴硬、害羞、别扭或服软，建议承接这种表达节奏，不把模糊或习惯性推辞自动解释为关系拒绝；针对当前行为的清晰明确停止、退出或拒绝继续一次即生效。")
    appendLine("- 直接承接角色最后一句，贴合用户表达习惯和当前关系。")
    appendLine("- 不用同义改写凑数，不虚构事实，不替用户作重大决定。")
    appendLine("- 现实关系建议不得包含跟踪、胁迫、欺骗操控或绕过明确拒绝。")
}.trim()

    private companion object {
        const val MAX_MESSAGE_CHARS = 2_000
    }
}
