package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.ui.theme.AppBackgroundState
import com.shenyan.designsystem.foundation.ShenyanPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6ComposerSurfaceTest {
    @Test fun composerUsesOriginalGlassTierInsteadOfOpaqueCanvasCompositing() {
        val empty = AppBackgroundState()
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark).forEach { palette ->
            val result = v6ConversationComposerSurface(empty, palette)
            assertEquals(palette.materialL2, result)
            assertTrue(result.alpha < 1f)
        }
    }

    @Test fun matteComposerRemainsOpaqueAndUsesItsOwnMaterial() {
        val matte = ShenyanPalettes.matte
        assertEquals(matte.materialL2, v6ConversationComposerSurface(AppBackgroundState(), matte))
        assertEquals(1f, matte.materialL2.alpha)
    }
}
