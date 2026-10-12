package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens

/**
 * Projects the author's character and the current conversation, without selecting a reaction.
 * Stored character facts remain authoritative; this projection owns no personality of its own.
 */
internal fun PersonaProfile.forRuntimeStoryStage(context: ChatContextState): PersonaProfile {
    // A new V4 identity is authoritative even before the first fact is filled in.
    // Legacy-only profiles still retain their old runtime fallback until converted.
    if (facts.isEmpty() && coreIdentity.isBlank()) return this
    // A request-local projection preserves the stored sources and stage IDs. Even if
    // all facts are future-gated, obsolete legacy prose must not be restored.
    return copy(
        facts = characterKnownFacts(context.storyStage, context.visibleStoryStages())
            .map { it.copy(temporalScope = "") },
        portrait = "",
        lifeContext = "",
        attentionBiases = emptyList(),
        attentionKeywords = emptyList(),
        perceptionBlindSpots = emptyList(),
        quirks = emptyList(),
        limitations = emptyList(),
        coreValues = emptyList(),
        coreTension = "",
        stableTraits = emptyList(),
        mutableTraits = emptyList(),
        initialUserImpression = "",
        voiceSamples = emptyList(),
        // Old prose must not bypass V4 stage-gated facts or world-book disclosure.
        timelinePosition = "",
        worldSetting = "",
    )
}


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
        val activePersona = persona.forRuntimeStoryStage(context)
        val privateState = state.copy(scene = ChatSceneState(), continuity = ChatContinuityState())
        val runtimeState = privateState
        val attention = context.attention
        val relevantRelationshipState = runtimeState.copy(
            dynamics = runtimeState.dynamics.copy(
                unresolvedConflict = runtimeState.dynamics.unresolvedConflict.takeIf {
                    ChatTurnContextPolicy.relevant(userInput, it, attention)
                }.orEmpty(),
                unknowns = runtimeState.dynamics.unknowns.filter {
                    ChatTurnContextPolicy.relevant(userInput, it, attention)
                },
                sharedMoments = runtimeState.dynamics.sharedMoments.filter {
                    ChatTurnContextPolicy.relevant(userInput, it, attention)
                },
                sharedObjects = runtimeState.dynamics.sharedObjects.filter {
                    ChatTurnContextPolicy.relevant(userInput, it, attention)
                },
                facts = runtimeState.dynamics.facts.filter {
                    ChatTurnContextPolicy.relevant(userInput, it.text, attention)
                },
                hypotheses = runtimeState.dynamics.hypotheses.filter {
                    ChatTurnContextPolicy.relevant(userInput, it.text, attention)
                },
            ),
        )
        val selectedStory = ChatTurnContextPolicy.storyLines(storyContext, userInput, attention)
        val storyPrompt = selectedStory.takeIf(String::isNotBlank)?.let {
            "【连续性摘要｜已发生】\n${it.take(MAX_STORY_CONTEXT_CHARS)}"
        }.orEmpty()

        val parts = listOf(
                    ChatContextPart(ChatContextLayer.LIVE, momentPrompt(activePersona, runtimeState, userInput, attention)),
                    ChatContextPart(ChatContextLayer.HISTORICAL, renderCharacterLifePrompt(runtimeState.lifeState)),
                    ChatContextPart(ChatContextLayer.LIVE, renderChatContextForModel(context, focusQuery = userInput)),
                    ChatContextPart(ChatContextLayer.HISTORICAL, relevantBackgroundPrompt(activePersona, userInput)),
                    ChatContextPart(ChatContextLayer.HISTORICAL, relevantCharacterFactPrompt(persona, context, userInput, selectedStory)),
                    ChatContextPart(ChatContextLayer.INSTRUCTION, context.storyStage.takeIf(String::isNotBlank)
                        ?.let { "【当前剧情阶段】${it.take(160)}" }.orEmpty()),
                    ChatContextPart(ChatContextLayer.HISTORICAL, storyPrompt),
                    ChatContextPart(ChatContextLayer.HISTORICAL, loreEngine.prompt(
                        activePersona, userInput, storyStage = context.storyStage,
                        unlockedStages = context.visibleStoryStages(),
                    )),
                    ChatContextPart(ChatContextLayer.HISTORICAL, relationshipEngine.prompt(userInput, relevantRelationshipState)),
                )
        return CharacterRuntimeProjection(
            stablePrompt = stablePrompt(activePersona),
            dynamicPrompt = ChatUnifiedTurnContext.compose(userInput, context, parts),
            dynamicParts = parts,
        )
    }

    private fun stablePrompt(persona: PersonaProfile): String {
        val legacy = persona.facts.isEmpty() && persona.coreIdentity.isBlank()
        val sections = listOf(
            "稳定身份" to persona.coreIdentity.ifBlank { persona.portrait },
            "稳定性格" to persona.trustedFactText(CharacterFactCategories.PERSONALITY)
                .ifBlank { if (legacy) persona.stableTraits.joinToString("；") else "" },
            "价值与取舍" to persona.trustedFactText(CharacterFactCategories.VALUES_AND_TRADEOFFS)
                .ifBlank { if (legacy) persona.coreValues.joinToString("；") else "" },
            "人物自身限制" to persona.hardConstraints.filterNot(::isCommonCharacterBoundary).joinToString("；"),
            "人物所知范围" to persona.knowledgeBoundary.filterNot(::isCommonCharacterBoundary).joinToString("；"),
            "人物修订资料" to persona.corrections.joinToString("；"),
            "作者填写的表达偏好" to persona.bannedPhrases.takeIf(List<String>::isNotEmpty)
                ?.let { "避用表达：${it.joinToString("；")}" }.orEmpty(),
            "语言风格资料" to persona.trustedFactText(CharacterFactCategories.VOICE_STYLE)
                .ifBlank { if (legacy) persona.voiceSamples.joinToString("\n") else "" },
            "自身认知与矛盾" to persona.trustedFactText(CharacterFactCategories.IDENTITY_GAP)
                .ifBlank { if (legacy) persona.coreTension else "" },
            "主观认识" to persona.trustedFactText(CharacterFactCategories.SUBJECTIVE_BELIEFS)
                .ifBlank { if (legacy) persona.perceptionBlindSpots.joinToString("；") else "" },
            "感知特点" to persona.trustedFactText(CharacterFactCategories.SENSORY_SIGNATURE)
                .ifBlank { if (legacy) persona.attentionBiases.joinToString("；") else "" },
            "偏好与习惯" to persona.trustedFactText(CharacterFactCategories.PREFERENCES_AND_HABITS)
                .ifBlank { if (legacy) persona.quirks.joinToString("；") else "" },
            "人物经历" to persona.trustedFactText(CharacterFactCategories.BIOGRAPHY)
                .ifBlank { if (legacy) persona.lifeContext else "" },
            "自我叙述" to persona.trustedFactText(CharacterFactCategories.SELF_NARRATIVE),
            "关键选择" to persona.trustedFactText(CharacterFactCategories.DEFINING_CHOICES),
            "情感经历" to persona.trustedFactText(CharacterFactCategories.EMOTIONAL_IMPRINTS),
            "生活与责任" to persona.trustedFactText(CharacterFactCategories.LIFE_GRAVITY),
            "未竟事项" to persona.trustedFactText(CharacterFactCategories.UNFINISHED_BUSINESS),
            "既有关系" to persona.trustedFactText(CharacterFactCategories.RELATIONSHIPS),
            "局限与代价" to persona.trustedFactText(CharacterFactCategories.LIMITS_AND_COSTS),
            "补充人物资料" to persona.trustedFactText(CharacterFactCategories.CUSTOM_FACTS),
            "原作来源" to persona.franchise,
            "当前剧情阶段" to persona.timelinePosition,
            "世界背景" to persona.worldSetting,
        ).filter { it.second.isNotBlank() }.distinctBy { it.second.trim() }
        val heading = "【人物】${persona.name}\n你是这个人物，身处当前聊天的人设与故事背景中。"
        val complete = listOf(heading) + sections.map { (title, text) -> "【$title】\n${text.trim()}" }
        val full = complete.joinToString("\n\n")
        if (estimateModelTokens(full) <= STABLE_PERSONA_TOKEN_BUDGET) return full

        return packCharacterSections(heading, sections, STABLE_PERSONA_TOKEN_BUDGET)
    }

    private fun momentPrompt(
        persona: PersonaProfile,
        state: ChatCharacterState,
        userInput: String,
        attention: ChatAttentionState,
    ): String {
        val lines = mutableListOf<String>()
        state.physicalState.takeIf(String::isNotBlank)?.let { lines += "身体：${it.take(140)}" }
        state.mood.takeUnless { it.isBlank() || it == "自然" }?.let { lines += "情绪：${it.take(100)}" }

        // Ongoing business survives in the state, but does not become an instruction to
        // resurrect that subject on every turn. The raw dialogue already carries continuity.
        val candidates = listOf(
            "这一刻容易注意" to state.currentFocus,
            "脑子里还挂着" to state.immediateConcern,
            "正在做" to state.currentAgenda,
            "当前目标" to state.activeGoal,
            "此刻的拉扯" to state.internalConflict,
        )
        val selected = mutableListOf<String>()
        candidates.forEach { (label, value) ->
            // "正在收拾桌子" is a live scene action even if the user asks about mood.
            // A future plan ("稍后展示...") is not. Explicit topic shifts suppress both.
            val presentAction = label == "正在做" &&
                Regex("""^(?:正在|正|在)[^，。]{2,}""").containsMatchIn(value.trim())
            val relevant = ChatTurnContextPolicy.relevant(userInput, value, attention) ||
                (presentAction && !ChatTurnContextPolicy.isTopicRedirect(userInput) &&
                    (attention.pausedSubject.isBlank() ||
                        attention.pausedSubject == "*" ||
                        !value.contains(attention.pausedSubject, ignoreCase = true)))
            if (selected.size >= 2 || value.isBlank() || !relevant) return@forEach
            if (selected.none { ChatContextAssembler.semanticallySimilar(it.substringAfter('：'), value) }) {
                selected += "$label：${value.take(180)}"
            }
        }
        lines += selected
        state.currentUserImpression.takeIf(String::isNotBlank)?.let {
            lines += "你目前怎么看对方：${it.take(240)}（人物的主观看法）"
        } ?: persona.initialUserImpression.takeIf { persona.facts.isEmpty() && it.isNotBlank() }
            ?.let { lines += "你目前怎么看对方：${it.take(240)}" }
        val open = state.unresolvedThreads.filter {
            ChatTurnContextPolicy.relevant(userInput, it, attention) &&
                selected.none { selectedLine ->
                    ChatContextAssembler.semanticallySimilar(selectedLine.substringAfter('：'), it)
                }
        }.takeLast(1)
        if (open.isNotEmpty()) lines += "还没做完/说完：${open.joinToString("；").take(180)}"
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("【此刻】")
            val budgets = allocateCharacterContextBudget(lines,
                MOMENT_TOKEN_BUDGET - estimateModelTokens("【此刻】\n") - lines.size)
            lines.forEachIndexed { index, line ->
                appendLine(takeWithinModelTokenBudget(line, budgets[index]))
            }
        }.trim()
    }

    private fun relevantBackgroundPrompt(persona: PersonaProfile, userInput: String): String {
        if (userInput.isBlank()) return ""
        val anchors = (if (persona.facts.isNotEmpty()) {
            listOf(
                "人物身份与经历" to persona.coreIdentity,
                "既有经历" to persona.trustedFactText(CharacterFactCategories.BIOGRAPHY),
                "生活牵引" to persona.trustedFactText(CharacterFactCategories.LIFE_GRAVITY),
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
            appendLine("【本轮相关背景】")
            anchors.forEach { (label, value) -> appendLine("$label：${value.take(800)}") }
        }.trim()
    }

    /** Select factual depth on demand; never use the character's current feelings as stored facts. */
    private fun relevantCharacterFactPrompt(
        persona: PersonaProfile,
        context: ChatContextState,
        userInput: String,
        storyContext: String?,
    ): String {
        val query = listOf(ChatMemorySelector.continuityQuery(userInput, context),
            ChatTurnContextPolicy.storyLines(storyContext, userInput, context.attention))
            .filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return ""
        val core = setOf(
            CharacterFactCategories.PERSONALITY,
            CharacterFactCategories.VALUES_AND_TRADEOFFS,
        )
        val visible = persona.characterKnownFacts(context.storyStage, context.visibleStoryStages())
            .filter { it.content.isNotBlank() &&
                (it.category !in core || it.provenance == CharacterFactProvenance.INFERRED ||
                    it.provenance == CharacterFactProvenance.UNVERIFIED) }
        val direct = visible.filter { fact ->
            relevantTo(fact.content, query) || relevantTo(fact.category, query)
        }
        val directIds = direct.mapTo(hashSetOf(), CharacterFact::id)
        val linkedIds = direct.flatMap(CharacterFact::relatedFactIds).toSet()
        // Expand only from genuinely relevant facts and only within the current story stage.
        // Fact IDs are internal references; users should never need to type them to recall a link.
        val linked = visible.filter { fact ->
            fact.id !in directIds && (
                fact.id in linkedIds ||
                    fact.relatedFactIds.any(directIds::contains) ||
                    sharesRelevantTopicWithKnownFact(fact, direct, query)
            )
        }
        val relevant = (direct.take(1) + linked + direct.drop(1)).distinctBy(CharacterFact::id)
        if (relevant.isEmpty()) return ""
        val facts = relevant.map { fact ->
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
                "${fact.category}（${status}${viewpoint}${evidence}）：${fact.content}"
        }
        val selected = packCompleteCharacterUnits(facts, 500 - estimateModelTokens("【本轮相关人物事实】\n"))
        return if (selected.isEmpty()) "" else "【本轮相关人物事实】\n${selected.joinToString("\n")}"
    }

    /**
     * A single name/topic anchor can connect a directly matched event with this
     * character's subjective impression of that same person. Do not expand an
     * unrelated fact merely because it shares generic dialogue words.
     */
    private fun sharesRelevantTopicWithKnownFact(
        candidate: CharacterFact,
        matched: List<CharacterFact>,
        query: String,
    ): Boolean {
        if (candidate.category != CharacterFactCategories.SUBJECTIVE_BELIEFS ||
            matched.isEmpty()
        ) return false
        val queryPairs = normalize(query).windowed(2).toSet()
        val factPairs = normalize(candidate.content).windowed(2).toSet()
        return matched.any { related ->
            normalize(related.content).windowed(2).any { pair ->
                pair in queryPairs && pair in factPairs &&
                    pair !in GENERIC_LINKED_FACT_PAIRS
            }
        }
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
        val GENERIC_LINKED_FACT_PAIRS = setOf(
            "我们", "他们", "自己", "一起", "那个", "这个", "事情", "时候",
            "知道", "关于", "什么", "可以", "认为", "感觉", "觉得",
        )
        const val MAX_STORY_CONTEXT_CHARS = 2_500
        // Previously 520 identity + 330 shared mode + 190 shared decision tokens.
        const val STABLE_PERSONA_TOKEN_BUDGET = 1_040
        const val MOMENT_TOKEN_BUDGET = 230
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
    val dynamicParts: List<ChatContextPart> = emptyList(),
)
