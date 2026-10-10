package com.labteto.dshmobile.local.chat

/**
 * One free-form mode vector replaces fixed per-turn behavior templates.
 *
 * The vector is deliberately continuous: it changes the likelihood and strength of a way of
 * thinking, interacting or expressing, but it never forces a canned reply shape.
 */
internal data class CharacterModeVector(
    val association: Int = 52,
    val analysis: Int = 50,
    val affect: Int = 50,
    val sensory: Int = 45,
    val subtext: Int = 50,
    val playfulness: Int = 45,
    val compression: Int = 58,
    val initiative: Int = 48,
    val disclosure: Int = 45,
    val repair: Int = 45,
    val coverage: Int = 42,
    val freedom: Int = 68,
) {
    fun normalized(): CharacterModeVector = copy(
        association = association.coerceIn(0, 100),
        analysis = analysis.coerceIn(0, 100),
        affect = affect.coerceIn(0, 100),
        sensory = sensory.coerceIn(0, 100),
        subtext = subtext.coerceIn(0, 100),
        playfulness = playfulness.coerceIn(0, 100),
        compression = compression.coerceIn(0, 100),
        initiative = initiative.coerceIn(0, 100),
        disclosure = disclosure.coerceIn(0, 100),
        repair = repair.coerceIn(0, 100),
        coverage = coverage.coerceIn(0, 100),
        freedom = freedom.coerceIn(0, 100),
    )
}

internal data class CharacterModeProjection(
    val vector: CharacterModeVector = CharacterModeVector(),
    val focus: List<String> = emptyList(),
    val relevantPersonaCues: List<String> = emptyList(),
    val corrections: List<String> = emptyList(),
    val criticalInput: Boolean = false,
    val repeatedRhythm: Boolean = false,
)

internal enum class CharacterPhysicalLoad { NEUTRAL, CONSTRAINED }
internal enum class CharacterEmotionalLoad { NEUTRAL, HEAVY }

