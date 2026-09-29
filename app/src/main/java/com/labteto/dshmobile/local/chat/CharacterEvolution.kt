package com.labteto.dshmobile.local.chat

import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * Slow-moving character baseline derived from repeated lived interaction.
 *
 * PersonaProfile remains the immutable identity layer; ChatCharacterState keeps immediate state;
 * this record sits between them and captures durable behavioural drift without allowing one reply
 * or one planner hallucination to rewrite the character.
 */
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
)

internal fun evolveCharacterEvolution(
    previous: ChatCharacterState,
    current: ChatCharacterState,
    significance: String,
): CharacterEvolutionState {
    val normalizedSignificance = significance.trim().uppercase()
    if (normalizedSignificance == "NONE") return previous.evolution

    val seeded = if (previous.evolution.observationCount == 0) {
        previous.evolution.copy(
            initiativeBaseline = previous.initiative.coerceIn(0, 100),
            opennessBaseline = previous.shareDesire.coerceIn(0, 100),
            securityBaseline = relationshipSecurity(previous.dynamics),
        )
    } else {
        previous.evolution
    }

    val weight = if (normalizedSignificance == "MAJOR") 2 else 1
    val initiative = evolveAxis(
        baseline = seeded.initiativeBaseline,
        target = current.initiative,
        momentum = seeded.initiativeMomentum,
        weight = weight,
    )
    val openness = evolveAxis(
        baseline = seeded.opennessBaseline,
        target = current.shareDesire,
        momentum = seeded.opennessMomentum,
        weight = weight,
    )
    val security = evolveAxis(
        baseline = seeded.securityBaseline,
        target = relationshipSecurity(current.dynamics),
        momentum = seeded.securityMomentum,
        weight = weight,
    )

    val observationCount = if (seeded.observationCount == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        seeded.observationCount + 1
    }
    val milestone = if (normalizedSignificance == "MAJOR") {
        current.dynamics.sharedMoments.lastOrNull()
            ?.trim()
            ?.take(160)
            ?.takeIf(String::isNotBlank)
            ?: relationshipMilestone(previous, current)
            ?: seeded.lastMajorMilestone
    } else {
        seeded.lastMajorMilestone
    }

    return seeded.copy(
        initiativeBaseline = initiative.baseline,
        opennessBaseline = openness.baseline,
        securityBaseline = security.baseline,
        observationCount = observationCount,
        initiativeMomentum = initiative.momentum,
        opennessMomentum = openness.momentum,
        securityMomentum = security.momentum,
        lastMajorMilestone = milestone,
    )
}

internal fun mergeCharacterEvolution(
    base: CharacterEvolutionState,
    incoming: CharacterEvolutionState,
): CharacterEvolutionState {
    val newer = if (incoming.observationCount >= base.observationCount) incoming else base
    val older = if (newer === incoming) base else incoming
    return newer.copy(
        lastMajorMilestone = newer.lastMajorMilestone.ifBlank { older.lastMajorMilestone },
    )
}

internal fun characterEvolutionSummary(evolution: CharacterEvolutionState): String? {
    if (evolution.observationCount < 2 && evolution.lastMajorMilestone.isBlank()) return null
    return "长期主动=${evolution.initiativeBaseline}/100｜表达开放=${evolution.opennessBaseline}/100｜" +
        "关系安全感=${evolution.securityBaseline}/100｜有效观察=${evolution.observationCount}"
}

internal fun StringBuilder.appendCharacterEvolutionContext(evolution: CharacterEvolutionState) {
    val summary = characterEvolutionSummary(evolution) ?: return
    appendLine("【长期人物演变】$summary")
    evolution.lastMajorMilestone.takeIf(String::isNotBlank)?.let {
        appendLine("最近重大经历：$it")
    }
    appendLine("基础人设、硬约束与用户纠正优先；长期演变调节稳定行为底色，当前状态只负责本轮波动。")
}

private data class EvolutionAxis(
    val baseline: Int,
    val momentum: Int,
)

private fun evolveAxis(
    baseline: Int,
    target: Int,
    momentum: Int,
    weight: Int,
): EvolutionAxis {
    val safeBaseline = baseline.coerceIn(0, 100)
    val safeTarget = target.coerceIn(0, 100)
    val gap = safeTarget - safeBaseline
    if (abs(gap) < EVOLUTION_SIGNAL_GAP) {
        return EvolutionAxis(safeBaseline, decayMomentum(momentum))
    }

    val direction = if (gap > 0) 1 else -1
    val alignedMomentum = if (momentum == 0 || momentum.sign() == direction) momentum else 0
    val nextMomentum = (alignedMomentum + direction * weight)
        .coerceIn(-MAX_EVOLUTION_MOMENTUM, MAX_EVOLUTION_MOMENTUM)
    val threshold = if (weight >= 2) MAJOR_EVOLUTION_THRESHOLD else MINOR_EVOLUTION_THRESHOLD
    if (abs(nextMomentum) < threshold) {
        return EvolutionAxis(safeBaseline, nextMomentum)
    }

    val maxStep = if (weight >= 2) MAJOR_EVOLUTION_STEP else MINOR_EVOLUTION_STEP
    val step = minOf(abs(gap), maxStep)
    return EvolutionAxis(
        baseline = (safeBaseline + direction * step).coerceIn(0, 100),
        momentum = direction,
    )
}

private fun relationshipSecurity(dynamics: RelationshipDynamics): Int =
    ((dynamics.trust.coerceIn(0, 100) +
        dynamics.stability.coerceIn(0, 100) +
        (100 - dynamics.tension.coerceIn(0, 100))) / 3)
        .coerceIn(0, 100)

private fun relationshipMilestone(
    previous: ChatCharacterState,
    current: ChatCharacterState,
): String? = when {
    current.dynamics.stage != previous.dynamics.stage ->
        "关系阶段：${previous.dynamics.stage}→${current.dynamics.stage}"
    current.relationshipState != previous.relationshipState ->
        "关系状态：${previous.relationshipState}→${current.relationshipState}"
    else -> null
}

private fun decayMomentum(value: Int): Int = when {
    value > 0 -> value - 1
    value < 0 -> value + 1
    else -> 0
}

private fun Int.sign(): Int = when {
    this > 0 -> 1
    this < 0 -> -1
    else -> 0
}

private const val EVOLUTION_SIGNAL_GAP = 10
private const val MINOR_EVOLUTION_THRESHOLD = 3
private const val MAJOR_EVOLUTION_THRESHOLD = 2
private const val MINOR_EVOLUTION_STEP = 1
private const val MAJOR_EVOLUTION_STEP = 3
private const val MAX_EVOLUTION_MOMENTUM = 6
