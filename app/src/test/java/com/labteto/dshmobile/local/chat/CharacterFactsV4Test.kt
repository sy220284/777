package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterFactsV4Test {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun v4IdentityFactsAndSourcesRoundTrip() {
        val profile = PersonaProfile(
            name = "叶澜",
            franchise = "原创",
            coreIdentity = "出版社设计师",
            facts = listOf(
                CharacterFact(
                    id = "belief-1", category = CharacterFactCategories.SUBJECTIVE_BELIEFS,
                    content = "认为自己的判断通常可靠",
                    perspective = "本人", provenance = CharacterFactProvenance.USER_CREATED,
                ),
            ),
        )
        val restored = json.decodeFromString(
            PersonaProfile.serializer(),
            json.encodeToString(PersonaProfile.serializer(), profile),
        )
        assertEquals(profile, restored)
        assertEquals("出版社设计师", restored.coreIdentity)
        assertEquals("本人", restored.facts.single().perspective)
    }

    @Test fun oneFactCanBeEditedWithoutCreatingASecondSource() {
        val profile = PersonaProfile(name = "叶澜")
            .withFact(CharacterFactCategories.PERSONALITY, "认真")
            .withFact(CharacterFactCategories.PERSONALITY, "认真且有主见")
        assertEquals(1, profile.facts.size)
        assertEquals("认真且有主见", profile.factText(CharacterFactCategories.PERSONALITY))
    }

    @Test fun categoryEditReplacesPresetFactAndPreservesStageSpecificFacts() {
        val original = CharacterFact(
            "builtin-personality", CharacterFactCategories.PERSONALITY, "原始性格",
            provenance = CharacterFactProvenance.CANON,
        )
        val later = CharacterFact(
            "later", CharacterFactCategories.PERSONALITY, "后续揭示",
            temporalScope = "act-2",
        )
        val profile = PersonaProfile(facts = listOf(original, later))
            .withFact(CharacterFactCategories.PERSONALITY, "用户修改")
        assertEquals("用户修改", profile.factText(CharacterFactCategories.PERSONALITY))
        assertEquals(listOf("builtin-personality", "later"), profile.facts.map(CharacterFact::id))
        assertEquals(CharacterFactProvenance.USER_CREATED,
            profile.facts.first { it.id == original.id }.provenance)
        assertEquals(listOf("later"),
            profile.facts.filter { it.temporalScope.isNotBlank() }.map(CharacterFact::id))
    }

    @Test fun automaticEnrichmentPreservesDistinctFactsWithinOneCategory() {
        val previous = listOf(CharacterFact("one", CharacterFactCategories.BIOGRAPHY, "少年时学医"))
        val additional = listOf(
            CharacterFact("two", CharacterFactCategories.BIOGRAPHY, "后来成为主治医师"),
            CharacterFact("three", CharacterFactCategories.BIOGRAPHY, "后来成为主治医师"),
        )
        val merged = mergeGeneratedCharacterFacts(previous, additional)
        assertEquals(2, merged.size)
        assertEquals(listOf("one", "two"), merged.map(CharacterFact::id))
    }

    @Test fun storyStageIsDistinctFromSceneClock() {
        val story = ChatContextState(
            scene = ChatSceneState(sceneTime = "晚上八点"),
            storyStage = "act-2",
        ).normalized()
        assertEquals("act-2", story.storyStage)
        assertEquals("晚上八点", story.scene.sceneTime)
    }

    @Test fun explicitStoryStageGatesUnreleasedFacts() {
        val profile = PersonaProfile(name = "阿云", facts = listOf(
            CharacterFact("now", CharacterFactCategories.RELATIONSHIPS, "认识阿宁"),
            CharacterFact("later", CharacterFactCategories.RELATIONSHIPS, "获知一个秘密", temporalScope = "act-2"),
        ))
        assertEquals(listOf("now"), profile.visibleFacts().map(CharacterFact::id))
        assertEquals(listOf("now", "later"), profile.visibleFacts("act-2").map(CharacterFact::id))
    }

    @Test fun generatedFactsCannotOverwriteAnExplicitUserEdit() {
        val user = CharacterFact("a", CharacterFactCategories.PERSONALITY, "很安静",
            provenance = CharacterFactProvenance.USER_CREATED)
        val generated = CharacterFact("b", CharacterFactCategories.PERSONALITY, "十分活泼",
            provenance = CharacterFactProvenance.INFERRED)
        assertEquals(listOf(user), mergeGeneratedCharacterFacts(listOf(user), listOf(generated)))
    }

    @Test fun allBundledCharactersHaveCanonicalV4FactsAndNoPresetGrowthScript() {
        PersonaPresetCatalog.presets.forEach { preset ->
            val p = preset.persona
            assertTrue(preset.id, p.coreIdentity.isNotBlank())
            assertTrue(preset.id, p.factText(CharacterFactCategories.PERSONALITY).isNotBlank())
            assertTrue(preset.id, p.factText(CharacterFactCategories.BIOGRAPHY).isNotBlank())
            assertTrue(preset.id, p.mutableTraits.isEmpty())
            assertFalse(preset.id, p.facts.any { it.content.isBlank() })
        }
    }

    @Test fun v4GalleryDirtyCheckTracksIdentityAndAllFactMetadata() {
        val originalFact = CharacterFact(
            id = "f", category = CharacterFactCategories.SUBJECTIVE_BELIEFS,
            content = "相信自己的判断", perspective = "本人",
            provenance = CharacterFactProvenance.CANON,
            sourceReference = "原作第一章",
        )
        val profile = PersonaProfile(
            id = "persona", name = "叶澜", coreIdentity = "设计师", facts = listOf(originalFact),
        )
        val entry = PersonaGalleryEntry(id = "persona", persona = profile)
        fun dirty(next: PersonaProfile): Boolean = galleryEntryHasUnsavedChanges(
            entry = entry, storyId = null, persona = next,
            history = emptyList(), chatState = ChatCharacterState(),
        )
        assertFalse(dirty(profile))
        assertTrue(dirty(profile.copy(coreIdentity = "调查员")))
        assertTrue(dirty(profile.copy(facts = listOf(originalFact.copy(content = "改变了认知")))))
        assertTrue(dirty(profile.copy(facts = listOf(originalFact.copy(perspective = "旁人")))))
        assertTrue(dirty(profile.copy(facts = listOf(originalFact.copy(temporalScope = "act-2")))))
        assertTrue(dirty(profile.copy(facts = listOf(originalFact.copy(sourceReference = "原作第二章")))))
        assertTrue(dirty(profile.copy(facts = listOf(originalFact.copy(provenance = CharacterFactProvenance.INFERRED)))))
        assertTrue(dirty(profile.copy(facts = emptyList())))
    }

    @Test fun v4GeneratedJsonCarriesSourceAndSubjectivePerspective() {
        val draft = parsePersonaDraft(json, """
            {"name":"黎深","coreIdentity":"心脏外科医生","franchise":"恋与深空",
             "facts":[{"id":"f1","category":"subjectiveBeliefs",
             "content":"认为职责应该优先","perspective":"本人","provenance":"CANON"}]}
        """.trimIndent())
        assertEquals("心脏外科医生", draft.coreIdentity)
        assertEquals(CharacterFactProvenance.CANON, draft.facts.single().provenance)
        assertEquals("本人", draft.facts.single().perspective)
    }
}
