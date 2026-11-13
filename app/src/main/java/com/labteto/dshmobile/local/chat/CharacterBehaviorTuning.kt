package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import kotlinx.serialization.Serializable

/**
 * Sampling choice plus decode-compatible fields from the retired behavior controller.
 * Legacy behavior axes are retained for old saved cards, but no longer direct generation,
 * state expiry, relationships or growth. Only sampling settings have an active UI.
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
    /** Sampling override shared by Chat composer and persona editor; null migrates legacy values. */
    val samplingTemperaturePosition: Int? = null,
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
        samplingTemperaturePosition = samplingTemperaturePosition?.coerceIn(0, 100),
    )

    fun isNatural(): Boolean = samplingTemperaturePosition == null && expressionVariation == 50

    /**
     * The temperature is separate from the character's expressive personality axis.
     * Legacy values migrate through the route's old mapping only when no new choice exists.
     * DeepSeek's old midpoint (1.3) becomes the new maximum without a dead slider region.
     */
    internal fun temperaturePosition(range: com.labteto.dshmobile.local.model.LocalModelTemperatureRange): Int =
        samplingTemperaturePosition?.coerceIn(0, 100)
            ?: range.legacyPosition(expressionVariation)

    /** The five-stop composer and the persona editor use exactly one stored temperature. */
    internal fun composerTemperatureLevel(
        range: com.labteto.dshmobile.local.model.LocalModelTemperatureRange? = null,
    ): Int {
        val position = if (range == null) {
            samplingTemperaturePosition ?: expressionVariation
        } else temperaturePosition(range)
        return ((position.coerceIn(0, 100) + 12) / 25).coerceIn(0, 4)
    }

    internal fun withComposerTemperatureLevel(level: Int): CharacterBehaviorTuning =
        copy(samplingTemperaturePosition = level.coerceIn(0, 4) * 25)

    internal fun roleplayTemperature(model: String, baseUrl: String): Double? {
        val range = LocalEffectiveModelContracts.resolve(model, baseUrl).temperature ?: return null
        val position = temperaturePosition(range)
        if (range.omitAtChatDefault && position == range.defaultPosition) return null
        return range.at(position)
    }

    internal fun signature(): String =
        "sampling:${samplingTemperaturePosition?.toString() ?: "legacy:$expressionVariation"}"

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
