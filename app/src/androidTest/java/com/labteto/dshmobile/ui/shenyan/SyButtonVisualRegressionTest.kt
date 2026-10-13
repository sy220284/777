package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.shenyan.designsystem.foundation.ShenyanPalettes
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyButtonVisualRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun infoIsVisiblyEmphasizedRatherThanRenderedAsGhost() {
        compose.setContent { DshTheme(preference = ThemePreference.LIGHT) {
            Column {
                // captureToImage captures this node's own drawing. A transparent ghost
                // needs a painted backing in the capture, not an ancestor outside it.
                Box(Modifier.background(ShenyanPalettes.light.canvas).testTag("infoSurface")) {
                    SyButton("Selected action", {}, modifier = Modifier.testTag("info"),
                        variant = SyButtonVariant.Info)
                }
                Box(Modifier.background(ShenyanPalettes.light.canvas).testTag("ghostSurface")) {
                    SyButton("Unselected action", {}, modifier = Modifier.testTag("ghost"),
                        variant = SyButtonVariant.Ghost)
                }
            }
        } }
        compose.waitForIdle()
        val info = compose.onNodeWithTag("infoSurface").captureToImage().toPixelMap()
        val ghost = compose.onNodeWithTag("ghostSurface").captureToImage().toPixelMap()
        val infoBackground = info[info.width / 2, 8]
        val ghostBackground = ghost[ghost.width / 2, 8]
        val selected = ShenyanPalettes.light.selected
        val canvas = ShenyanPalettes.light.canvas
        assertEquals("Info uses the selected palette", selected.red, infoBackground.red, 0.02f)
        assertEquals("Info uses the selected palette", selected.green, infoBackground.green, 0.02f)
        assertEquals("Info uses the selected palette", selected.blue, infoBackground.blue, 0.02f)
        assertEquals("Ghost preserves the backing", canvas.red, ghostBackground.red, 0.02f)
        assertEquals("Ghost preserves the backing", canvas.green, ghostBackground.green, 0.02f)
        assertEquals("Ghost preserves the backing", canvas.blue, ghostBackground.blue, 0.02f)
        assertTrue("Info must have a distinct selected background",
            infoBackground.blue + 0.07f < ghostBackground.blue)
    }

    @Test fun smallIsCompactWithoutLosingClickOrMinimumTouchBounds() {
        var count = 0
        compose.setContent { DshTheme {
            Column {
                SyButton("Compact secondary action", { count++ },
                    modifier = Modifier.testTag("small"), size = SyButtonSize.Small)
                SyButton("Compact secondary action", { count++ },
                    modifier = Modifier.testTag("normal"), size = SyButtonSize.Normal)
                SyButton("Disabled action", { count++ },
                    modifier = Modifier.testTag("disabled"), enabled = false,
                    size = SyButtonSize.Small)
            }
        } }
        compose.onNodeWithTag("small").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("normal").assertHeightIsAtLeast(48.dp)
        val smallWidth = compose.onNodeWithTag("small").fetchSemanticsNode().boundsInRoot.width
        val normalWidth = compose.onNodeWithTag("normal").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Small should use denser content spacing", smallWidth < normalWidth)
        compose.onNodeWithTag("small").performClick()
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, count) }
    }
}
