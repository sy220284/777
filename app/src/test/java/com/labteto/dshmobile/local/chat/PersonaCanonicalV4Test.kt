package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCanonicalV4Test {
    @Test fun legacyOnlyCardDoesNotRegenerateFactsOrWorldBook() {
        val old = PersonaProfile(
            name = "晴川", portrait = "少年时是制琴师",
            lifeContext = "每天修琴", quirks = listOf("喜欢浓茶"),
            worldSetting = "世界存在浮空岛", coreValues = listOf("守住承诺"),
        )
        val normalized = old.canonicalV4()
        assertTrue(normalized.facts.isEmpty())
        assertTrue(normalized.loreEntries.isEmpty())
        assertTrue(normalized.portrait.isBlank())
        assertTrue(normalized.worldSetting.isBlank())
        assertTrue(normalized.coreValues.isEmpty())
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
            facts = retired + current + custom, portrait = "旧版身份说法")
        val clean = persona.canonicalV4()
        assertEquals(listOf(current, custom), clean.facts)
        assertEquals("书店店主", clean.coreIdentity)
        assertFalse(clean.toString().contains("旧版身份说法"))
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
