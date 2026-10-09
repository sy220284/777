package com.labteto.dshmobile.local.chat

import kotlin.math.roundToInt
import com.labteto.dshmobile.local.model.LocalModelPresets
import kotlinx.serialization.Serializable

/**
 * User-facing performance preferences for a roleplay character.
 *
 * They tune expression and pacing only. Relationship facts, shared memories and long-term
 * baselines still come from lived interaction.
 */
@Serializable
data class CharacterBehaviorTuning(
    val intimacy: Int = 50,
    val persistence: Int = 50,
    val initiative: Int = 50,
    val openness: Int = 50,
    val evolution: Int = 50,
    val emotionalAfterglow: Int = 50,
    val novelty: Int = 50,
    val loreAdherence: Int = 50,
    val relationshipPace: Int = 50,
    val lockRelationshipStage: Boolean = false,
    val updatedAt: Long = 0L,
    val expressionVariation: Int = 50,
) {
    fun normalized(): CharacterBehaviorTuning = copy(
        intimacy = intimacy.coerceIn(0, 100),
        persistence = persistence.coerceIn(0, 100),
        initiative = initiative.coerceIn(0, 100),
        openness = openness.coerceIn(0, 100),
        evolution = evolution.coerceIn(0, 100),
        emotionalAfterglow = emotionalAfterglow.coerceIn(0, 100),
        novelty = novelty.coerceIn(0, 100),
        loreAdherence = loreAdherence.coerceIn(0, 100),
        relationshipPace = relationshipPace.coerceIn(0, 100),
        updatedAt = updatedAt.coerceAtLeast(0L),
        expressionVariation = expressionVariation.coerceIn(0, 100),
    )

    fun isNatural(): Boolean =
        intimacy == 50 && persistence == 50 && initiative == 50 && openness == 50 &&
            evolution == 50 && emotionalAfterglow == 50 && novelty == 50 &&
            loreAdherence == 50 && relationshipPace == 50 && !lockRelationshipStage &&
            expressionVariation == 50

    internal fun transientTtl(base: Int): Int =
        scaleAroundNatural(base, persistence, low = 0.6f, high = 2.0f).coerceAtLeast(1)

    internal fun moodTtl(): Int =
        scaleAroundNatural(4, emotionalAfterglow, low = 0.5f, high = 2.5f).coerceIn(1, 10)

    internal fun cooldownTurns(base: Int): Int =
        scaleAroundNatural(base, novelty, low = 0.65f, high = 1.8f).coerceIn(1, 8)

    internal fun relationshipDelta(base: Int): Int =
        scaleAroundNatural(base, relationshipPace, low = 0.55f, high = 1.5f).coerceAtLeast(1)

    internal fun evolutionThresholdOffset(): Int = when {
        evolution <= 20 -> 2
        evolution <= 40 -> 1
        evolution >= 80 -> -1
        else -> 0
    }

    /**
     * The 0..100 character axis spans the selected model's verified official limits.
     * Center (50) uses its conversation-friendly temperature without changing saved sliders.
     * Missing/unsupported model bounds leave its default sampling behavior intact.
     */
    /** Five composer stops share the persona editor's 0..100 persisted value. */
    internal fun composerTemperatureLevel(): Int =
        ((expressionVariation.coerceIn(0, 100) + 12) / 25).coerceIn(0, 4)

    internal fun withComposerTemperatureLevel(level: Int): CharacterBehaviorTuning =
        copy(expressionVariation = level.coerceIn(0, 4) * 25)

    internal fun roleplayTemperature(model: String, baseUrl: String): Double? {
        val range = LocalModelPresets.chatTemperatureRangeFor(model, baseUrl) ?: return null
        // Gemini 3.x official guidance recommends omitting sampling fields at the default.
        if (range.omitAtChatDefault && expressionVariation == 50) return null
        return range.at(expressionVariation)
    }

    internal fun evolutionStepBonus(): Int = when {
        evolution <= 20 -> -1
        evolution >= 85 -> 2
        evolution >= 65 -> 1
        else -> 0
    }

    internal fun signature(): String = listOf(
        intimacy, persistence, initiative, openness, evolution,
        emotionalAfterglow, novelty, loreAdherence, relationshipPace,
        if (lockRelationshipStage) 1 else 0, expressionVariation,
    ).joinToString(",")
}

internal fun mergeCharacterBehaviorTuning(
    base: CharacterBehaviorTuning,
    incoming: CharacterBehaviorTuning,
): CharacterBehaviorTuning {
    val cleanBase = base.normalized()
    val cleanIncoming = incoming.normalized()
    return when {
        cleanIncoming.updatedAt > cleanBase.updatedAt -> cleanIncoming
        cleanBase.updatedAt > cleanIncoming.updatedAt -> cleanBase
        !cleanIncoming.isNatural() -> cleanIncoming
        else -> cleanBase
    }
}

internal fun StringBuilder.appendCharacterBehaviorTuningContext(tuning: CharacterBehaviorTuning) {
    val clean = tuning.normalized()
    if (clean.isNatural()) return
    appendLine("【相处偏好｜用户调节】")
    appendLine(
        "互动距离=" + axisLabel(clean.intimacy, "克制", "自然", "亲密") + "｜" +
            "主动=" + axisLabel(clean.initiative, "等用户推动", "自然", "更主动") + "｜" +
            "表达=" + axisLabel(clean.openness, "含蓄", "自然", "坦率"),
    )
    appendLine(
        "状态延续=" + axisLabel(clean.persistence, "短", "自然", "持久") + "｜" +
            "人物变化=" + axisLabel(clean.evolution, "稳定", "自然", "灵活") + "｜" +
            "情绪余韵=" + axisLabel(clean.emotionalAfterglow, "易平复", "自然", "深刻"),
    )
    if (clean.novelty != 50 || clean.loreAdherence != 50 || clean.relationshipPace != 50) {
        appendLine(
            "互动新鲜度=" + axisLabel(clean.novelty, "保持习惯", "自然", "多变化") + "｜" +
                "原设遵循=" + axisLabel(clean.loreAdherence, "自由演绎", "自然", "严格原设") + "｜" +
                "关系节奏=" + axisLabel(clean.relationshipPace, "慢热", "自然", "升温较快"),
        )
    }
    if (clean.expressionVariation != 50) {
        appendLine(
            "表达变化=" + axisLabel(clean.expressionVariation, "更稳定", "自然", "更丰富") +
                "；只影响措辞与采样变化，不改变人物事实。",
        )
    }
    if (clean.lockRelationshipStage) appendLine("关系阶段已锁定：保持当前阶段，除非用户关闭锁定。")
    appendLine("参数只调节表现和变化速度，不得凭参数改写信任、共同经历或关系事实。")
}

private fun scaleAroundNatural(base: Int, value: Int, low: Float, high: Float): Int {
    val safe = value.coerceIn(0, 100)
    val safeBase = base.coerceAtLeast(0)
    val factor = if (safe <= 50) {
        low + (1f - low) * (safe / 50f)
    } else {
        1f + (high - 1f) * ((safe - 50) / 50f)
    }
    val scaled = safeBase.toDouble() * factor.toDouble()
    return when {
        !scaled.isFinite() || scaled >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        scaled <= 0.0 -> 0
        else -> scaled.roundToInt()
    }
}

private fun axisLabel(value: Int, low: String, middle: String, high: String): String = when {
    value <= 25 -> low
    value >= 75 -> high
    else -> middle
}
