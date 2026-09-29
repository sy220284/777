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
}
