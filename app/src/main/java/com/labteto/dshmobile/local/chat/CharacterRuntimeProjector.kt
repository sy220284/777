package com.labteto.dshmobile.local.chat

/**
 * Single request-time projection for the V3 character-life runtime.
 *
 * Rich persona/state data remains durable, while each model turn receives only a small stable
 * identity prefix plus the moment-specific facts that can actually affect this reply.
 */
internal class CharacterRuntimeProjector(
    private val relationshipEngine: ChatRelationshipEngine,
    private val loreEngine: CharacterLoreEngine,
) {
    fun project(
        persona: PersonaProfile,
        state: ChatCharacterState,
        context: ChatContextState,
        userInput: String,
        storyContext: String?,
    ): CharacterRuntimeProjection {
        val privateState = state.copy(scene = ChatSceneState(), continuity = ChatContinuityState())
        val storyPrompt = storyContext?.takeIf(String::isNotBlank)?.let {
            """
            【连续性摘要｜已发生】
            ${it.take(MAX_STORY_CONTEXT_CHARS)}
            只把它当作已经发生的背景；当前输入、当前状态和更精确的记忆优先，不主动复述旧内容。
            """.trimIndent()
        }.orEmpty()

        return CharacterRuntimeProjection(
            stablePrompt = stablePrompt(persona),
            dynamicPrompt = listOf(
                momentPrompt(persona, privateState),
                composePersonaExpressionSemanticsPrompt(persona),
                renderChatContextForModel(context),
                renderChatTurnModeForModel(userInput),
                composeRoleplayNoveltyPrompt(privateState),
                relevantBackgroundPrompt(persona, userInput),
                storyPrompt,
                loreEngine.prompt(persona, userInput),
                relationshipEngine.prompt(userInput, privateState),
            ).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    private fun stablePrompt(persona: PersonaProfile): String = buildString {
        appendLine("【人物】${persona.name}")
        appendLine("下面是一个认识你很久的人对你的理解。它是你自然生活和判断的底色，不是每轮需要展示的人设清单。")
        persona.portrait.takeIf(String::isNotBlank)?.let { appendLine(it.take(1_600)) }
        persona.lifeContext.takeIf(String::isNotBlank)?.let {
            appendLine("【你的生活】")
            appendLine(it.take(1_200))
        }

        fun section(title: String, values: List<String>, limit: Int) {
            val selected = values.asSequence().map(String::trim).filter(String::isNotBlank).take(limit).toList()
            if (selected.isEmpty()) return
            appendLine("【$title】")
            selected.forEach { appendLine("- ${it.take(220)}") }
        }

        section("你天然会注意", persona.attentionBiases, 4)
        section("你有时会漏掉或看偏", persona.perceptionBlindSpots, 3)
        section("没有必要解释的小习惯", persona.quirks, 4)
        section("你不擅长", persona.limitations, 3)
        section("真正重要的东西", persona.coreValues, 3)
        persona.coreTension.takeIf(String::isNotBlank)?.let {
            appendLine("【长期内在拉扯】")
            appendLine(it.take(360))
        }
        section("不容易被关系改变", persona.stableTraits, 4)
        section("可以被经历慢慢改变", persona.mutableTraits, 4)
        section("知识边界", persona.knowledgeBoundary, 5)
        section("硬约束", persona.hardConstraints, 6)

        val samples = persona.voiceSamples.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_STABLE_VOICE_SAMPLES)
            .toList()
        if (samples.isNotEmpty()) {
            appendLine("【声音感觉】")
            samples.forEach { appendLine("- ${it.take(180)}") }
            appendLine("这些只是节奏参考，不复读，不把其中词句当口头禅。")
        }

        append(
            "你有自己的生活和注意力。大多数聊天可以普通、平淡，甚至没什么值得记住；" +
                "不用证明自己有性格，也不用每句话都完整、有用、漂亮。真正重要的经历才会留下影响。",
        )
    }.trim()

    private fun momentPrompt(persona: PersonaProfile, state: ChatCharacterState): String {
        val lines = mutableListOf<String>()
        state.physicalState.takeIf(String::isNotBlank)?.let { lines += "身体：${it.take(140)}" }
        state.mood.takeUnless { it.isBlank() || it == "自然" }?.let { lines += "情绪：${it.take(100)}" }
        state.currentAgenda.takeIf(String::isNotBlank)?.let { lines += "正在做：${it.take(180)}" }
        state.immediateConcern.takeIf(String::isNotBlank)?.let { lines += "脑子里还挂着：${it.take(180)}" }
        state.currentFocus.takeIf(String::isNotBlank)?.let { lines += "这一刻容易注意：${it.take(180)}" }
        state.recentImpression.takeIf(String::isNotBlank)?.let { lines += "你目前怎么看对方：${it.take(240)}" }
            ?: persona.initialUserImpression.takeIf(String::isNotBlank)?.let {
                lines += "你目前怎么看对方：${it.take(240)}"
            }
        state.internalConflict.takeIf(String::isNotBlank)?.let { lines += "此刻的拉扯：${it.take(180)}" }
        state.dynamics.unresolvedConflict.takeIf(String::isNotBlank)?.let {
            lines += "关系里还没过去的事：${it.take(180)}"
        }
        state.dynamics.sharedObjects.takeLast(2).takeIf(List<String>::isNotEmpty)?.let {
            lines += "你们之间还在延续的小东西：${it.joinToString("；").take(260)}"
        }
        state.unresolvedThreads.takeLast(2).takeIf(List<String>::isNotEmpty)?.let {
            lines += "还没做完/说完：${it.joinToString("；").take(260)}"
        }

        val responseSpace = when {
            state.physicalState.containsAny("困", "刚醒", "头疼", "忙", "走路", "开车", "骑车", "没电") ->
                "回复空间：偏短，注意力有限，不必展开。"
            state.mood.containsAny("生气", "烦", "难过", "紧张", "委屈", "失望") ->
                "回复空间：允许只接住最在意的一处，不必马上把情绪解释清楚。"
            else -> "回复空间：自然；只回应真正注意到的部分，不必覆盖对方消息的每一点。"
        }
        lines += responseSpace
        lines += "【此刻】只是背景噪音，不是表演任务；没有自然理由时，不要把这些内容主动说出来。"

        return buildString {
            appendLine("【此刻】")
            lines.forEach(::appendLine)
        }.trim()
    }

    private fun composeRoleplayNoveltyPrompt(state: ChatCharacterState): String {
        val cooling = state.interactionCooldowns.filterValues { it > 0 }.entries
            .sortedByDescending { it.value }.take(8)
        if (
            state.recentActionTags.isEmpty() && state.recentPoseTags.isEmpty() &&
            state.recentVerbalTags.isEmpty() && state.recentAddressTerms.isEmpty() && cooling.isEmpty()
        ) return ""
        return buildString {
            appendLine("【近期表现去重】")
            if (state.recentActionTags.isNotEmpty()) appendLine("动作：${state.recentActionTags.joinToString("、")}")
            if (state.recentPoseTags.isNotEmpty()) appendLine("姿态：${state.recentPoseTags.joinToString("、")}")
            if (state.recentVerbalTags.isNotEmpty()) appendLine("表达：${state.recentVerbalTags.joinToString("、")}")
            if (state.recentAddressTerms.isNotEmpty()) appendLine("称呼：${state.recentAddressTerms.joinToString("、")}")
            if (cooling.isNotEmpty()) appendLine("近期已用：${cooling.joinToString("；") { it.key }}")
            append("只在确有重复风险时避开这些节拍；不要为了显得真人而故意走神、犯错或制造残句。")
        }.trim()
    }

    private fun relevantBackgroundPrompt(persona: PersonaProfile, userInput: String): String {
        if (userInput.isBlank()) return ""
        val anchors = listOf(
            "生活" to persona.lifeContext,
            "世界" to persona.worldSetting,
            "时间线" to persona.timelinePosition,
            "来源" to persona.franchise,
        ).filter { (_, value) -> value.isNotBlank() && relevantTo(value, userInput) }
        if (anchors.isEmpty()) return ""
        return buildString {
            appendLine("【本轮相关背景】只在当前话题自然需要时使用，不主动扩写。")
            anchors.forEach { (label, value) -> appendLine("$label：${value.take(800)}") }
        }.trim()
    }

    private fun relevantTo(source: String, query: String): Boolean {
        val a = normalize(source)
        val b = normalize(query)
        if (a.isBlank() || b.length < 2) return false
        val pairs = if (b.length == 2) setOf(b) else b.windowed(2).toSet()
        val hits = pairs.count(a::contains)
        return hits >= minOf(2, pairs.size)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

    private companion object {
        const val MAX_STORY_CONTEXT_CHARS = 2_500
        const val MAX_STABLE_VOICE_SAMPLES = 4
    }
}

internal data class CharacterRuntimeProjection(
    val stablePrompt: String,
    val dynamicPrompt: String,
)
