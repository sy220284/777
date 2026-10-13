package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyButtonVisualRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun infoIsVisiblyEmphasizedRatherThanRenderedAsGhost() {
        compose.setContent { DshTheme(preference = ThemePreference.LIGHT) {
            Column {
                SyButton("Selected action", {}, modifier = Modifier.testTag("info"),
                    variant = SyButtonVariant.Info)
                SyButton("Unselected action", {}, modifier = Modifier.testTag("ghost"),
                    variant = SyButtonVariant.Ghost)
            }
        } }
        compose.waitForIdle()
        val info = compose.onNodeWithTag("info").captureToImage().toPixelMap()
        val ghost = compose.onNodeWithTag("ghost").captureToImage().toPixelMap()
        val infoBackground = info[info.width / 2, 8]
        val ghostBackground = ghost[ghost.width / 2, 8]
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
