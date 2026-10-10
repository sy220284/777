package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-level deterministic smoke coverage across the bundled character gallery.
 * These assertions do not stand in for real model evaluations or device instrumentation.
 */
class ChatModeVNextPresetSmokeTest {
    private val projector = CharacterRuntimeProjector(
        ChatRelationshipEngine(), CharacterLoreEngine(),
    )

    @Test
    fun allTwentyOnePresetsKeepDistinctV4IdentityAcrossDirectAndGroupStyleProjection() {
        val presets = PersonaPresetCatalog.presets
        assertEquals(21, presets.size)
        assertEquals(presets.size, presets.map { it.id }.distinct().size)

        val rendered = presets.map { preset ->
            val persona = preset.persona
            assertTrue("V4 identity absent for ${preset.id}", persona.coreIdentity.isNotBlank())
            assertTrue("V4 facts absent for ${preset.id}", persona.facts.isNotEmpty())
            val solo = projector.project(
                persona, ChatCharacterState(), ChatContextState(),
                "你周末有什么安排？", null,
            )
            val shared = projector.project(
                persona, ChatCharacterState(), ChatContextState(),
                "你周末有什么安排？", "大家正在商量周末的活动。",
            )
            assertTrue(solo.stablePrompt.contains(persona.name))
            assertEquals(solo.stablePrompt, shared.stablePrompt)
            assertTrue(shared.dynamicPrompt.contains("大家正在商量周末的活动。"))
            assertTrue(solo.stablePrompt.length <= 8_000)
            solo.stablePrompt
        }
        assertEquals(presets.size, rendered.distinct().size)
    }

    @Test
    fun futureStoryFactsStayHiddenUntilSelectedAndRevokeOnExplicitRewind() {
        val original = PersonaPresetCatalog.presets.first().persona
        val persona = original.copy(facts = original.facts + listOf(
            CharacterFact(
                id = "vnext-known-first",
                category = CharacterFactCategories.BIOGRAPHY,
                content = "第一幕在书店收到了手写信",
                temporalScope = "opening",
                provenance = CharacterFactProvenance.CANON,
            ),
            CharacterFact(
                id = "vnext-future-secret",
                category = CharacterFactCategories.BIOGRAPHY,
                content = "终章绝密：手写信来自未来的同伴",
                temporalScope = "reveal",
                provenance = CharacterFactProvenance.CANON,
            ),
        ))
        fun rendered(context: ChatContextState): String {
            val view = projector.project(persona, ChatCharacterState(), context,
                "当时收到的手写信有什么秘密？", null)
            return view.stablePrompt + view.dynamicPrompt
        }

        val base = rendered(ChatContextState())
        assertFalse(base.contains("第一幕在书店收到了手写信"))
        assertFalse(base.contains("手写信来自未来的同伴"))
        val opening = ChatContextState().withStoryStageSelection("opening")
        assertTrue(rendered(opening).contains("第一幕在书店收到了手写信"))
        assertFalse(rendered(opening).contains("手写信来自未来的同伴"))
        val later = opening.withStoryStageSelection("reveal")
        assertTrue(rendered(later).contains("第一幕在书店收到了手写信"))
        assertTrue(rendered(later).contains("手写信来自未来的同伴"))
        assertFalse(rendered(later.withStoryStageSelection("opening"))
            .contains("手写信来自未来的同伴"))
    }
}
