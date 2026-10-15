package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBehaviorTuningTest {
    @Test
    fun legacyBehaviorAxesNoLongerRepresentAnActiveSetting() {
        val legacy = CharacterBehaviorTuning(intimacy = 100, novelty = 0,
            persistence = 100, evolution = 100, lockRelationshipStage = true)
        assertTrue(legacy.isNatural())
        assertEquals(CharacterBehaviorTuning().signature(), legacy.signature())
    }

    @Test
    fun composerAndPersonaShareOneStoredTemperaturePosition() {
        val fine = CharacterBehaviorTuning(expressionVariation = 60)
        assertEquals(2, fine.composerTemperatureLevel())
        val high = fine.withComposerTemperatureLevel(4)
        assertEquals(60, high.expressionVariation) // The character expression axis is independent.
        assertEquals(100, high.samplingTemperaturePosition)
        assertEquals(4, high.composerTemperatureLevel())
        assertEquals(0, high.withComposerTemperatureLevel(-1).samplingTemperaturePosition)
        assertEquals(100, high.withComposerTemperatureLevel(9).samplingTemperaturePosition)
    }

    @Test
    fun deepSeekTemperatureCapsAtOnePointThreeAndLegacyChoicesStayMeaningful() {
        val model = "deepseek-flash"
        val endpoint = "https://api.deepseek.com"
        assertEquals(1.3, CharacterBehaviorTuning().roleplayTemperature(model, endpoint)!!, 0.000001)
        assertEquals(0.0, CharacterBehaviorTuning(expressionVariation = 0)
            .roleplayTemperature(model, endpoint)!!, 0.0)
        assertEquals(1.3, CharacterBehaviorTuning(expressionVariation = 100)
            .roleplayTemperature(model, endpoint)!!, 0.0)
        assertEquals(0.65, CharacterBehaviorTuning(expressionVariation = 25)
            .roleplayTemperature(model, endpoint)!!, 0.000001)
        assertEquals(1.3, CharacterBehaviorTuning(expressionVariation = 75)
            .roleplayTemperature(model, endpoint)!!, 0.000001)
        assertEquals(0.0, CharacterBehaviorTuning(expressionVariation = -500)
            .roleplayTemperature(model, endpoint)!!, 0.0)
        assertEquals(1.3, CharacterBehaviorTuning(expressionVariation = 500)
            .roleplayTemperature(model, endpoint)!!, 0.0)
    }

    @Test
    fun deepSeekChatDefaultsToMaximumAndUsesAllFiveTemperatureStops() {
        for (model in listOf("deepseek-flash", "deepseek-v4-pro")) {
            val url = "https://api.deepseek.com"
            val range = com.labteto.dshmobile.local.model.LocalModelPresets
                .chatTemperatureRangeFor(model, url)!!
            assertEquals(1.3, range.maximum, 0.0)
            assertEquals(100, range.defaultPosition)
            val clean = CharacterBehaviorTuning()
            assertEquals(100, clean.temperaturePosition(range))
            assertEquals(4, clean.composerTemperatureLevel(range))
            assertEquals(1.3, clean.roleplayTemperature(model, url)!!, 0.0)
            listOf(0.0, 0.325, 0.65, 0.975, 1.3).forEachIndexed { level, expected ->
                val selected = clean.withComposerTemperatureLevel(level)
                assertEquals(level * 25, selected.temperaturePosition(range))
                assertEquals(expected, selected.roleplayTemperature(model, url)!!, 0.000001)
                assertEquals(50, selected.expressionVariation)
            }
            // Older 25/50/75 values retain their former output up to the new cap.
            assertEquals(0.65,
                CharacterBehaviorTuning(expressionVariation = 25).roleplayTemperature(model, url)!!,
                0.000001)
            assertEquals(1.3,
                CharacterBehaviorTuning(expressionVariation = 75).roleplayTemperature(model, url)!!,
                0.000001)
        }
    }

    @Test
    fun modelSpecificTemperatureBoundsRejectUnknownAndFixedRoutes() {
        assertEquals(null, CharacterBehaviorTuning().roleplayTemperature(
            "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/openai"))
        assertEquals(1.0, com.labteto.dshmobile.local.model.LocalModelPresets.chatTemperatureRangeFor(
            "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/openai")!!.at(50), 0.0)
        assertEquals(0.0, CharacterBehaviorTuning(expressionVariation = 0).roleplayTemperature(
            "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/openai")!!, 0.0)
        assertEquals(2.0, CharacterBehaviorTuning(expressionVariation = 100).roleplayTemperature(
            "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/openai")!!, 0.0)
        assertEquals(1.3, CharacterBehaviorTuning(expressionVariation = 100).roleplayTemperature(
            "deepseek-flash", "https://api.deepseek.com/v1")!!, 0.0)
        assertEquals(null, CharacterBehaviorTuning().roleplayTemperature(
            "deepseek-flash", "https://proxy.example.com/v1"))
        assertEquals(null, CharacterBehaviorTuning().roleplayTemperature(
            "kimi-for-coding", "https://api.kimi.com/coding/v1"))
        assertEquals(null, CharacterBehaviorTuning().roleplayTemperature(
            "gpt-6-astra", "https://api.openai.com/v1"))
    }

    @Test
    fun temperatureRangeSupportsArbitraryVerifiedProviderLimits() {
        val range = com.labteto.dshmobile.local.model.LocalModelTemperatureRange(0.15, 0.95, 0.75)
        assertEquals(0.15, range.at(0), 0.0)
        assertEquals(0.75, range.at(50), 0.0)
        assertEquals(0.95, range.at(100), 0.0)
        assertEquals(0.45, range.at(25), 0.000001)
        assertEquals(0.85, range.at(75), 0.000001)
    }

    @Test
    fun legacyBehaviorFieldsRemainDecodableWithoutAffectingSampling() {
        val legacy = kotlinx.serialization.json.Json.decodeFromString(
            CharacterBehaviorTuning.serializer(),
            """{"persistence":100,"novelty":0,"relationshipPace":100,"expressionVariation":25}""",
        )
        assertEquals(25, legacy.expressionVariation)
        assertEquals(CharacterBehaviorTuning(expressionVariation = 25).roleplayTemperature(
            "deepseek-flash", "https://api.deepseek.com"),
            legacy.roleplayTemperature("deepseek-flash", "https://api.deepseek.com"))
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
            expressionVariation = 999,
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
        assertEquals(100, normalized.expressionVariation)
    }

    @Test
    fun retiredParametersDoNotChangeTheActiveSamplingSignature() {
        val base = CharacterBehaviorTuning(samplingTemperaturePosition = 25)
        assertEquals(base.signature(), base.copy(intimacy = 100, initiative = 0,
            openness = 0, novelty = 100, relationshipPace = 0).signature())
    }

    @Test
    fun oldTuningCannotOverwriteCharacterContext() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青", coreIdentity = "内向寡言，重视自由")
        val base = ChatCharacterState()
        val tuned = base.copy(behaviorTuning = CharacterBehaviorTuning(
            intimacy = 100, initiative = 100, loreAdherence = 0, relationshipPace = 100))
        assertEquals(projector.project(persona, base, ChatContextState(), "陪我一会", null),
            projector.project(persona, tuned, ChatContextState(), "陪我一会", null))
    }

    @Test
    fun staleImportedTuningCannotOverwriteNewerLocalChoice() {
        val local = CharacterBehaviorTuning(intimacy = 90, updatedAt = 200)
        val stale = CharacterBehaviorTuning(intimacy = 10, updatedAt = 100)

        assertEquals(local, mergeCharacterBehaviorTuning(local, stale))
    }
}
