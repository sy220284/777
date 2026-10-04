package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBehaviorTuningTest {
    @Test
    fun naturalTuningPreservesExistingTiming() {
        val tuning = CharacterBehaviorTuning()
        assertEquals(3, tuning.transientTtl(3))
        assertEquals(3, tuning.cooldownTurns(3))
        assertEquals(10, tuning.relationshipDelta(10))
    }

    @Test
    fun persistenceAndNoveltyOnlyChangeBehaviorTiming() {
        val tuned = CharacterBehaviorTuning(persistence = 100, novelty = 100)
        assertTrue(tuned.transientTtl(5) > CharacterBehaviorTuning().transientTtl(5))
        assertTrue(tuned.cooldownTurns(3) > CharacterBehaviorTuning().cooldownTurns(3))
    }

    @Test
    fun newerExplicitNaturalSettingCanReplaceAnOlderCustomSetting() {
        val custom = CharacterBehaviorTuning(intimacy = 75, updatedAt = 10)
        val reset = CharacterBehaviorTuning(updatedAt = 20)
        assertEquals(reset, mergeCharacterBehaviorTuning(custom, reset))
    }

    @Test
    fun longTermBaselineStillComesFromObservedState() {
        val previous = ChatCharacterState(
            behaviorTuning = CharacterBehaviorTuning(evolution = 100, updatedAt = 10),
            evolution = CharacterEvolutionState(initiativeBaseline = 55, observationCount = 2),
        )
        val current = previous.copy(initiative = 90)
        val evolved = evolveCharacterEvolution(previous, current, "MINOR")
        assertEquals(55, previous.evolution.initiativeBaseline)
        assertTrue(evolved.initiativeBaseline >= 55)
    }
    @Test
    fun malformedPersistedTuningIsClampedBeforeUse() {
        val normalized = CharacterBehaviorTuning(
            intimacy = Int.MIN_VALUE,
            persistence = Int.MAX_VALUE,
            initiative = -1,
            openness = 101,
            evolution = Int.MAX_VALUE,
            emotionalAfterglow = Int.MIN_VALUE,
            novelty = 999,
            loreAdherence = -999,
            relationshipPace = 101,
            updatedAt = Long.MIN_VALUE,
        ).normalized()

        assertEquals(0, normalized.intimacy)
        assertEquals(100, normalized.persistence)
        assertEquals(0, normalized.initiative)
        assertEquals(100, normalized.openness)
        assertEquals(100, normalized.evolution)
        assertEquals(0, normalized.emotionalAfterglow)
        assertEquals(100, normalized.novelty)
        assertEquals(0, normalized.loreAdherence)
        assertEquals(100, normalized.relationshipPace)
        assertEquals(0L, normalized.updatedAt)
    }

    @Test
    fun extremeBaseValuesNeverOverflowOrThrow() {
        val tuning = CharacterBehaviorTuning(
            persistence = 100,
            novelty = 100,
            relationshipPace = 100,
        )

        assertTrue(tuning.transientTtl(Int.MAX_VALUE) > 0)
        assertTrue(tuning.cooldownTurns(Int.MAX_VALUE) in 1..8)
        assertTrue(tuning.relationshipDelta(Int.MAX_VALUE) > 0)
        assertEquals(1, tuning.transientTtl(Int.MIN_VALUE))
        assertEquals(1, tuning.relationshipDelta(Int.MIN_VALUE))
    }

    @Test
    fun v3ModeConsumesLoreAndRelationshipPaceWithoutRewritingRelationshipFacts() {
        val attention = CharacterAttentionProjection()
        val naturalState = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            behaviorTuning = CharacterBehaviorTuning(),
        )
        val tunedState = naturalState.copy(
            behaviorTuning = CharacterBehaviorTuning(
                loreAdherence = 0,
                relationshipPace = 100,
            ),
        )

        val natural = resolveCharacterMode(PersonaProfile(), naturalState, "靠近一点", attention).vector
        val tuned = resolveCharacterMode(PersonaProfile(), tunedState, "靠近一点", attention).vector

        assertTrue(tuned.freedom > natural.freedom)
        assertTrue(tuned.initiative > natural.initiative)
        assertEquals(naturalState.dynamics, tunedState.dynamics)
    }

    @Test
    fun staleImportedTuningCannotOverwriteNewerLocalChoice() {
        val local = CharacterBehaviorTuning(intimacy = 90, updatedAt = 200)
        val stale = CharacterBehaviorTuning(intimacy = 10, updatedAt = 100)

        assertEquals(local, mergeCharacterBehaviorTuning(local, stale))
    }
}
