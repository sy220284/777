package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaSchemaMigrationTest {
    @Test fun v4UserFactsStayAuthoritativeDuringGalleryMerge() {
        val user = CharacterFact("same", CharacterFactCategories.PERSONALITY,
            "说话谨慎", provenance = CharacterFactProvenance.USER_CREATED)
        val generated = CharacterFact("same", CharacterFactCategories.PERSONALITY,
            "反应热烈", provenance = CharacterFactProvenance.INFERRED)
        val base = PersonaProfile(name="阿青", coreIdentity="游历剑客", facts=listOf(user))
        val result = mergePersonaProfiles(base, base.copy(facts=listOf(generated)))
        assertEquals(user, result.facts.single())
    }

    @Test fun v4ShareAndCompactShareRespectFactScopes() {
        val card = PersonaProfile(name="阿青", coreIdentity="江湖剑客", facts=listOf(
            CharacterFact("a", CharacterFactCategories.VALUES_AND_TRADEOFFS, "重视诺言"),
            CharacterFact("b", CharacterFactCategories.UNFINISHED_BUSINESS, "尚未完成约定"),
        ))
        assertEquals(card.facts, fullSharePersona(card).facts)
        val compact = compactSharePersona(card)
        assertEquals("重视诺言", compact.facts.single().content)
        assertTrue(compact.facts.none { it.category == CharacterFactCategories.UNFINISHED_BUSINESS })
    }
}
