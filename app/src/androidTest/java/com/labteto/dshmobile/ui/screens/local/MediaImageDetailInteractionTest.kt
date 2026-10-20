package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.labteto.dshmobile.ui.theme.DshTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The image's only save/share entrance is the top-right anchored overflow. */
class MediaImageDetailInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullscreenImageUsesTopRightActionsWithoutLegacyButtons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "media-preview-interaction.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        try {
            compose.setContent {
                DshTheme {
                    MediaImageDetailDialog(file, remoteUrl = null, onDismiss = {})
                }
            }
            compose.onNodeWithTag("syFullscreenMediaOverlay").assertIsDisplayed()
            compose.onNodeWithText("保存").assertDoesNotExist()
            compose.onNodeWithText("分享").assertDoesNotExist()
            compose.onNodeWithText("还原大小").assertDoesNotExist()
            compose.onNodeWithText("关闭").assertDoesNotExist()

            compose.onNodeWithTag("mediaImageActions").assertIsDisplayed().performClick()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("分享").assertIsDisplayed()
         } finally {
            file.delete()
        }
    }

    @Test fun tappingPictureDoesNotDismissButBackgroundDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "media-preview-tap-test.png")
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        var dismissed = false
        try {
            compose.setContent {
                DshTheme {
                    MediaImageDetailDialog(file, remoteUrl = null, onDismiss = { dismissed = true })
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("mediaImageContent").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mediaImageContent").performClick()
            compose.runOnIdle { assertTrue(!dismissed) }
            compose.onNodeWithTag("syFullscreenMediaOverlay")
                .performTouchInput { click(Offset(4f, 4f)) }
            compose.runOnIdle { assertTrue(dismissed) }
        } finally {
            file.delete()
        }
    }

    @Test fun remoteOnlyImageShowsShareWithoutPretendingLocalSave() {
        compose.setContent {
            DshTheme {
                MediaImageDetailDialog(file = null, remoteUrl = "https://example.com:8443/image.png", onDismiss = {})
            }
        }
        compose.onNodeWithTag("mediaImageActions").performClick()
        compose.onNodeWithText("分享链接").assertIsDisplayed()
        compose.onNodeWithText("保存").assertDoesNotExist()
    }
}
