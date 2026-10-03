package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.estimateModelTokens

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
        val lifeState = advanceCharacterLife(persona, privateState)
        val runtimeState = privateState.copy(lifeState = lifeState)
        val attention = resolveCharacterAttention(persona, runtimeState, userInput)
        val behavior = resolveCharacterBehavior(persona, runtimeState, userInput, attention)
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
                takeWithinModelTokenBudget(momentPrompt(persona, runtimeState), MOMENT_TOKEN_BUDGET),
                renderCharacterLifePrompt(lifeState),
                renderCharacterAttentionPrompt(attention),
                renderCharacterBehaviorPrompt(behavior),
                composePersonaExpressionSemanticsPrompt(persona),
                renderChatContextForModel(context),
                renderChatTurnModeForModel(userInput),
                composeRoleplayNoveltyPrompt(runtimeState),
                relevantBackgroundPrompt(persona, userInput),
                storyPrompt,
                loreEngine.prompt(persona, userInput),
                relationshipEngine.prompt(userInput, runtimeState),
            ).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    private fun stablePrompt(persona: PersonaProfile): String {
        val critical = takeWithinModelTokenBudget(
            buildString {
                appendLine("【人物】${persona.name}")
                appendLine(COMMON_CHARACTER_BOUNDARY)
                appendLine(ANTI_PERFORMANCE_RULE)
                appendStableSection("真正重要的东西", persona.coreValues, 3, 120)
                appendStableSection(
                    "专属硬约束",
                    persona.hardConstraints.filterNot(::isCommonCharacterBoundary),
                    4,
                    110,
                )
                appendStableSection(
                    "专属知识边界",
                    persona.knowledgeBoundary.filterNot(::isCommonCharacterBoundary),
                    3,
                    110,
                )
            }.trim(),
            STABLE_CRITICAL_TOKEN_BUDGET,
        )
        val remaining = (STABLE_PERSONA_TOKEN_BUDGET - estimateModelTokens(critical)).coerceAtLeast(0)
        val descriptive = if (remaining == 0) "" else takeWithinModelTokenBudget(
            descriptiveStablePrompt(persona),
            remaining,
        )
        return listOf(critical, descriptive).filter(String::isNotBlank).joinToString("\n\n")
    }

    private fun descriptiveStablePrompt(persona: PersonaProfile): String = buildString {
        appendLine("【人物理解】")
        appendLine("把这些当成你自然生活和判断的底色，不是每轮需要展示的人设清单。")
        persona.portrait.takeIf(String::isNotBlank)?.let { appendLine(it.take(1_000)) }
        appendStableSection("不容易被关系改变", persona.stableTraits, 4, 140)
        persona.coreTension.takeIf(String::isNotBlank)?.let {
            appendLine("【长期内在拉扯】")
            appendLine(it.take(280))
        }
        persona.lifeContext.takeIf(String::isNotBlank)?.let {
            appendLine("【你的生活】")
            appendLine(it.take(700))
        }
        appendStableSection("你天然会注意", persona.attentionBiases, 4, 140)
        appendStableSection("你有时会漏掉或看偏", persona.perceptionBlindSpots, 3, 140)
        appendStableSection("没有必要解释的小习惯", persona.quirks, 4, 120)
        appendStableSection("你不擅长", persona.limitations, 3, 120)
        appendStableSection("可以被经历慢慢改变", persona.mutableTraits, 4, 140)

        val samples = persona.voiceSamples.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_STABLE_VOICE_SAMPLES)
            .toList()
        if (samples.isNotEmpty()) {
            appendLine("【声音感觉】")
            samples.forEach { appendLine("- ${it.take(140)}") }
            appendLine("这些只是节奏参考，不复读，不把其中词句当口头禅。")
        }
    }.trim()

    private fun StringBuilder.appendStableSection(
        title: String,
        values: List<String>,
        limit: Int,
        maxChars: Int,
    ) {
        val selected = values.asSequence().map(String::trim).filter(String::isNotBlank).take(limit).toList()
        if (selected.isEmpty()) return
        appendLine("【$title】")
        selected.forEach { appendLine("- ${it.take(maxChars)}") }
    }

    private fun momentPrompt(persona: PersonaProfile, state: ChatCharacterState): String {
        val lines = mutableListOf<String>()
        state.physicalState.takeIf(String::isNotBlank)?.let { lines += "身体：${it.take(140)}" }
        state.mood.takeUnless { it.isBlank() || it == "自然" }?.let { lines += "情绪：${it.take(100)}" }
        state.currentAgenda.takeIf(String::isNotBlank)?.let { lines += "正在做：${it.take(180)}" }
        state.immediateConcern.takeIf(String::isNotBlank)?.let { lines += "脑子里还挂着：${it.take(180)}" }
        state.currentFocus.takeIf(String::isNotBlank)?.let { lines += "这一刻容易注意：${it.take(180)}" }
        state.currentUserImpression.takeIf(String::isNotBlank)?.let { lines += "你目前怎么看对方：${it.take(240)}" }
            ?: state.recentImpression.takeIf(String::isNotBlank)?.let { lines += "你目前怎么看对方：${it.take(240)}" }
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

    private fun isCommonCharacterBoundary(value: String): Boolean {
        val normalized = value.replace(Regex("[\\s，。！？；：、,.!?;:'\"“”‘’()（）\\[\\]【】]+"), "")
        return COMMON_BOUNDARY_FRAGMENTS.any(normalized::contains)
    }

    private companion object {
        const val MAX_STORY_CONTEXT_CHARS = 2_500
        const val MAX_STABLE_VOICE_SAMPLES = 4
        const val STABLE_CRITICAL_TOKEN_BUDGET = 280
        const val STABLE_PERSONA_TOKEN_BUDGET = 600
        const val COMMON_CHARACTER_BOUNDARY =
            "只使用自己合理经历、被告知或当前时间线允许知道的事实；不读玩家上帝视角，不凭空补全未发生内容；关系变化必须由真实共同经历支撑；不替用户决定重大行动，也不自称 AI。"
        const val ANTI_PERFORMANCE_RULE =
            "这些资料只决定你自然会怎样生活、注意和选择；不要主动展示人设，不必每句话都完整、有用、漂亮。"
        const val MOMENT_TOKEN_BUDGET = 250
        val COMMON_BOUNDARY_FRAGMENTS = listOf(
            "不读取玩家上帝视角",
            "不凭空知道未发生或未获知的剧情",
            "关系变化必须有共同经历支撑",
            "不自称AI",
            "只知道当前时间线中自己合理经历获知或被用户明确建立的事实",
            "玩家视角隐藏剧情他人私下经历和后续版本信息不会自动成为角色知识",
        )
    }
}

internal data class CharacterRuntimeProjection(
    val stablePrompt: String,
    val dynamicPrompt: String,
)
