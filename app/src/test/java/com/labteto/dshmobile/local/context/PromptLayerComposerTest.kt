package com.labteto.dshmobile.local.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PromptLayerComposerTest {
    private val composer = PromptLayerComposer()

    @Test
    fun keepsStablePrefixDeterministicAndDynamicMaterialAtTail() {
        val first = composer.compose(
            listOf(
                PromptLayer("recall", 600, PromptLayerStability.DYNAMIC, "动态记忆"),
                PromptLayer("rules", 200, PromptLayerStability.STABLE, "长期规则"),
                PromptLayer("project", 300, PromptLayerStability.STABLE, "项目规则"),
            ),
        )
        val reordered = composer.compose(
            listOf(
                PromptLayer("project", 300, PromptLayerStability.STABLE, "项目规则"),
                PromptLayer("rules", 200, PromptLayerStability.STABLE, "长期规则"),
                PromptLayer("recall", 600, PromptLayerStability.DYNAMIC, "另一段动态记忆"),
            ),
        )

        assertEquals("长期规则\n\n项目规则", first.stable)
        assertEquals("动态记忆", first.dynamic)
        assertEquals(first.stableFingerprint, reordered.stableFingerprint)
        assertNotEquals(first.dynamic, reordered.dynamic)
    }
}
