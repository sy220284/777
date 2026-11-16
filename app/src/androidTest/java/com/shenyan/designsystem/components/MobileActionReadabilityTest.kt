package com.shenyan.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import com.labteto.dshmobile.ui.audit.captureUiAudit
import org.junit.Rule
import org.junit.Test

/** Accessibility labels must stay visually readable, including shared two-column actions. */
class MobileActionReadabilityTest {
    @get:Rule val compose = createComposeRule()

    @After fun keepRenderedLayoutEvidence() = captureUiAudit(compose, "long-action-200pct")

    @Test fun descriptiveActionWrapsCompletelyAtLargeFontAndStillSubmitsOnce() {
        val label = "继续完成账号授权与连接检查"
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DshTheme {
                    Box(Modifier.width(140.dp)) {
                        SyButton(label, onClick = { clicks++ })
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Expected real composed text layout", layouts.isNotEmpty())
        assertFalse("The action's purpose must remain visible at 200% text size", layouts.single().hasVisualOverflow)
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
