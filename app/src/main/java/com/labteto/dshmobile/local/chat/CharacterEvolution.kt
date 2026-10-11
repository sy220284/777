package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

@Serializable
data class CharacterTraitEvolutionState(
    val baseline: Int = 50,
    val currentWeight: Int = 50,
    val evidenceCount: Int = 0,
    val momentum: Int = 0,
    val counterEvidence: Int = 0,
    val recentEvidenceKeys: List<String> = emptyList(),
    val lastChangedAt: Long = 0L,
)

@Serializable
data class CharacterEvolutionState(
    val initiativeBaseline: Int = 50,
    val opennessBaseline: Int = 50,
    val securityBaseline: Int = 50,
    val observationCount: Int = 0,
    val initiativeMomentum: Int = 0,
    val opennessMomentum: Int = 0,
    val securityMomentum: Int = 0,
    val lastMajorMilestone: String = "",
    val traitStates: Map<String, CharacterTraitEvolutionState> = emptyMap(),
)

internal fun evolveCharacterEvolution(
    previous: ChatCharacterState,
    current: ChatCharacterState,
    significance: String,
): CharacterEvolutionState = evolveCharacterEvolution(
    persona = PersonaProfile(),
    previous = previous,
    current = current,
    significance = significance,
    userMessage = "",
    assistantMessage = "",
)

internal fun evolveCharacterEvolution(
    persona: PersonaProfile,
    previous: ChatCharacterState,
    current: ChatCharacterState,
    significance: String,
    userMessage: String,
    assistantMessage: String,
): CharacterEvolutionState {
    if (significance.trim().uppercase() != "MAJOR") return previous.evolution
    val newMoment = current.dynamics.sharedMoments.asReversed()
        .firstOrNull { it !in previous.dynamics.sharedMoments }
        ?.trim()?.take(180)?.takeIf(String::isNotBlank)
        ?: return previous.evolution
    // Preserve old serialized axes for compatibility. New observations record lived events,
    // without converting them into weights that steer the character's next reaction.
    return previous.evolution.copy(
        observationCount = if (previous.evolution.observationCount == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else previous.evolution.observationCount + 1,
        lastMajorMilestone = newMoment,
    )
}

internal fun mergeCharacterEvolution(
    base: CharacterEvolutionState,
    incoming: CharacterEvolutionState,
): CharacterEvolutionState {
    val newer = if (incoming.observationCount >= base.observationCount) incoming else base
    val older = if (newer === incoming) base else incoming
    val traits = (older.traitStates.keys + newer.traitStates.keys).associateWith { key ->
        val a = older.traitStates[key]
        val b = newer.traitStates[key]
        when {
            a == null -> b!!
            b == null -> a
            b.evidenceCount >= a.evidenceCount -> b
            else -> a
        }
    }
    return newer.copy(
        lastMajorMilestone = newer.lastMajorMilestone.ifBlank { older.lastMajorMilestone },
        traitStates = traits,
    )
}

internal fun characterEvolutionSummary(evolution: CharacterEvolutionState): String? =
    evolution.lastMajorMilestone.trim().takeIf(String::isNotBlank)
        ?.let { "最近共同经历：$it" }
