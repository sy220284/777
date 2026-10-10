package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCanonicalV4Test {
    @Test fun legacyOnlyCharacterMigratesToFactsAndIndependentLoreExactlyOnce() {
        val old = PersonaProfile(
            name = "晴川", portrait = "少年时是制琴师", lifeContext = "每天修琴",
            quirks = listOf("习惯喝浓茶"), worldSetting = "世界存在浮空岛",
            coreValues = listOf("答应的事必须兑现"),
            hardConstraints = listOf("不可背叛家人"),
        )
        val migrated = old.canonicalV4()
        assertTrue(migrated.facts.any { it.category == CharacterFactCategories.BIOGRAPHY &&
            it.content == "少年时是制琴师" })
        assertTrue(migrated.facts.any { it.category == CharacterFactCategories.LIFE_GRAVITY &&
            it.content == "每天修琴" })
        assertEquals("世界存在浮空岛", migrated.loreEntries.single().content)
        assertEquals(old.hardConstraints, migrated.hardConstraints)
        assertEquals("", migrated.portrait)
        assertEquals("", migrated.worldSetting)
        assertEquals(migrated, migrated.canonicalV4())
    }

    @Test fun existingV4NeverResurrectsConflictingHiddenLegacyPersonalityOrSpoiler() {
        val current = PersonaProfile(
            coreIdentity = "在小城开书店",
            facts = listOf(CharacterFact(
                id = "fact-1", category = CharacterFactCategories.LIFE_GRAVITY,
                content = "每天整理新书", temporalScope = "opening",
            )),
            portrait = "旧资料：已知结局与终极秘密",
            lifeContext = "旧资料：每天驾驶宇宙飞船",
            worldSetting = "旧世界设定：提前知道终章",
        )
        val normalized = current.canonicalV4()
        assertEquals(current.facts, normalized.facts)
        assertEquals(current.coreIdentity, normalized.coreIdentity)
        assertTrue(normalized.loreEntries.isEmpty())
        assertFalse(normalized.toString().contains("宇宙飞船"))
        assertFalse(normalized.toString().contains("提前知道终章"))
    }
}
