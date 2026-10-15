package com.labteto.dshmobile.local.chat

/** Builds model prompts for hidden-state updates and user reply suggestions. */
internal class ChatInteractionPromptBuilder {
    private val intimacyMode = ChatIntimacyMode()
    fun prompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): String = buildString {
        appendLine("更新人物隐藏状态，只输出 JSON。")
        appendPostTurnPersonaContext(persona)

        appendLine("上一状态：身体=${state.physicalState.ifBlank { "无" }}｜情绪=${state.mood}｜关系=${state.relationshipState}")
        state.currentFocus.takeIf(String::isNotBlank)?.let { appendLine("当前注意：$it") }
        state.currentUserImpression.takeIf(String::isNotBlank)?.let { appendLine("对用户的主观印象：$it") }
        state.activeGoal.takeIf(String::isNotBlank)?.let { appendLine("目标：$it") }
        state.currentAgenda.takeIf(String::isNotBlank)?.let { appendLine("正在做：$it") }
        state.internalConflict.takeIf(String::isNotBlank)?.let { appendLine("此刻拉扯：$it") }
        state.immediateConcern.takeIf(String::isNotBlank)?.let { appendLine("脑中挂着：$it") }
        if (state.unresolvedThreads.isNotEmpty()) appendLine("未完：${state.unresolvedThreads.joinToString("；")}")
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
        state.stateObservations.takeLast(4).forEach { observation ->
            appendLine("上一状态依据（${observation.field}，${if (observation.interpretation) "对话归纳" else "原文表达"}）：${observation.basis}")
        }
        if (state.scene.sceneTime.isNotBlank() || state.scene.location.isNotBlank()) {
            appendLine(
                "当前硬场景：时间=${state.scene.sceneTime.ifBlank { "未知" }}｜地点=${state.scene.location.ifBlank { "未知" }}",
            )
        }
        if (state.continuity.recentEvents.isNotEmpty()) {
            appendLine("近期事件：${state.continuity.recentEvents.joinToString("；")}")
        }
        val visibleDecisions = ChatContinuityPromptProjection.select(
            state.continuity.decisions, userMessage,
        )
        val visibleUnfinished = ChatContinuityPromptProjection.select(
            state.continuity.unfinished, userMessage,
        )
        if (visibleDecisions.isNotEmpty()) {
            appendLine("已定事项（仅选取本轮相关或近期的活动事项，编号用于准确撤销/修正）：")
            visibleDecisions.forEach { fact ->
                val source = state.continuity.evidence.firstOrNull {
                    it.kind == ChatContinuityFactKind.DECISION && it.text == fact
                }
                val id = source?.eventId?.takeIf(String::isNotBlank)
                    ?: chatContinuityEventId(
                        ChatContinuityFactKind.DECISION, fact, source?.sourceSequence ?: 0L,
                    )
                appendLine("- $id：$fact")
            }
        }
        if (visibleUnfinished.isNotEmpty()) {
            appendLine("待续事件（编号仅用于准确结清；未展示事项不代表已完成）：")
            visibleUnfinished.forEach { fact ->
                val source = state.continuity.evidence.firstOrNull {
                    it.kind == ChatContinuityFactKind.OPEN_THREAD && it.text == fact
                }
                val id = source?.eventId?.takeIf(String::isNotBlank)
                    ?: chatContinuityEventId(
                        ChatContinuityFactKind.OPEN_THREAD, fact, source?.sourceSequence ?: 0L,
                    )
                appendLine("- $id：$fact")
            }
        }

        appendLine("用户：${userMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("人物：${assistantMessage.take(MAX_MESSAGE_CHARS)}")
        appendLine("输出：{\"state\":{仅写变化字段},\"stateEvidence\":{\"变化字段名\":\"支持变化的本轮原文\"},\"resolvedContinuityIds\":[],\"suggestions\":[],\"turnSignificance\":\"NONE|MINOR|MAJOR\",\"diaryDelta\":null}")
        appendLine(
            "state字段：physicalState, mood, relationshipState, currentFocus, activeGoal, " +
                "currentAgenda, internalConflict, immediateConcern, unresolvedThreads, currentUserImpression；" +
                "dynamics(stage,unresolvedConflict,facts,hypotheses,unknowns,sharedMoments,sharedObjects)；" +
                "continuity(recentEvents,decisions,unfinished)。",
        )
        appendLine("dynamics.unresolvedConflict 为字符串，无冲突写空字符串。")
        appendLine("dynamics.facts/hypotheses 必须是对象数组，每项含 text(字符串)、confidence(整数)、source(字符串)；没有可核实的新证据就省略这些字段，禁止用字符串数组替代。")
        appendLine("规则：")
        appendLine("- 以真实对话和用户明确纠正为准；事实、推测、未知分开，不虚构人物生活、心理或用户动机。")
        appendLine("- 只记已经发生的互动与人物实际表达；主观印象可存在但不等于客观事实，也不为下一轮预设反应。")
        appendLine("- physicalState 只在对话确有证据时更新，例如困、饿、刚醒、头疼、手上正忙；没有证据不要编。")
        appendLine("- currentUserImpression 是人物长期主观印象，只在有新证据时修正；允许不完整和犯错，但不能把猜测升级为用户事实，也不按轮数自动消失。")
        appendLine("- sharedObjects 只保存双方真实建立、未来可能自然回调的小物件、共同梗、未完成小约定；普通名词不写入。")
        appendLine("- 生活习惯与本轮实际行动分开，不能把习惯视为已发生事件。")
        appendLine("- stage 只描述有明确证据的实际关系事件，取 NEW/FAMILIAR/AMBIGUOUS/DATING/COMMITTED/CONFLICT/COOLING/SEPARATED/REPAIRING。")
        appendLine("- 重大关系变化只通过 dynamics.stage 及其本轮真实对话证据确认，relationshipState 不可独立写入结婚、分手、恋爱等阶段结论。")
        appendLine("- 发生关系阶段变化时必须在 stateEvidence 的 dynamics.stage 填写本轮真实对话原文片段；非阶段性的相处描述更新须提供 stateEvidence.relationshipState 的真实关系语句。不能引用假设、第三人或否定句，也不能凭单个关键词猜测。")
        appendLine("- 废弃的关系数值与人物主动评分不再生成；以共同经历、明确事件和真实行为证据描述关系。")
        appendLine("- evolution 与 lifeState 为兼容与事件投影字段，模型不输出。")
        appendLine("- 情绪、身体状态、当前安排与未完事项由本轮实际事件更新；省略字段保留原值，已结束的状态显式清空。scene 和 interaction* 由系统维护，不输出。")
        appendLine("- 重新核对上一状态是否仍然成立；已经说开、完成、撤销或纠正的事项更新为当前版本。状态值为空字符串表示结束，mood为空或自然表示已无特定情绪；dynamics.unresolvedConflict为空表示矛盾已解。不要仅因聊了几轮清除状态，也不要把已解事项永久保留。")
        appendLine("- stateEvidence引用实际对话，嵌套字段用dynamics.unresolvedConflict等完整字段名；不编造引文。人物的主观理解与用户事实分开，未知情绪不补写。")
        appendLine("- dynamics.unknowns 若输出，必须是本轮处理后仍未解答问题的完整列表；已由对话确认的问题移除，全部解答则输出 []。省略字段代表本轮没有检查此项。")
        appendLine("- 用户说不再讨论某话题，不等于该事件从历史中删除；应停止把它当成本轮目标，后续明确重新提起时仍可回忆。")
        appendLine("- resolvedContinuityIds 只填写本轮已完成/撤销的已定事项或待续事件编号；每个编号必须由本批用户真实原话支持且已从连续性活动列表退出，不能猜编号、不能只因语义相似就删除。")
        appendLine("- continuity 只保留当前有效的关键事件、决定和待续事项，合并重复，不复制旧台词。")
        appendLine("- NONE=无值得长期记住的经历或人物思想变化，但仍须更新有依据的短期状态、结清与纠正；MINOR=有值得延续的小事件或角色因真实经历形成持续的个人思想变化；MAJOR=重大关系事件、共同经历或稳定事实。普通寒暄无需日记，思想变化也不得虚构触发它的经历。")
        appendLine("- NONE 下新增非空状态时须在 stateEvidence 提供本轮双方真实原话。显式空字段清理不受重要程度阻止；不能编造新状态。")
        appendLine("- suggestions 固定为空。")
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
                "人物当前情绪=${state.mood}",
        )
        state.currentUserImpression.takeIf(String::isNotBlank)?.let { appendLine("人物目前对用户的印象：$it") }

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
        intimacyMode.suggestionContext(userMessage, state)
            .takeIf(String::isNotBlank)
            ?.let { appendLine(it) }
        appendLine(
            "输出：{\"suggestions\":[{\"label\":\"2到6字短标签\",\"style\":\"自然|俏皮|直球|放飞\",\"text\":\"用户可直接发送的下一句\",\"bold\":false}]}",
        )
        appendLine("规则：")
        appendLine("- 给1至4条真正不同、自然可发送的建议；style仅从自然、俏皮、直球、放飞中选择最贴切的标签，不强制覆盖所有风格，也不为凑数重复改写。bold仅在确实值得突出时为true。")
        appendLine("- 最新1～2轮和当前状态优先；已结束、拒绝或被纠正的话题不得复活。")
        appendLine("- 建议服务于用户自己的表达，不照搬人物语言风格资料，也不替用户表演对方性格。")
        appendLine("- 用户明确纠正和当前上下文决定承接节奏；模糊表达要结合前后行为与关系语境理解，不把单个模糊推辞自动解释为关系拒绝；针对当前行为的清晰明确停止、退出或拒绝继续一次即生效。")
        appendLine("- 直接承接人物最后一句，按最近对话中真实的用户表达和当前关系自然写作，不使用旧画像评分。")
        appendLine("- 不用同义改写凑数，不虚构事实，不替用户作重大决定。")
    }.trim()

    private companion object {
        const val MAX_MESSAGE_CHARS = 2_000
    }
}
