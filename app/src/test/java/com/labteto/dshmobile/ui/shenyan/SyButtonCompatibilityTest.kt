package com.labteto.dshmobile.ui.shenyan

import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.components.SyButtonStyle as NativeButtonStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyButtonCompatibilityTest {
    @Test fun infoIsDistinctFromGhostAndSelectedActionsAreVisible() {
        val info = resolveSyButtonStyle(SyButtonStyle.PRIMARY, SyButtonVariant.Info)
        val ghost = resolveSyButtonStyle(SyButtonStyle.PRIMARY, SyButtonVariant.Ghost)
        assertEquals(NativeButtonStyle.INFO, info)
        assertEquals(NativeButtonStyle.GHOST, ghost)
        assertNotEquals(ghost, info)
    }

    @Test fun explicitVariantOverridesStyleWithoutBreakingLegacyStyle() {
        assertEquals(NativeButtonStyle.PRIMARY,
            resolveSyButtonStyle(SyButtonStyle.DANGER, SyButtonVariant.Primary))
        assertEquals(NativeButtonStyle.DANGER,
            resolveSyButtonStyle(SyButtonStyle.DANGER, null))
        assertEquals(NativeButtonStyle.OUTLINE,
            resolveSyButtonStyle(SyButtonStyle.GHOST, SyButtonVariant.Outline))
    }

    @Test fun compactDoesNotShrinkTouchTargetAndLargePreservesExplicitHeight() {
        assertEquals(SyDimensions.minimumTouch, resolveSyButtonHeight(SyButtonSize.Small, 32.dp))
        assertEquals(SyDimensions.minimumTouch, resolveSyButtonHeight(SyButtonSize.Normal, 32.dp))
        assertEquals(52.dp, resolveSyButtonHeight(SyButtonSize.Large, 48.dp))
        assertEquals(64.dp, resolveSyButtonHeight(SyButtonSize.Large, 64.dp))
        assertEquals(60.dp, resolveSyButtonHeight(SyButtonSize.Small, 60.dp))
    }
}
