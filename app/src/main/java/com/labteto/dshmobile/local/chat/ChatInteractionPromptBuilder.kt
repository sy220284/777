package com.labteto.dshmobile.local.chat

/** Builds model prompts for hidden-state updates and user reply suggestions. */
internal class ChatInteractionPromptBuilder {
    fun prompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): String = buildString {
        appendLine("更新人物隐藏状态，只输出 JSON。")
        appendPostTurnPersonaContext(persona)
        appendCharacterEvolutionContext(state.evolution)
        appendCharacterBehaviorTuningContext(state.behaviorTuning)

        appendLine(
            "上一状态：身体=${state.physicalState.ifBlank { "无" }}｜情绪=${state.mood}｜" +
                "关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
                "温度=${state.dynamics.warmth} 信任=${state.dynamics.trust} " +
                "互惠=${state.dynamics.reciprocity} 张力=${state.dynamics.tension} " +
                "稳定=${state.dynamics.stability}｜主动=${state.initiative} 分享=${state.shareDesire}",
        )
        if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
            appendLine(
                "互动：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                    "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                    "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}",
            )
        }
        state.currentFocus.takeIf(String::isNotBlank)?.let { appendLine("当前注意：$it") }
        state.currentUserImpression.takeIf(String::isNotBlank)?.let { appendLine("对用户的主观印象：$it") }
        state.activeGoal.takeIf(String::isNotBlank)?.let { appendLine("目标：$it") }
        state.currentAgenda.takeIf(String::isNotBlank)?.let { appendLine("正在做：$it") }
        state.internalConflict.takeIf(String::isNotBlank)?.let { appendLine("此刻拉扯：$it") }
        state.immediateConcern.takeIf(String::isNotBlank)?.let { appendLine("脑中挂着：$it") }
        if (state.unresolvedThreads.isNotEmpty()) appendLine("未完：${state.unresolvedThreads.joinToString("；")}")
        appendLine(
            "用户习惯：长度=${state.userPattern.replyLength}｜直接=${state.userPattern.directness}｜" +
                "玩笑=${state.userPattern.playfulness}｜主动=${state.userPattern.initiative}",
        )
        if (state.dynamics.facts.isNotEmpty()) {
            appendLine("关系事实：${state.dynamics.facts.joinToString("；") { it.text }}")
        }
        if (state.dynamics.hypotheses.isNotEmpty()) {
            appendLine("关系推测：${state.dynamics.hypotheses.joinToString("；") { it.text }}")
        }
        if (state.dynamics.unknowns.isNotEmpty()) appendLine("未知：${state.dynamics.unknowns.joinToString("；")}")
        if (state.dynamics.sharedMoments.isNotEmpty()) {
            appendLine("共同经历：${state.dynamics.sharedMoments.joinToString("；")}")
        }
        if (state.dynamics.sharedObjects.isNotEmpty()) {
            appendLine("共同物/共同梗/未完成小约定：${state.dynamics.sharedObjects.joinToString("；")}")
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
        appendLine("人物：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        if (hasFlirtingOrIntimateIntent(userMessage, state)) {
            appendLine("当前为亲密互动：按上下文保留互动连续性；双关不当作新事实。")
        }
        if (hasAdultIntimacyIntent(userMessage, state)) {
            appendLine("当前意图明确，状态更新按实际互动推进，不重复确认或自行转移。")
        }
        appendLine("输出：{\"state\":{仅写变化字段},\"suggestions\":[],\"turnSignificance\":\"NONE|MINOR|MAJOR\",\"diaryDelta\":null}")
        appendLine(
            "state字段：physicalState, mood, relationshipState, currentFocus, activeGoal, " +
                "currentAgenda, internalConflict, immediateConcern, unresolvedThreads, currentUserImpression, initiative, shareDesire；" +
                "dynamics(stage,warmth,trust,reciprocity,tension,stability,unresolvedConflict,facts,hypotheses,unknowns,sharedMoments,sharedObjects)；" +
                "userPattern(replyLength,directness,playfulness,initiative,emojiStyle,preferredTone)；continuity(recentEvents,decisions,unfinished)。",
        )
        appendLine("输出补丁必须遵守 JSON 类型：state.initiative/shareDesire 与 dynamics.warmth/trust/reciprocity/tension/stability 为0到100整数；dynamics.unresolvedConflict 为字符串，无冲突写空字符串。")
        appendLine("dynamics.facts/hypotheses 必须是对象数组，每项含 text(字符串)、confidence(整数)、source(字符串)；没有可核实的新证据就省略这些字段，禁止用字符串数组替代。")
        appendLine("规则：")
        appendLine("- 以真实对话和用户明确纠正为准；事实、推测、未知分开，不虚构人物生活、心理或用户动机。")
        appendLine("- 角色台词的模糊或习惯性推辞不得单独触发关系降温、冲突或边界状态；结合人物表达、关系阶段和持续参与证据判断。针对当前行为的清晰明确停止、退出或拒绝继续，一次表达即生效，不要求重复。")
        appendLine("- physicalState 只在对话确有证据时更新，例如困、饿、刚醒、头疼、手上正忙；没有证据不要编。")
        appendLine("- currentUserImpression 是人物长期主观印象，只在有新证据时修正；允许不完整和犯错，但不能把猜测升级为用户事实，也不按轮数自动消失。")
        appendLine("- sharedObjects 只保存双方真实建立、未来可能自然回调的小物件、共同梗、未完成小约定；普通名词不写入。")
        appendLine("- 人物生活流只从V4生活事实、既有生活事件、已发生经历和当前明确对话推进；允许低风险日常进展，禁止凭空生成重大人生事件、关系事实或不可逆事件。")
        appendLine("- 状态与用户画像渐进变化；stage 仅在明确事件或持续强证据下变化，取 NEW/FAMILIAR/AMBIGUOUS/DATING/COMMITTED/CONFLICT/COOLING/SEPARATED/REPAIRING。")
        appendLine("- warmth/trust/reciprocity/tension/stability 只是派生诊断；共同经历、共同物、明确事件和真实行为证据优先，禁止仅凭数值推动人物选择或关系跨级。")
        appendLine("- evolution 与 lifeState 为系统维护层；可变特质只由多轮真实经历渐进更新，模型不得输出、覆盖或重置。")
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
        appendReplySuggestionPersonaContext(persona)
        appendLine(
            "当前关系=${state.relationshipState}｜阶段=${state.dynamics.stage}｜" +
                "人物当前情绪=${state.mood}｜用户习惯：长度=${state.userPattern.replyLength}｜" +
                "直接=${state.userPattern.directness}｜玩笑=${state.userPattern.playfulness}｜主动=${state.userPattern.initiative}",
        )
        state.currentUserImpression.takeIf(String::isNotBlank)?.let { appendLine("人物目前对用户的印象：$it") }
        if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
            appendLine(
                "互动状态：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                    "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                    "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}｜" +
                    "近期称呼=${state.recentAddressTerms.joinToString("、").ifBlank { "无" }}",
            )
        }
        val dialogue = recentDialogue
            .filter { (role, content) -> (role == "user" || role == "assistant") && content.isNotBlank() }
            .takeLast(6)
        if (dialogue.isNotEmpty()) {
            appendLine("最近对话（旧→新）：")
            dialogue.forEach { (role, content) ->
                val speaker = if (role == "user") "用户" else "人物"
                appendLine("${speaker}：${content.take(MAX_MESSAGE_CHARS)}")
            }
        } else {
            appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
            appendLine("人物：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
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
        appendLine("- 建议服务于用户自己的表达，不照搬人物语言风格资料，也不替用户表演对方性格。")
        appendLine("- 用户明确纠正和当前上下文决定承接节奏；模糊表达要结合前后行为与关系语境理解，不把单个模糊推辞自动解释为关系拒绝；针对当前行为的清晰明确停止、退出或拒绝继续一次即生效。")
        appendLine("- 直接承接人物最后一句，贴合用户表达习惯和当前关系。")
        appendLine("- 不用同义改写凑数，不虚构事实，不替用户作重大决定。")
        appendLine("- 现实关系建议不得包含跟踪、胁迫、欺骗操控或绕过明确拒绝。")
    }.trim()

    private companion object {
        const val MAX_MESSAGE_CHARS = 2_000
    }
}
