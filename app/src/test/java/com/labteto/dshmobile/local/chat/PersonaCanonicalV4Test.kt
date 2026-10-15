package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCanonicalV4Test {
    @Test fun retiredJsonPropertiesDoNotReturnAfterDecodingAndSerializing() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val legacyJson = """{"name":"晴川","portrait":"制琴师","lifeContext":"每天修琴",
            "quirks":["喜欢浓茶"],"worldSetting":"浮空岛","coreValues":["守诺"]}"""
        val decoded = json.decodeFromString(PersonaProfile.serializer(), legacyJson)
        val normalized = decoded.canonicalV4()
        assertTrue(normalized.facts.isEmpty())
        assertTrue(normalized.loreEntries.isEmpty())
        assertTrue(normalized.coreIdentity.isEmpty())
        val exported = json.encodeToString(PersonaProfile.serializer(), normalized)
        val removedFields = listOf(
            "portrait", "lifeContext", "attentionBiases", "attentionKeywords",
            "perceptionBlindSpots", "quirks", "limitations", "coreValues",
            "coreTension", "stableTraits", "mutableTraits",
            "initialUserImpression", "voiceSamples", "worldSetting",
        )
        // encodeDefaults=true: even forgotten empty schema properties would be serialized.
        removedFields.forEach { assertFalse(exported.contains("\"$it\"")) }
        assertEquals(normalized, normalized.canonicalV4())
    }

    @Test fun droppedCategoriesAreNeverRecreatedAsOtherFields() {
        val retired = listOf(
            "definingChoices", "emotionalImprints", "lifeGravity",
            "unfinishedBusiness", "limitsAndCosts", "sensorySignature",
        ).mapIndexed { index, category ->
            CharacterFact("old-$index", category, "过去的分类 $category")
        }
        val current = CharacterFact("present", CharacterFactCategories.PERSONALITY, "认真")
        val custom = CharacterFact("custom", "自己的补充设定", "喜欢看星星")
        val persona = PersonaProfile(name = "小岚", coreIdentity = "书店店主",
            facts = retired + current + custom)
        val clean = persona.canonicalV4()
        assertEquals(listOf(current, custom), clean.facts)
        assertEquals("书店店主", clean.coreIdentity)
        assertTrue(clean.facts.none { it.category in retired.map(CharacterFact::category) })
        assertEquals(clean, clean.canonicalV4())
    }

    @Test fun distinctNewFactsRemainIndependentAndDeduplicatedByIdentity() {
        val one = PersonaProfile(name = "角色", coreIdentity = "手艺人",
            facts = listOf(CharacterFact("one", CharacterFactCategories.VALUES_AND_TRADEOFFS,
                "保持选择权", provenance = CharacterFactProvenance.USER_CREATED))).canonicalV4()
        val two = PersonaProfile(name = "角色", coreIdentity = "手艺人",
            facts = listOf(CharacterFact("two", CharacterFactCategories.VALUES_AND_TRADEOFFS,
                "推动长期计划", provenance = CharacterFactProvenance.USER_CREATED))).canonicalV4()
        val merged = mergePersonaProfiles(one, two)
        assertEquals(setOf("保持选择权", "推动长期计划"),
            merged.facts.map(CharacterFact::content).toSet())
        assertEquals(merged.facts, mergePersonaProfiles(merged, two).facts)
    }
}
