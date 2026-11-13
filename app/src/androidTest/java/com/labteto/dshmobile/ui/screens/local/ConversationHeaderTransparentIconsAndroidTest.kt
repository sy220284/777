package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.theme.DshTheme
import com.shenyan.designsystem.components.SyIconButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises real Android rendering, not just the transparent default parameter. */
class ConversationHeaderTransparentIconsAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navAndNewSessionHaveNoTileFillOrShadowAndRemainClickable() {
        val paper = Color(0xFFCEB7DA)
        var opens = 0
        var creates = 0
        compose.setContent {
            DshTheme {
                Row(Modifier.background(paper)) {
                    Box(Modifier.size(64.dp).testTag("transparent-nav")) {
                        SyIconButton(
                            icon = FeatherIcons.Menu,
                            contentDescription = "打开侧栏",
                            onClick = { opens++ },
                            bare = true,
                        )
                    }
                    Box(Modifier.size(64.dp).testTag("transparent-new-session")) {
                        SyIconButton(
                            icon = SyEntryIcons.NewSession,
                            contentDescription = "新建会话",
                            onClick = { creates++ },
                            bare = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        listOf("transparent-nav", "transparent-new-session").forEach { tag ->
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            val untouchedPaper = pixels[4, 4].toArgb()
            // (13,13) is within the former 32dp entry tile but outside either glyph.
            assertEquals("$tag must have no icon tile", untouchedPaper, pixels[13, 13].toArgb())
            // A shadow/elevation must not paint beyond the 48dp touch surface.
            assertEquals("$tag must have no elevation", untouchedPaper, pixels[53, 24].toArgb())
        }
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        compose.onNodeWithContentDescription("新建会话").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, creates)
        }
    }
}