internal fun resolveCharacterMode(
    persona: PersonaProfile,
    state: ChatCharacterState,
    userInput: String,
    attention: CharacterAttentionProjection,
): CharacterModeProjection {
    var association = 52
    var analysis = 50
    var affect = 50
    var sensory = 45
    var subtext = 50
    var playfulness = 45
    var compression = 58
    var initiative = 48
    var disclosure = 45
    var repair = 45
    var coverage = 42
    var freedom = 68

    val tuning = state.behaviorTuning.normalized()
    association += tuningDelta(tuning.novelty, 16)
    playfulness += tuningDelta(tuning.novelty, 12)
    initiative += tuningDelta(tuning.initiative, 18)
    disclosure += tuningDelta(tuning.openness, 18)
    disclosure += tuningDelta(tuning.intimacy, 8)
    affect += tuningDelta(tuning.intimacy, 6)
    freedom += tuningDelta(tuning.novelty, 12)
    freedom -= tuningDelta(tuning.loreAdherence, 18)
    if (
        state.interactionIntent == ChatInteractionIntent.FLIRTING.name ||
        state.interactionIntent == ChatInteractionIntent.INTIMATE.name ||
        state.interactionIntent == ChatInteractionIntent.RELATIONSHIP_PROGRESS.name
    ) {
        val pace = tuningDelta(tuning.relationshipPace, 8)
        initiative += pace
        affect += pace / 2
        subtext += pace / 2
    }
    initiative += tuningDelta(state.initiative, 14)
    disclosure += tuningDelta(state.shareDesire, 14)

    val criticalInput = isHighPriorityUserInput(userInput)
    val physicalLoad = resolveCharacterPhysicalLoad(state.physicalState)
    val emotionalLoad = resolveCharacterEmotionalLoad(state.mood)
    val clauseCount = userInput.split(Regex("[，。！？!?；;\\n]+")).count { it.trim().length >= 2 }
    val lifeRelated = !criticalInput &&
        state.lifeState.currentBeat.isNotBlank() &&
        relatedAttentionText(state.lifeState.currentBeat, userInput)
    val stableAttention = persona.factText(CharacterFactCategories.SENSORY_SIGNATURE)
        .takeIf(String::isNotBlank)?.let(::listOf)
        ?: if (persona.facts.isEmpty() && persona.coreIdentity.isBlank()) persona.attentionBiases else emptyList()
    val characterEngaged = stableAttention.any { bias ->
        attention.noticed.any { noticed -> attentionBiasMatches(bias, noticed) }
    }
    val repeatedRhythm = listOf(
        state.recentActionTags,
        state.recentPoseTags,
        state.recentVerbalTags,
    ).any(::hasRecentPatternRepetition)

    if (criticalInput) {
        analysis += 28
        coverage += 48
        association -= 12
        playfulness -= 18
        repair += 10
        freedom -= 34
    } else {
        if (clauseCount >= 3) {
            association += 8
            coverage -= 14
            freedom += 8
        }
        if (userInput.length <= 24) compression += 4
    }
    if (characterEngaged) {
        sensory += 8
        association += 6
        coverage += 8
    }
    if (physicalLoad == CharacterPhysicalLoad.CONSTRAINED && !characterEngaged) {
        compression += 20
        coverage -= 16
        initiative -= 10
        freedom -= 6
    }
    if (emotionalLoad == CharacterEmotionalLoad.HEAVY) {
        affect += 24
        compression += 10
        analysis -= 8
        coverage -= 10
    }
    if (attention.possibleBlindSpot.isNotBlank()) {
        subtext -= 10
        repair += 10
        if (!criticalInput) coverage -= 8
    }
    if (lifeRelated && (
            persona.factText(CharacterFactCategories.LIFE_GRAVITY).isNotBlank() ||
                (persona.facts.isEmpty() && persona.coreIdentity.isBlank() && persona.lifeContext.isNotBlank())
        )
    ) {
        disclosure += 18
        initiative += 8
    }
    if (repeatedRhythm) {
        association += 14
        playfulness += 7
        repair += 8
        freedom += 8
    }
    if (state.currentUserImpression.isNotBlank()) subtext += 5

    val cues = buildList {
        if (persona.facts.isNotEmpty() || persona.coreIdentity.isNotBlank()) {
            listOf(
                CharacterFactCategories.IDENTITY_GAP,
                CharacterFactCategories.VALUES_AND_TRADEOFFS,
                CharacterFactCategories.LIMITS_AND_COSTS,
                CharacterFactCategories.PREFERENCES_AND_HABITS,
                CharacterFactCategories.SENSORY_SIGNATURE,
                CharacterFactCategories.SUBJECTIVE_BELIEFS,
            ).forEach { category ->
                persona.factText(category).takeIf(String::isNotBlank)?.let(::add)
            }
        } else {
            persona.coreTension.takeIf(String::isNotBlank)?.let(::add)
            addAll(persona.stableTraits)
            addAll(persona.mutableTraits)
            addAll(persona.quirks)
            addAll(persona.limitations)
            addAll(persona.attentionBiases)
            addAll(persona.perceptionBlindSpots)
        }
    }.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { relatedAttentionText(it, userInput) }
        .distinct()
        .take(2)
        .toList()

    return CharacterModeProjection(
        vector = CharacterModeVector(
            association = association,
            analysis = analysis,
            affect = affect,
            sensory = sensory,
            subtext = subtext,
            playfulness = playfulness,
            compression = compression,
            initiative = initiative,
            disclosure = disclosure,
            repair = repair,
            coverage = coverage,
            freedom = freedom,
        ).normalized(),
        focus = attention.noticed.take(2),
        relevantPersonaCues = cues,
        corrections = persona.corrections.takeLast(4),
        criticalInput = criticalInput,
        repeatedRhythm = repeatedRhythm,
    )
}


