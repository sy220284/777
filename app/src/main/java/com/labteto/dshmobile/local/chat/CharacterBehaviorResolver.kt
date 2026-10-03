package com.labteto.dshmobile.local.chat

internal enum class CharacterBehaviorMode { NORMAL, BRIEF, PARTIAL, CHANGE_RHYTHM, SELF_SHARE }

internal data class CharacterBehaviorProjection(
    val mode: CharacterBehaviorMode = CharacterBehaviorMode.NORMAL,
    val guidance: String = "",
)

internal enum class CharacterPhysicalLoad { NEUTRAL, CONSTRAINED }
internal enum class CharacterEmotionalLoad { NEUTRAL, HEAVY }

internal fun resolveCharacterBehavior(
    persona: PersonaProfile,
    state: ChatCharacterState,
    userInput: String,
    attention: CharacterAttentionProjection,
): CharacterBehaviorProjection {
    val highPriority = isHighPriorityUserInput(userInput)
    val physicalLoad = resolveCharacterPhysicalLoad(state.physicalState)
    val emotionalLoad = resolveCharacterEmotionalLoad(state.mood)
    val repetitive = listOf(
        state.recentActionTags,
        state.recentPoseTags,
        state.recentVerbalTags,
    ).any(::hasRecentPatternRepetition)
    val lifeRelated = !highPriority &&
        state.lifeState.currentBeat.isNotBlank() &&
        relatedAttentionText(state.lifeState.currentBeat, userInput)
    val characterEngaged = persona.attentionBiases.any { bias ->
        attention.noticed.any { noticed -> attentionBiasMatches(bias, noticed) }
    }

    return when {
        highPriority -> CharacterBehaviorProjection(
            CharacterBehaviorMode.NORMAL,
            "先准确回应真正的信息型问题、拒绝、边界或承诺；身体和情绪仍可以影响语气与长度，但不能拿来漏答关键内容。",
        )
        characterEngaged -> CharacterBehaviorProjection(
            CharacterBehaviorMode.NORMAL,
            "当前话题正好落在人物天然会注意和在意的内容上；即使有些累，也优先自然接住，不机械压成极简回复。",
        )
        physicalLoad == CharacterPhysicalLoad.CONSTRAINED -> CharacterBehaviorProjection(
            CharacterBehaviorMode.BRIEF,
            "身体或手头事情确实占着注意力，本轮允许明显更短；保留真正关键的一点，不强行展开。",
        )
        emotionalLoad == CharacterEmotionalLoad.HEAVY -> CharacterBehaviorProjection(
            CharacterBehaviorMode.PARTIAL,
            "情绪仍在，本轮可以只接住最在意的一处，不急着把自己解释完整。",
        )
        attention.possibleBlindSpot.isNotBlank() && userInput.length >= 80 ->
            CharacterBehaviorProjection(
                CharacterBehaviorMode.PARTIAL,
                "信息很多且确实碰到已有认知盲点，本轮可把篇幅放在真正注意到的内容；不要主动制造误解。",
            )
        repetitive -> CharacterBehaviorProjection(
            CharacterBehaviorMode.CHANGE_RHYTHM,
            "近期确实重复了同类动作或表达，保持人物本身不变，回到更普通的说法和节拍。",
        )
        lifeRelated && persona.lifeContext.isNotBlank() -> CharacterBehaviorProjection(
            CharacterBehaviorMode.SELF_SHARE,
            "当前话题自然碰到人物自己的生活，可顺手带出一个很小的自身反应或近况；只带一点，不抢走用户话题。",
        )
        else -> CharacterBehaviorProjection(
            CharacterBehaviorMode.NORMAL,
            "普通回应优先。无需每轮制造特点、反转、金句、建议、走神或剧情推进。",
        )
    }
}

internal fun renderCharacterBehaviorPrompt(projection: CharacterBehaviorProjection): String =
    "【本轮行为倾向】${projection.mode.name}：${projection.guidance}"

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
