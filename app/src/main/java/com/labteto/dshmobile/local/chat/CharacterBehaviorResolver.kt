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

    val tuning = state.behaviorTuning.normalized()
    association += tuningDelta(tuning.novelty, 16)
    playfulness += tuningDelta(tuning.novelty, 12)
    initiative += tuningDelta(tuning.initiative, 18)
    disclosure += tuningDelta(tuning.openness, 18)
    disclosure += tuningDelta(tuning.intimacy, 8)
    affect += tuningDelta(tuning.intimacy, 6)

    val personaText = modePersonaText(persona)
    if (personaText.containsAnyMode("灵动", "跳脱", "脑洞", "联想", "发散", "天马行空", "古灵精怪", "思路快")) {
        association += 18
    }
    if (personaText.containsAnyMode("理性", "逻辑", "谨慎", "较真", "推理", "分析", "冷静", "认真")) {
        analysis += 16
        repair += 8
    }
    if (personaText.containsAnyMode("感性", "敏感", "心软", "冲动", "共情", "情绪")) affect += 15
    if (personaText.containsAnyMode("细腻", "观察", "敏锐", "细节", "画面", "声音", "气味", "动作")) sensory += 15
    if (personaText.containsAnyMode("察言观色", "言外之意", "看气氛", "懂暗示", "敏感", "细腻")) subtext += 14
    if (personaText.containsAnyMode("幽默", "嘴贫", "爱开玩笑", "俏皮", "调侃", "古灵精怪", "灵动")) playfulness += 18
    if (personaText.containsAnyMode("寡言", "克制", "含蓄", "嘴硬", "别扭", "害羞", "惜字")) {
        compression += 13
        disclosure -= 10
    }
    if (personaText.containsAnyMode("直率", "直球", "坦率", "爽快", "健谈", "话多")) {
        compression -= 10
        disclosure += 10
    }
    if (personaText.containsAnyMode("主动", "爱带话题", "强势", "自来熟")) initiative += 12
    if (personaText.containsAnyMode("被动", "慢热", "戒备", "不主动")) initiative -= 10

    val criticalInput = isHighPriorityUserInput(userInput)
    val physicalLoad = resolveCharacterPhysicalLoad(state.physicalState)
    val emotionalLoad = resolveCharacterEmotionalLoad(state.mood)
    val clauseCount = userInput.split(Regex("[，。！？!?；;\\n]+")).count { it.trim().length >= 2 }
    val lifeRelated = !criticalInput &&
        state.lifeState.currentBeat.isNotBlank() &&
        relatedAttentionText(state.lifeState.currentBeat, userInput)
    val characterEngaged = persona.attentionBiases.any { bias ->
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
    } else {
        if (clauseCount >= 3) {
            association += 8
            coverage -= 14
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
    if (lifeRelated && persona.lifeContext.isNotBlank()) {
        disclosure += 18
        initiative += 8
    }
    if (repeatedRhythm) {
        association += 14
        playfulness += 7
        repair += 8
    }
    if (state.mood.containsAnyMode("开心", "兴奋", "期待", "雀跃")) {
        association += 10
        playfulness += 12
    }
    if (state.currentUserImpression.isNotBlank() || state.recentImpression.isNotBlank()) subtext += 5

    val cues = buildList {
        persona.coreTension.takeIf(String::isNotBlank)?.let(::add)
        addAll(persona.stableTraits)
        addAll(persona.mutableTraits)
        addAll(persona.quirks)
        addAll(persona.limitations)
        addAll(persona.attentionBiases)
        addAll(persona.perceptionBlindSpots)
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
        ).normalized(),
        focus = attention.noticed.take(2),
        relevantPersonaCues = cues,
        corrections = persona.corrections.takeLast(4),
        criticalInput = criticalInput,
        repeatedRhythm = repeatedRhythm,
    )
}

internal fun renderCharacterModePrompt(projection: CharacterModeProjection): String {
    val mode = projection.vector
    return buildString {
        appendLine("【本轮模式】")
        appendLine("这些是本轮脑回路、互动和表达的倾向，不是台词模板，也不是逐项任务；自然选择一两种最合适的表现即可。")
        if (projection.focus.isNotEmpty()) appendLine("注意力落点：${projection.focus.joinToString("；")}")
        if (projection.relevantPersonaCues.isNotEmpty()) {
            appendLine("本轮相关人物底色：${projection.relevantPersonaCues.joinToString("；")}")
        }
        appendLine(
            "思维：联想${modeLabel(mode.association, "收束", "自然", "开放")}｜" +
                "推演${modeLabel(mode.analysis, "直觉先行", "平衡", "较强")}｜" +
                "情绪驱动${modeLabel(mode.affect, "低", "自然", "高")}｜" +
                "感官具体${modeLabel(mode.sensory, "低", "自然", "高")}｜" +
                "言外敏感${modeLabel(mode.subtext, "低", "自然", "高")}",
        )
        appendLine(
            "互动：主动${modeLabel(mode.initiative, "跟随", "自然", "带话题")}｜" +
                "自我分享${modeLabel(mode.disclosure, "保留", "自然", "更开放")}｜" +
                "回应覆盖${modeLabel(mode.coverage, "选择性", "自然", "完整")}",
        )
        appendLine(
            "表达：压缩${modeLabel(mode.compression, "展开", "自然", "高")}｜" +
                "玩心${modeLabel(mode.playfulness, "低", "自然", "高")}｜" +
                "改口/补充${modeLabel(mode.repair, "少", "自然", "容易出现")}",
        )
        if (projection.repeatedRhythm) appendLine("近期节拍有重复：允许自然换一种节奏，但不要为了新鲜而表演反常。")
        if (projection.corrections.isNotEmpty()) {
            appendLine("用户明确纠正（硬约束）：${projection.corrections.joinToString("；")}")
        }
        appendLine(
            "内部思考可以比说出口完整。允许有根据的联想、跳一下话题、回想、改口、补一句、留白或只接一部分；" +
                "这些都必须从当前话题、人物经历、记忆或状态自然牵出，不能随机制造怪癖。",
        )
        appendLine("无需把用户每个信息点都服务式答完，也无需每轮安慰、总结、建议、追问或推进关系。普通回应可以很普通。")
        if (projection.criticalInput) {
            append("本轮含信息问题、明确拒绝/边界、承诺或重要事实：这些内容必须准确接住，模式自由不得造成漏答或误读。")
        } else {
            append("说到人物自然会停的位置就停；不要把脑内推理补成完整说明书。")
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

private fun modePersonaText(persona: PersonaProfile): String = buildString {
    append(persona.portrait); append(' ')
    append(persona.coreTension); append(' ')
    append(persona.stableTraits.joinToString(" ")); append(' ')
    append(persona.mutableTraits.joinToString(" ")); append(' ')
    append(persona.attentionBiases.joinToString(" ")); append(' ')
    append(persona.quirks.joinToString(" ")); append(' ')
    append(persona.limitations.joinToString(" ")); append(' ')
    append(persona.voiceSamples.take(6).joinToString(" ")); append(' ')
    append(persona.corrections.joinToString(" "))
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

private fun String.containsAnyMode(vararg values: String): Boolean =
    values.any { contains(it, ignoreCase = true) }

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
