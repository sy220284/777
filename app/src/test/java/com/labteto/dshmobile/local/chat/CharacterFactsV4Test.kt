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

    @Test fun thirdPartySubjectiveBeliefsDoNotBecomeCharactersOwnKnowledge() {
        val persona = PersonaProfile(name = "叶澜", coreIdentity = "出版社设计师",
            facts = listOf(
                CharacterFact("own", CharacterFactCategories.SUBJECTIVE_BELIEFS,
                    "叶澜相信阿宁是可信赖的人", perspective = "本人"),
                CharacterFact("other", CharacterFactCategories.SUBJECTIVE_BELIEFS,
                    "阿紫认为阿宁藏着秘密", perspective = "阿紫"),
                CharacterFact("public", CharacterFactCategories.BIOGRAPHY,
                    "叶澜和阿宁一起参观过灯塔", perspective = "公开事件"),
            ))
        // The complete source survives in the authoring card.
        assertEquals(3, persona.facts.size)
        assertEquals(listOf("own", "public"),
            persona.characterKnownFacts().map(CharacterFact::id))
        val model = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(),
                "你和阿宁知道关于灯塔与秘密的哪些事情？", null)
        val prompt = model.stablePrompt + model.dynamicPrompt
        assertFalse(prompt.contains("阿紫认为阿宁藏着秘密"))
        assertTrue(prompt.contains("叶澜和阿宁一起参观过灯塔"))
        assertTrue(prompt.contains("叶澜相信阿宁是可信赖的人"))
    }

    @Test fun uncertainCoreFactsStayOutOfStableIdentityButRemainDiscoverableWithWarning() {
        val persona = PersonaProfile(name = "阿岚", coreIdentity = "药师",
            facts = listOf(CharacterFact("suspect", CharacterFactCategories.PERSONALITY,
                "可能隐瞒一段旧事", provenance = CharacterFactProvenance.INFERRED)))
        val projected = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(),
                "你是不是隐瞒旧事？", null)
        assertFalse(projected.stablePrompt.contains("可能隐瞒一段旧事"))
        assertTrue(projected.dynamicPrompt.contains("推断，须保持不确定性"))
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
        assertEquals(setOf("builtin-personality", "later"), profile.facts.map(CharacterFact::id).toSet())
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

    @Test fun v4RuntimeDoesNotReuseConflictingOldPersonaDescriptions() {
        val persona = PersonaProfile(
            name = "阿云",
            coreIdentity = "咖啡师",
            facts = listOf(
                CharacterFact("life", CharacterFactCategories.LIFE_GRAVITY, "周末会去看外婆"),
                CharacterFact("bio", CharacterFactCategories.BIOGRAPHY, "从小住在江南"),
            ),
            lifeContext = "旧版本生活资料：每天驾驶宇宙飞船",
            portrait = "旧版本人物背景：天外来客",
        )
        val runtime = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(),
                "今天在忙什么？", null)
        assertFalse(runtime.stablePrompt.contains("天外来客"))
        assertFalse(runtime.stablePrompt.contains("宇宙飞船"))
        assertFalse(runtime.dynamicPrompt.contains("宇宙飞船"))
    }

    @Test fun relatedFactsAreRecalledOnlyWhenVisibleInCurrentStoryStage() {
        val persona = PersonaProfile(
            name = "阿云", coreIdentity = "档案管理员",
            facts = listOf(
                CharacterFact("decision", CharacterFactCategories.DEFINING_CHOICES,
                    "阿云决定守护旧图书馆", relatedFactIds = listOf("memory")),
                CharacterFact("memory", CharacterFactCategories.EMOTIONAL_IMPRINTS,
                    "童年的那场洪水留下无法磨灭的记忆",
                    temporalScope = "act-2", sourceReference = "原作第二幕"),
            ),
        )
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val early = projector.project(
            persona, ChatCharacterState(), ChatContextState(),
            "为什么阿云决定守护旧图书馆？", null,
        )
        val late = projector.project(
            persona, ChatCharacterState(), ChatContextState(storyStage = "act-2"),
            "为什么阿云决定守护旧图书馆？", null,
        )
        assertTrue(early.dynamicPrompt.contains("守护旧图书馆"))
        assertFalse(early.dynamicPrompt.contains("童年的那场洪水"))
        assertTrue(late.dynamicPrompt.contains("童年的那场洪水"))
        assertTrue(late.dynamicPrompt.contains("来源：原作第二幕"))
    }

    @Test fun onlyCurrentStoryStageCanReleasePlotScopedFactsToModel() {
        val persona = PersonaProfile(
            name = "阿云", coreIdentity = "档案管理员",
            facts = listOf(
                CharacterFact("early", CharacterFactCategories.RELATIONSHIPS, "认识阿宁"),
                CharacterFact("future", CharacterFactCategories.RELATIONSHIPS,
                    "第二幕里阿宁透露了遗失的秘密", temporalScope = "act-2"),
            ),
        )
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val early = projector.project(
            persona, ChatCharacterState(), ChatContextState(scene = ChatSceneState(sceneTime = "第二幕")),
            "阿宁透露的秘密是什么？", null,
        )
        val late = projector.project(
            persona, ChatCharacterState(), ChatContextState(storyStage = "act-2"),
            "阿宁透露的秘密是什么？", null,
        )
        assertFalse(early.dynamicPrompt.contains("遗失的秘密"))
        assertTrue(late.dynamicPrompt.contains("遗失的秘密"))
        assertTrue(late.dynamicPrompt.contains("【当前剧情阶段】act-2"))
    }

    @Test fun storyStageGatesStableTraitsLifeAndLegacyFallbackTogether() {
        val persona = PersonaProfile(
            name = "阿云", coreIdentity = "旧书店店主",
            portrait = "隐藏旧版背景：后来的全部秘密",
            lifeContext = "隐藏旧版生活：被永远安排的计划",
            facts = listOf(
                CharacterFact("early", CharacterFactCategories.BIOGRAPHY, "在小镇经营书店"),
                CharacterFact("later", CharacterFactCategories.PERSONALITY,
                    "得知真相后变得格外谨慎", temporalScope = "act-2",
                    provenance = CharacterFactProvenance.CANON),
                CharacterFact("later-life", CharacterFactCategories.LIFE_GRAVITY,
                    "夜里会整理那封密信", temporalScope = "act-2"),
            ),
        )
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        fun stage(value: String) = projector.project(
            persona, ChatCharacterState(), ChatContextState(storyStage = value),
            "你现在每天在做什么？", null,
        )
        val early = stage("")
        val late = stage("act-2")
        assertFalse(early.stablePrompt.contains("格外谨慎"))
        assertFalse(early.dynamicPrompt.contains("那封密信"))
        assertFalse((early.stablePrompt + early.dynamicPrompt).contains("隐藏旧版背景"))
        assertFalse((early.stablePrompt + early.dynamicPrompt).contains("隐藏旧版生活"))
        assertTrue(late.stablePrompt.contains("格外谨慎"))
        assertTrue(late.dynamicPrompt.contains("那封密信"))
    }

    @Test fun aiEnrichmentAddsDistinctFactsAlongsideProtectedUserEdit() {
        val user = CharacterFact("user", CharacterFactCategories.RELATIONSHIPS,
            "与阿宁是旧识", provenance = CharacterFactProvenance.USER_CREATED)
        val generated = CharacterFact("new", CharacterFactCategories.RELATIONSHIPS,
            "与小周在大学相识", provenance = CharacterFactProvenance.USER_CREATED)
        val merged = mergeGeneratedCharacterFacts(listOf(user), listOf(generated))
        assertEquals(2, merged.size)
        assertEquals(user, merged.first())
        assertEquals(CharacterFactProvenance.INFERRED, merged.last().provenance)
    }

    @Test fun aiEnrichmentCannotPromoteItsOwnFactProvenanceOnInsertOrIdReplacement() {
        val inferred = CharacterFact("inferred", CharacterFactCategories.BIOGRAPHY, "早年行医",
            provenance = CharacterFactProvenance.INFERRED)
        val canon = CharacterFact("canon", CharacterFactCategories.BIOGRAPHY, "在故乡开诊所",
            provenance = CharacterFactProvenance.CANON)
        val user = CharacterFact("user", CharacterFactCategories.BIOGRAPHY, "用户补写经历",
            provenance = CharacterFactProvenance.USER_CREATED)
        val incoming = listOf(
            inferred.copy(content = "模型建议的新经历", provenance = CharacterFactProvenance.USER_CREATED),
            canon.copy(content = "模型误改原作", provenance = CharacterFactProvenance.CANON),
            user.copy(content = "模型误改用户编辑", provenance = CharacterFactProvenance.INFERRED),
            CharacterFact("new-canon", CharacterFactCategories.BIOGRAPHY, "声称是原作的事实",
                provenance = CharacterFactProvenance.CANON),
            CharacterFact("new-author", CharacterFactCategories.RELATIONSHIPS, "声称是用户创作",
                provenance = CharacterFactProvenance.USER_CREATED),
        )
        val merged = mergeGeneratedCharacterFacts(listOf(inferred, canon, user), incoming)
        assertEquals("模型建议的新经历", merged.first { it.id == "inferred" }.content)
        assertEquals(CharacterFactProvenance.INFERRED,
            merged.first { it.id == "inferred" }.provenance)
        assertEquals(canon, merged.first { it.id == "canon" })
        assertEquals(user, merged.first { it.id == "user" })
        assertEquals(CharacterFactProvenance.UNVERIFIED,
            merged.first { it.id == "new-canon" }.provenance)
        assertEquals(CharacterFactProvenance.INFERRED,
            merged.first { it.id == "new-author" }.provenance)
    }

    @Test fun aiEnrichmentPreservesUnverifiedReplacementState() {
        val previous = CharacterFact("same", CharacterFactCategories.PERSONALITY, "旧推测",
            provenance = CharacterFactProvenance.UNVERIFIED)
        val suggested = previous.copy(content = "新候选", provenance = CharacterFactProvenance.CANON)
        val merged = mergeGeneratedCharacterFacts(listOf(previous), listOf(suggested))
        assertEquals("新候选", merged.single().content)
        assertEquals(CharacterFactProvenance.UNVERIFIED, merged.single().provenance)
    }

    @Test fun explicitStoryVisitsPreserveEarlierKnowledgeAndRewindRevokesLaterKnowledge() {
        val profile = PersonaProfile(facts = listOf(
            CharacterFact("a", CharacterFactCategories.BIOGRAPHY, "第一幕的秘密", temporalScope = "act-1"),
            CharacterFact("b", CharacterFactCategories.BIOGRAPHY, "第二幕的秘密", temporalScope = "act-2"),
            CharacterFact("c", CharacterFactCategories.BIOGRAPHY, "第三幕的秘密", temporalScope = "act-3"),
        ))
        val first = ChatContextState().withStoryStageSelection("act-1")
        val second = first.withStoryStageSelection("act-2")
        assertEquals(listOf("act-1", "act-2"), second.unlockedStoryStages)
        assertEquals(listOf("a", "b"),
            profile.visibleFacts(second.storyStage, second.visibleStoryStages()).map(CharacterFact::id))
        val restored = json.decodeFromString(ChatContextState.serializer(),
            json.encodeToString(ChatContextState.serializer(), second))
        assertEquals(second, restored)
        val third = restored.withStoryStageSelection("act-3")
        assertEquals(3, profile.visibleFacts(third.storyStage, third.visibleStoryStages()).size)
        val rewind = third.withStoryStageSelection("act-1")
        assertEquals(listOf("act-1"), rewind.unlockedStoryStages)
        assertEquals(listOf("a"),
            profile.visibleFacts(rewind.storyStage, rewind.visibleStoryStages()).map(CharacterFact::id))
        val reset = rewind.withStoryStageSelection("")
        assertTrue(reset.visibleStoryStages().isEmpty())
        assertTrue(profile.visibleFacts(reset.storyStage, reset.visibleStoryStages()).isEmpty())
    }

    @Test fun runtimeProjectionUsesOnlyExplicitVisitedStagePath() {
        val profile = PersonaProfile(name = "叶澜", coreIdentity = "旅行者", facts = listOf(
            CharacterFact("a", CharacterFactCategories.BIOGRAPHY,
                "第一幕在旧桥见过阿宁", temporalScope = "opening", provenance = CharacterFactProvenance.CANON),
            CharacterFact("b", CharacterFactCategories.BIOGRAPHY,
                "第二幕在雨夜找到线索", temporalScope = "later", provenance = CharacterFactProvenance.CANON),
        ))
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val second = ChatContextState().withStoryStageSelection("opening").withStoryStageSelection("later")
        val output = projector.project(profile, ChatCharacterState(), second, "阿宁与你有哪些共同经历？", null)
        assertTrue(output.stablePrompt.contains("旧桥见过阿宁"))
        assertTrue(output.stablePrompt.contains("雨夜找到线索"))
        val rewind = projector.project(profile, ChatCharacterState(),
            second.withStoryStageSelection("opening"), "阿宁与你有哪些共同经历？", null)
        assertFalse((rewind.stablePrompt + rewind.dynamicPrompt).contains("雨夜找到线索"))
    }

    @Test fun storyStageSelectionNeverDerivesOrderFromStageName() {
        val context = ChatContextState().withStoryStageSelection("act-3")
            .withStoryStageSelection("act-1")
        assertEquals(listOf("act-3", "act-1"), context.unlockedStoryStages)
        assertTrue(context.visibleStoryStages().contains("act-3"))
        val rewind = context.withStoryStageSelection("act-3")
        assertEquals(listOf("act-3"), rewind.unlockedStoryStages)
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
        val merged = mergeGeneratedCharacterFacts(listOf(user), listOf(generated))
        assertEquals(user, merged.first())
        assertEquals(CharacterFactProvenance.INFERRED, merged.last().provenance)
        assertEquals(2, merged.size)
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