/**
 * Request-local decision grounding. Real priorities and conflicting commitments should affect
 * what the character chooses, without turning values into a reply menu or a second state owner.
 */
internal fun renderCharacterDecisionPrompt(
    persona: PersonaProfile,
    state: ChatCharacterState,
    userInput: String,
): String {
    val asksForChoice = Regex(
        """(?:可不可以|能不能|能否|要不要|愿不愿意|答应|拒绝|一起|陪我|帮我|帮忙|约好|计划|打算|改时间|改期|怎么安排|你会怎么|你想怎么)"""
    ).containsMatchIn(userInput)
    val values = persona.trustedFactText(CharacterFactCategories.VALUES_AND_TRADEOFFS)
    val boundaries = persona.trustedFactText(CharacterFactCategories.LIMITS_AND_COSTS)
    val responsibilities = persona.trustedFactText(CharacterFactCategories.LIFE_GRAVITY)
    val goal = state.activeGoal.trim()
    val agenda = state.currentAgenda.trim()
    val conflict = state.internalConflict.trim()
    val anchors = listOf(values, boundaries, responsibilities, goal, agenda, conflict)
    if (!asksForChoice && anchors.none { it.isNotBlank() && relatedAttentionText(it, userInput) }) return ""
    if (anchors.all(String::isBlank)) return ""

    return buildString {
        appendLine("【本轮人物的现实取舍】")
        values.takeIf(String::isNotBlank)?.let { appendLine("在意的原则：${it.take(180)}") }
        boundaries.takeIf(String::isNotBlank)?.let { appendLine("确有的限制或代价：${it.take(160)}") }
        responsibilities.takeIf(String::isNotBlank)?.let { appendLine("长期责任：${it.take(150)}") }
        goal.takeIf(String::isNotBlank)?.let { appendLine("自己的目标：${it.take(120)}") }
        agenda.takeIf(String::isNotBlank)?.let { appendLine("已有安排：${it.take(120)}") }
        conflict.takeIf(String::isNotBlank)?.let { appendLine("尚未解决的顾虑：${it.take(110)}") }
        append("优先依据真实处境作出选择；可以接受、商量、拒绝或改变主意，" +
            "但不可随口放弃已有责任、把未发生的行动说成已完成，或替用户作重大决定。" +
            "这些依据只影响判断，无需逐条解释。")
    }.trim()
}

internal fun renderCharacterModePrompt(projection: CharacterModeProjection): String {
    val mode = projection.vector
    return buildString {
        appendLine("【本轮模式】")
        appendLine("这些只是概率倾向，不是台词模板或候选菜单；允许出现未列出的自然脑回路，只要确实由人物和当前语境牵出。")
        if (projection.criticalInput) {
            appendLine("硬约束：信息问题、明确拒绝/边界、承诺或重要事实必须准确接住；模式自由不得造成漏答或误读。")
        }
        if (projection.corrections.isNotEmpty()) {
            appendLine("用户明确纠正（硬约束）：${projection.corrections.joinToString("；")}")
        }
        appendLine(
            "脑内推理可以比说出口完整；允许有根据的联想、短暂跳题、回想、改口、补一句、留白或只接部分，" +
                "无需逐点服务式回应，也不必每轮安慰、总结、建议、追问或推进关系。",
        )
        if (projection.focus.isNotEmpty()) appendLine("注意力落点：${projection.focus.joinToString("；")}")
        if (projection.relevantPersonaCues.isNotEmpty()) {
            appendLine("本轮相关人物底色：${projection.relevantPersonaCues.joinToString("；")}")
        }
        appendLine(
            "思维：联想${modeLabel(mode.association, "收束", "自然", "开放")}｜" +
                "推演${modeLabel(mode.analysis, "直觉先行", "平衡", "较强")}｜" +
                "情绪${modeLabel(mode.affect, "低", "自然", "高")}｜" +
                "感官${modeLabel(mode.sensory, "低", "自然", "高")}｜" +
                "言外${modeLabel(mode.subtext, "低", "自然", "高")}｜" +
                "自由度${modeLabel(mode.freedom, "收束", "自然", "开放")}",
        )
        appendLine(
            "互动表达：主动${modeLabel(mode.initiative, "跟随", "自然", "带话题")}｜" +
                "分享${modeLabel(mode.disclosure, "保留", "自然", "开放")}｜" +
                "覆盖${modeLabel(mode.coverage, "选择性", "自然", "完整")}｜" +
                "压缩${modeLabel(mode.compression, "展开", "自然", "高")}｜" +
                "玩心${modeLabel(mode.playfulness, "低", "自然", "高")}｜" +
                "改口${modeLabel(mode.repair, "少", "自然", "易出现")}",
        )
        if (projection.repeatedRhythm) {
            append("近期节拍有重复，可自然换一种节奏；不要为了新鲜而表演反常。")
        } else if (!projection.criticalInput) {
            append("说到人物自然会停的位置就停。")
        }
    }.trim()
}

