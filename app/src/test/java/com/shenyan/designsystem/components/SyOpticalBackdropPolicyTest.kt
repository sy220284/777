package com.shenyan.designsystem.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-independent contract. RenderEffect pixels require a real Android screenshot test. */
class SyOpticalBackdropPolicyTest {
    @Test fun compactGlassEnabledOnApi33() {
        assertTrue(sySupportsOpticalRefraction(
            sdk = 33, transparent = true, hasScene = true, enabled = true,
            surfaceWidth = 360, surfaceHeight = 180,
        ))
    }

    @Test fun noOpticalPassForLegacyOrSolidOrNoScene() {
        val base = { sdk: Int, transparent: Boolean, scene: Boolean, on: Boolean ->
            sySupportsOpticalRefraction(sdk, transparent, scene, on, 340, 180)
        }
        assertFalse(base(32, true, true, true))
        assertFalse(base(33, false, true, true))
        assertFalse(base(33, true, false, true))
        assertFalse(base(33, true, true, false))
    }

    @Test fun rejectOffscreenFullHeightAndEmptyBounds() {
        assertFalse(sySupportsOpticalRefraction(35, true, true, true, 2000, 2000))
        assertFalse(sySupportsOpticalRefraction(35, true, true, true, 0, 100))
        assertFalse(sySupportsOpticalRefraction(35, true, true, true, 320, -1))
        assertTrue(sySupportsOpticalRefraction(35, true, true, true, 1000, 1300))
    }

    @Test fun shaderSamplesOnlySceneAndAlwaysClampsCoordinates() {
        assertTrue(SY_OPTICAL_REFRACTION_AGSL.contains("uniform shader scene"))
        assertTrue(SY_OPTICAL_REFRACTION_AGSL.contains("scene.eval(sampleAt)"))
        assertTrue(SY_OPTICAL_REFRACTION_AGSL.contains("smoothstep"))
        assertTrue(SY_OPTICAL_REFRACTION_AGSL.contains("clamp("))
    }
}
