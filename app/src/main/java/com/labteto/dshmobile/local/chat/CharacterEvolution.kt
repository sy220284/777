package com.labteto.dshmobile.local.chat

import kotlin.math.abs
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
    val normalizedSignificance = significance.trim().uppercase()
    if (normalizedSignificance == "NONE") return previous.evolution

    val seeded = previous.evolution
    val weight = if (normalizedSignificance == "MAJOR") 2 else 1
    val initiative = evolveAxis(
        baseline = seeded.initiativeBaseline,
        target = current.initiative,
        momentum = seeded.initiativeMomentum,
        weight = weight,
        tuning = previous.behaviorTuning,
    )
    val openness = evolveAxis(
        baseline = seeded.opennessBaseline,
        target = current.shareDesire,
        momentum = seeded.opennessMomentum,
        weight = weight,
        tuning = previous.behaviorTuning,
    )
    val currentSecurity = relationshipSecurity(current.dynamics)
    val security = evolveAxis(
        baseline = seeded.securityBaseline,
        target = currentSecurity,
        momentum = seeded.securityMomentum,
        weight = weight,
        tuning = previous.behaviorTuning,
    )

    val hasEvolutionSignal =
        abs(current.initiative.coerceIn(0, 100) - seeded.initiativeBaseline) >= EVOLUTION_SIGNAL_GAP ||
            abs(current.shareDesire.coerceIn(0, 100) - seeded.opennessBaseline) >= EVOLUTION_SIGNAL_GAP ||
            abs(currentSecurity - seeded.securityBaseline) >= EVOLUTION_SIGNAL_GAP
    val shouldCountObservation = normalizedSignificance == "MAJOR" || hasEvolutionSignal
    val observationCount = when {
        !shouldCountObservation -> seeded.observationCount
        seeded.observationCount == Int.MAX_VALUE -> Int.MAX_VALUE
        else -> seeded.observationCount + 1
    }
    val milestone = if (normalizedSignificance == "MAJOR") {
        current.dynamics.sharedMoments.asReversed()
            .firstOrNull { it !in previous.dynamics.sharedMoments }
            ?.trim()
            ?.take(160)
            ?.takeIf(String::isNotBlank)
            ?: relationshipMilestone(previous, current)
            ?: seeded.lastMajorMilestone
    } else seeded.lastMajorMilestone

    val newMoments = current.dynamics.sharedMoments.filterNot(previous.dynamics.sharedMoments::contains)
    val newObjects = current.dynamics.sharedObjects.filterNot(previous.dynamics.sharedObjects::contains)
    val evidenceText = listOfNotNull(
        userMessage.takeIf(String::isNotBlank),
        assistantMessage.takeIf(String::isNotBlank),
        newMoments.takeLast(2).joinToString(" ").takeIf(String::isNotBlank),
        newObjects.takeLast(2).joinToString(" ").takeIf(String::isNotBlank),
    ).joinToString(" ")
    val traitStates = evolveMutableTraits(
        persona = persona,
        previous = seeded.traitStates,
        evidenceText = evidenceText,
        significance = normalizedSignificance,
    )

    return seeded.copy(
        initiativeBaseline = initiative.baseline,
        opennessBaseline = openness.baseline,
        securityBaseline = security.baseline,
        observationCount = observationCount,
        initiativeMomentum = initiative.momentum,
        opennessMomentum = openness.momentum,
        securityMomentum = security.momentum,
        lastMajorMilestone = milestone,
        traitStates = traitStates,
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

internal fun characterEvolutionSummary(evolution: CharacterEvolutionState): String? {
    if (
        evolution.observationCount < 2 &&
        evolution.lastMajorMilestone.isBlank() &&
        evolution.traitStates.values.none { it.evidenceCount >= 2 }
    ) return null
    val traits = evolution.traitStates.entries
        .filter { it.value.evidenceCount >= 2 || abs(it.value.currentWeight - it.value.baseline) >= 4 }
        .sortedByDescending { abs(it.value.currentWeight - it.value.baseline) }
        .take(3)
        .joinToString("；") { "${it.key}=${it.value.currentWeight}/100" }
    return buildString {
        append(
            "长期主动=${evolution.initiativeBaseline}/100｜表达开放=${evolution.opennessBaseline}/100｜" +
                "关系安全感=${evolution.securityBaseline}/100｜有效观察=${evolution.observationCount}"
        )
        if (traits.isNotBlank()) append("｜可变倾向：$traits")
    }
}

internal fun StringBuilder.appendCharacterEvolutionContext(evolution: CharacterEvolutionState) {
    val summary = characterEvolutionSummary(evolution) ?: return
    appendLine("【长期人物演变】$summary")
    evolution.lastMajorMilestone.takeIf(String::isNotBlank)?.let { appendLine("最近重大经历：$it") }
    appendLine("基础人设、硬约束与用户纠正优先；稳定部分不被关系热度改写；可变倾向只由多次真实经历缓慢改变，旧习惯与新倾向允许长期并存和拉扯。")
}

private fun evolveMutableTraits(
    persona: PersonaProfile,
    previous: Map<String, CharacterTraitEvolutionState>,
    evidenceText: String,
    significance: String,
): Map<String, CharacterTraitEvolutionState> {
    val anchors = persona.evolvingTraitAnchors()
    if (anchors.isEmpty()) return previous
    val now = System.currentTimeMillis()
    val evidenceKey = normalizeEvolutionText(evidenceText)
        .take(240)
        .takeIf(String::isNotBlank)
        ?.hashCode()
        ?.toUInt()
        ?.toString(16)
        .orEmpty()
    return anchors.associate { rawTrait ->
        val trait = rawTrait.trim().take(80)
        val old = previous[trait] ?: CharacterTraitEvolutionState()
        if (
            evidenceKey.isBlank() ||
            evidenceKey in old.recentEvidenceKeys ||
            !traitRelated(trait, evidenceText)
        ) {
            trait to old.copy(momentum = decayMomentum(old.momentum))
        } else {
            val negative = NEGATIVE_TRAIT_SIGNAL.containsMatchIn(evidenceText)
            val direction = if (negative) -1 else 1
            val weight = if (significance == "MAJOR") 2 else 1
            val aligned = if (old.momentum == 0 || old.momentum.sign() == direction) old.momentum else 0
            val momentum = (aligned + direction * weight).coerceIn(-6, 6)
            val threshold = if (significance == "MAJOR") 2 else 3
            val step = if (abs(momentum) >= threshold) {
                if (significance == "MAJOR") 4 else 2
            } else 0
            val nextWeight = (old.currentWeight + direction * step).coerceIn(20, 80)
            trait to old.copy(
                currentWeight = nextWeight,
                evidenceCount = (old.evidenceCount + 1).coerceAtMost(Int.MAX_VALUE),
                momentum = if (step == 0) momentum else direction,
                counterEvidence = old.counterEvidence + if (negative) 1 else 0,
                recentEvidenceKeys = (old.recentEvidenceKeys + evidenceKey).takeLast(MAX_RECENT_TRAIT_EVIDENCE),
                lastChangedAt = if (step == 0) old.lastChangedAt else now,
            )
        }
    }
}

/**
 * V4 facts remain the author-owned baseline. Only verified habits and subjective beliefs
 * may form a slow-moving runtime tendency; changes never rewrite the original card.
 * Future-stage and unverified facts cannot silently become evidence targets.
 */
private fun PersonaProfile.evolvingTraitAnchors(): List<String> =
    if (facts.isEmpty()) {
        mutableTraits.asSequence().map(String::trim)
            .filter(String::isNotBlank).distinct().take(8).toList()
    } else {
        facts.asSequence()
            .filter { it.temporalScope.isBlank() }
            .filter {
                it.category == CharacterFactCategories.PREFERENCES_AND_HABITS ||
                    it.category == CharacterFactCategories.SUBJECTIVE_BELIEFS
            }
            .filter {
                it.provenance == CharacterFactProvenance.USER_CREATED ||
                    it.provenance == CharacterFactProvenance.CANON
            }
            .map { it.content.trim().take(80) }
            .filter(String::isNotBlank).distinct().take(8).toList()
    }

private fun traitRelated(trait: String, text: String): Boolean {
    val a = normalizeEvolutionText(trait)
    val b = normalizeEvolutionText(text)
    if (a.length < 2 || b.length < 2) return false
    if (b.contains(a)) return true
    val actionAnchor = a.takeLast(2)
    if (actionAnchor.length == 2 && actionAnchor !in GENERIC_TRAIT_ANCHORS && b.contains(actionAnchor)) {
        return true
    }
    if (a.length <= 3) return false
    val grams = a.windowed(2).toSet()
    val hits = grams.count(b::contains)
    return hits >= 2 && hits * 2 >= grams.size.coerceAtMost(6)
}

private data class EvolutionAxis(val baseline: Int, val momentum: Int)

private fun evolveAxis(
    baseline: Int,
    target: Int,
    momentum: Int,
    weight: Int,
    tuning: CharacterBehaviorTuning,
): EvolutionAxis {
    val safeBaseline = baseline.coerceIn(0, 100)
    val safeTarget = target.coerceIn(0, 100)
    val gap = safeTarget - safeBaseline
    if (abs(gap) < EVOLUTION_SIGNAL_GAP) return EvolutionAxis(safeBaseline, decayMomentum(momentum))

    val direction = if (gap > 0) 1 else -1
    val alignedMomentum = if (momentum == 0 || momentum.sign() == direction) momentum else 0
    val nextMomentum = (alignedMomentum + direction * weight).coerceIn(-MAX_EVOLUTION_MOMENTUM, MAX_EVOLUTION_MOMENTUM)
    val threshold = ((if (weight >= 2) MAJOR_EVOLUTION_THRESHOLD else MINOR_EVOLUTION_THRESHOLD) +
        tuning.evolutionThresholdOffset()).coerceAtLeast(1)
    if (abs(nextMomentum) < threshold) return EvolutionAxis(safeBaseline, nextMomentum)

    val maxStep = ((if (weight >= 2) MAJOR_EVOLUTION_STEP else MINOR_EVOLUTION_STEP) +
        tuning.evolutionStepBonus()).coerceAtLeast(1)
    val step = minOf(abs(gap), maxStep)
    return EvolutionAxis(
        baseline = (safeBaseline + direction * step).coerceIn(0, 100),
        momentum = direction,
    )
}

private fun relationshipSecurity(dynamics: RelationshipDynamics): Int {
    val stageBase = when (dynamics.stage) {
        "COMMITTED" -> 72
        "DATING" -> 66
        "AMBIGUOUS" -> 58
        "REPAIRING" -> 52
        "CONFLICT" -> 40
        "COOLING" -> 38
        "SEPARATED" -> 25
        else -> 50
    }
    val livedHistory = (dynamics.sharedMoments.size * 6 + dynamics.sharedObjects.size * 4).coerceAtMost(28)
    val numericHint = (
        dynamics.trust.coerceIn(0, 100) +
            dynamics.stability.coerceIn(0, 100) +
            (100 - dynamics.tension.coerceIn(0, 100))
        ) / 3
    return ((stageBase + livedHistory) * 2 + numericHint) / 3
}

private fun relationshipMilestone(previous: ChatCharacterState, current: ChatCharacterState): String? = when {
    current.dynamics.stage != previous.dynamics.stage -> "关系阶段：${previous.dynamics.stage}→${current.dynamics.stage}"
    current.relationshipState != previous.relationshipState -> "关系状态：${previous.relationshipState}→${current.relationshipState}"
    else -> null
}

private fun normalizeEvolutionText(text: String): String =
    text.lowercase().replace(Regex("[\\s，。！？；：、,.!?;:'\"“”‘’()（）\\[\\]【】_-]+"), "")

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

private val GENERIC_TRAIT_ANCHORS = setOf("自己", "一点", "一些", "时候", "关系", "状态", "方式")
private val NEGATIVE_TRAIT_SIGNAL = Regex("(?:不再|不想|不愿|拒绝|避免|改掉|收回|后悔|不像以前)")
private const val EVOLUTION_SIGNAL_GAP = 10
private const val MINOR_EVOLUTION_THRESHOLD = 3
private const val MAJOR_EVOLUTION_THRESHOLD = 2
private const val MINOR_EVOLUTION_STEP = 1
private const val MAJOR_EVOLUTION_STEP = 3
private const val MAX_EVOLUTION_MOMENTUM = 6
private const val MAX_RECENT_TRAIT_EVIDENCE = 8