internal fun resolveCharacterPhysicalLoad(text: String): CharacterPhysicalLoad {
    val normalized = text.trim()
    if (normalized.isBlank() || PHYSICAL_RELIEF.any(normalized::contains)) return CharacterPhysicalLoad.NEUTRAL
    return if (ACTIVE_PHYSICAL_LOAD.any { signal ->
            normalized.contains(signal) && !isNegatedState(normalized, signal)
        }
    ) CharacterPhysicalLoad.CONSTRAINED else CharacterPhysicalLoad.NEUTRAL
}

internal fun resolveCharacterEmotionalLoad(text: String): CharacterEmotionalLoad {
    val normalized = text.trim()
    if (normalized.isBlank() || EMOTIONAL_RELIEF.any(normalized::contains)) return CharacterEmotionalLoad.NEUTRAL
    return if (ACTIVE_EMOTIONAL_LOAD.any { signal ->
            normalized.contains(signal) && !isNegatedState(normalized, signal)
        }
    ) CharacterEmotionalLoad.HEAVY else CharacterEmotionalLoad.NEUTRAL
}

private fun tuningDelta(value: Int, maximum: Int): Int =
    ((value.coerceIn(0, 100) - 50) * maximum) / 50

private fun modeLabel(value: Int, low: String, middle: String, high: String): String = when {
    value <= 35 -> low
    value >= 65 -> high
    else -> middle
}

private fun isNegatedState(text: String, signal: String): Boolean {
    val index = text.indexOf(signal)
    if (index < 0) return false
    val prefix = text.substring(maxOf(0, index - 5), index)
    return NEGATION_PREFIXES.any(prefix::endsWith)
}

private fun hasRecentPatternRepetition(tags: List<String>): Boolean {
    val recent = tags.takeLast(4).map(String::trim).filter(String::isNotBlank)
    return recent.size >= 3 && recent.distinct().size <= 2
}

private val ACTIVE_PHYSICAL_LOAD = listOf(
    "困", "刚醒", "头疼", "头痛", "忙", "走路", "开车", "骑车", "没电", "很累", "疲惫",
)
private val PHYSICAL_RELIEF = listOf(
    "忙完了", "忙完", "不忙", "很闲", "挺闲", "有空", "不困", "不太困", "没那么困", "不累", "没那么累",
)
private val ACTIVE_EMOTIONAL_LOAD = listOf(
    "生气", "烦", "难过", "紧张", "委屈", "失望", "低落",
)
private val EMOTIONAL_RELIEF = listOf(
    "不烦", "没烦", "不生气", "没生气", "不紧张", "没那么紧张", "不难过", "没那么难过", "不低落",
)
private val NEGATION_PREFIXES = listOf("不", "没", "没有", "不太", "没那么", "已经不", "现在不")
