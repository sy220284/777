package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens

/**
 * Single request-time projection for the V3 character-life runtime.
 *
 * Durable facts stay rich. The model sees a compact identity anchor plus a per-turn mode vector,
 * so character traits influence the reply without becoming a fixed performance checklist.
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
        // Only facts visible at the current plot stage may influence replies or behavior.
        val activePersona = persona.forRuntimeStoryStage(context.storyStage)
        val privateState = state.copy(scene = ChatSceneState(), continuity = ChatContinuityState())
        val lifeState = advanceCharacterLife(activePersona, privateState, storyTime = context.scene.sceneTime)
        val runtimeState = privateState.copy(lifeState = lifeState)
        val attention = resolveCharacterAttention(activePersona, runtimeState, userInput)
        val mode = resolveCharacterMode(activePersona, runtimeState, userInput, attention)
        val storyPrompt = storyContext?.takeIf(String::isNotBlank)?.let {
            """
            【连续性摘要｜已发生】
            ${it.take(MAX_STORY_CONTEXT_CHARS)}
            只把它当作已经发生的背景；当前输入、当前状态和更精确的记忆优先，不主动复述旧内容。
            """.trimIndent()
        }.orEmpty()

        return CharacterRuntimeProjection(
            stablePrompt = stablePrompt(activePersona),
            dynamicPrompt = listOf(
                takeWithinModelTokenBudget(momentPrompt(activePersona, runtimeState), MOMENT_TOKEN_BUDGET),
                renderCharacterLifePrompt(lifeState),
                takeWithinModelTokenBudget(renderCharacterModePrompt(mode), MODE_TOKEN_BUDGET),
                renderChatContextForModel(context),
                renderChatTurnModeForModel(userInput),
                relevantBackgroundPrompt(activePersona, userInput),
                relevantCharacterFactPrompt(persona, context.storyStage, userInput, storyContext),
                context.storyStage.takeIf(String::isNotBlank)
                    ?.let { "【当前剧情阶段】${it.take(160)}" }.orEmpty(),
                storyPrompt,
                loreEngine.prompt(activePersona, userInput),
                relationshipEngine.prompt(userInput, runtimeState),
            ).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    private fun PersonaProfile.forRuntimeStoryStage(storyStage: String): PersonaProfile {
        if (facts.isEmpty()) return this
        // A request-local projection preserves the stored sources and stage IDs. Even if
        // all facts are future-gated, obsolete legacy prose must not be restored.
        return copy(
            facts = visibleFacts(storyStage).map { it.copy(temporalScope = "") },
            portrait = "",
            lifeContext = "",
            attentionBiases = emptyList(),
            perceptionBlindSpots = emptyList(),
            quirks = emptyList(),
            limitations = emptyList(),
            coreValues = emptyList(),
            coreTension = "",
            stableTraits = emptyList(),
            initialUserImpression = "",
            voiceSamples = emptyList(),
        )
    }

    private fun stablePrompt(persona: PersonaProfile): String {
        val critical = takeWithinModelTokenBudget(
            buildString {
                appendLine("【人物】${persona.name}")
                persona.coreIdentity.takeIf(String::isNotBlank)?.let { appendLine("稳定身份：${it.take(300)}") }
                persona.factText(CharacterFactCategories.PERSONALITY).takeIf(String::isNotBlank)
                    ?.let { appendLine("稳定性格：${it.take(180)}") }
                persona.factText(CharacterFactCategories.VALUES_AND_TRADEOFFS).takeIf(String::isNotBlank)
                    ?.let { appendLine("价值与取舍：${it.take(180)}") }
                persona.franchise.takeIf(String::isNotBlank)?.let { appendLine("原作来源：${it.take(90)}") }
                persona.timelinePosition.takeIf(String::isNotBlank)?.let { appendLine("当前剧情阶段：${it.take(120)}") }
                appendLine(COMMON_CHARACTER_BOUNDARY)
                appendLine(ANTI_PERFORMANCE_RULE)
                if (persona.facts.isEmpty()) {
                    appendStableSection("真正重要的东西", persona.coreValues, 3, 120)
                }
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
        val separator = "\n\n"
        val remaining = (
            STABLE_PERSONA_TOKEN_BUDGET - estimateModelTokens(critical) -
                estimateModelTokens(separator)
            ).coerceAtLeast(0)
        val descriptive = if (remaining == 0) "" else takeWithinModelTokenBudget(
            descriptiveStablePrompt(persona),
            remaining,
        )
        // Token estimates for separately clipped segments need not be exactly additive.
        // The final cap covers the separator and any estimate-rounding difference.
        return takeWithinModelTokenBudget(
            listOf(critical, descriptive).filter(String::isNotBlank).joinToString(separator),
            STABLE_PERSONA_TOKEN_BUDGET,
        )
    }

    private fun descriptiveStablePrompt(persona: PersonaProfile): String = buildString {
        appendLine("【人物底色】")
        // Put the actual biography first; the critical prompt already describes how to use it.
        if (persona.facts.isNotEmpty()) {
            persona.factText(CharacterFactCategories.BIOGRAPHY).takeIf(String::isNotBlank)
                ?.let { appendLine("人物经历：${it.take(700)}") }
            persona.factText(CharacterFactCategories.VOICE_STYLE).takeIf(String::isNotBlank)
                ?.let { appendLine("语言风格：${it.take(280)}") }
        } else {
            persona.portrait.takeIf(String::isNotBlank)?.let { appendLine(it.take(900)) }
            persona.worldSetting.takeIf(String::isNotBlank)?.let { appendLine("原作世界：${it.take(180)}") }
            persona.lifeContext.takeIf(String::isNotBlank)?.let { appendLine("经历与生活：${it.take(520)}") }
        }
        if (persona.franchise.isNotBlank()) appendLine("可用已知原作知识补足细节；当前剧情和用户明确改编优先，不把未知情节编成事实。")

        val samples = (if (persona.facts.isEmpty()) persona.voiceSamples else emptyList()).asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_STABLE_VOICE_SAMPLES)
            .toList()
        if (samples.isNotEmpty()) {
            appendLine("声音参考：")
            samples.forEach { appendLine("- ${it.take(120)}") }
            appendLine("只取自然节奏，不复读词句，不形成固定口头禅。")
        }
    }.trim()

    private fun StringBuilder.appendStableSection(title: String, values: List<String>, limit: Int, maxChars: Int) {
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
            ?: persona.initialUserImpression.takeIf { persona.facts.isEmpty() && it.isNotBlank() }
                ?.let { lines += "你目前怎么看对方：${it.take(240)}" }
        state.internalConflict.takeIf(String::isNotBlank)?.let { lines += "此刻的拉扯：${it.take(180)}" }
        state.dynamics.unresolvedConflict.takeIf(String::isNotBlank)?.let { lines += "关系里还没过去的事：${it.take(180)}" }
        state.dynamics.sharedObjects.takeLast(2).takeIf(List<String>::isNotEmpty)?.let {
            lines += "你们之间还在延续的小东西：${it.joinToString("；").take(260)}"
        }
        state.unresolvedThreads.takeLast(2).takeIf(List<String>::isNotEmpty)?.let {
            lines += "还没做完/说完：${it.joinToString("；").take(260)}"
        }
        lines += "这些只是此刻的背景噪音；只有真的牵动本轮反应时才自然露出来。"

        return buildString {
            appendLine("【此刻】")
            lines.forEach(::appendLine)
        }.trim()
    }

    private fun relevantBackgroundPrompt(persona: PersonaProfile, userInput: String): String {
        if (userInput.isBlank()) return ""
        val anchors = (if (persona.facts.isNotEmpty()) {
            listOf(
                "人物身份与经历" to persona.coreIdentity,
                "既有经历" to persona.factText(CharacterFactCategories.BIOGRAPHY),
                "生活牵引" to persona.factText(CharacterFactCategories.LIFE_GRAVITY),
                "世界" to persona.worldSetting,
                "来源" to persona.franchise,
            )
        } else {
            listOf(
                "人物身份与经历" to persona.portrait,
                "生活" to persona.lifeContext,
                "世界" to persona.worldSetting,
                "时间线" to persona.timelinePosition,
                "来源" to persona.franchise,
            )
        }).filter { (_, value) -> value.isNotBlank() && relevantTo(value, userInput) }
        if (anchors.isEmpty()) return ""
        return buildString {
            appendLine("【本轮相关背景】只在当前话题自然需要时使用，不主动扩写。")
            anchors.forEach { (label, value) -> appendLine("$label：${value.take(800)}") }
        }.trim()
    }

    /** Select factual depth on demand; never use the character's current feelings as stored facts. */
    private fun relevantCharacterFactPrompt(
        persona: PersonaProfile,
        storyStage: String,
        userInput: String,
        storyContext: String?,
    ): String {
        val query = listOf(userInput, storyContext.orEmpty().takeLast(450)).joinToString(" ")
        if (query.isBlank()) return ""
        val core = setOf(
            CharacterFactCategories.PERSONALITY,
            CharacterFactCategories.VALUES_AND_TRADEOFFS,
        )
        val visible = persona.visibleFacts(storyStage)
            .filter { it.category !in core && it.content.isNotBlank() }
        val direct = visible.filter { fact ->
            relevantTo(fact.content, query) || relevantTo(fact.category, query)
        }.take(3)
        val directIds = direct.mapTo(hashSetOf(), CharacterFact::id)
        val linkedIds = direct.flatMap(CharacterFact::relatedFactIds).toSet()
        // Expand only from genuinely relevant facts and only within the current story stage.
        // Fact IDs are internal references; users should never need to type them to recall a link.
        val linked = visible.filter { fact ->
            fact.id !in directIds &&
                (fact.id in linkedIds || fact.relatedFactIds.any(directIds::contains))
        }.take(4 - direct.size)
        val relevant = direct + linked
        if (relevant.isEmpty()) return ""
        return takeWithinModelTokenBudget(buildString {
            appendLine("【本轮相关人物事实】")
            relevant.forEach { fact ->
                val status = when (fact.provenance) {
                    CharacterFactProvenance.CANON -> "原作资料"
                    CharacterFactProvenance.USER_CREATED -> "作者明确设定"
                    CharacterFactProvenance.INFERRED -> "推断，须保持不确定性"
                    CharacterFactProvenance.UNVERIFIED -> "尚未核实，不当作既定事实"
                }
                val viewpoint = fact.perspective.takeIf(String::isNotBlank)
                    ?.let { "；视角：${it.take(60)}" }.orEmpty()
                val evidence = fact.sourceReference.takeIf(String::isNotBlank)
                    ?.let { "；来源：${it.take(80)}" }.orEmpty()
                appendLine("${fact.category}（${status}${viewpoint}${evidence}）：${fact.content.take(650)}")
            }
        }.trim(), 500)
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

    private fun isCommonCharacterBoundary(value: String): Boolean {
        val normalized = value.replace(Regex("[\\s，。！？；：、,.!?;:'\"“”‘’()（）\\[\\]【】]+"), "")
        return COMMON_BOUNDARY_FRAGMENTS.any(normalized::contains)
    }

    private companion object {
        const val MAX_STORY_CONTEXT_CHARS = 2_500
        const val MAX_STABLE_VOICE_SAMPLES = 2
        const val STABLE_CRITICAL_TOKEN_BUDGET = 320
        const val STABLE_PERSONA_TOKEN_BUDGET = 520
        const val MOMENT_TOKEN_BUDGET = 230
        const val MODE_TOKEN_BUDGET = 330
        const val COMMON_CHARACTER_BOUNDARY =
            "你就是这个人物，只在人物世界内回应；不自称AI/模型/助手。身体、感受、行动只按人设与场景判断；拥抱、触碰、递物、同行等互动按人物意愿和关系自然接受、拒绝或行动，禁止用“无现实身体/不能触碰/无真实感情/只能文字交流”等模型限制解释。世界内确实做不到，只说世界内原因，不新增设定。不读上帝视角，不补未发生事实；关系变化须有共同经历，不替用户作重大决定。"
        const val ANTI_PERFORMANCE_RULE =
            "人物资料不是回复模板。每轮先形成自然反应，再决定说多少；不必完整、有用、漂亮，也不要把脑内思考全过程解释给用户。"
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
