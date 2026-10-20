package com.labteto.dshmobile.ui.screens.local

import android.graphics.Bitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.shenyan.designsystem.foundation.LocalSyColors
import org.junit.Rule
import org.junit.Test

class LocalReplyCompositionVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowReplyEmbedsMediaAtFullReadingWidthWithoutDuplicateResultHeader() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp).background(LocalSyColors.current.canvas)) {
                    Text("报告正文：已经整理好预览图。")
                    MediaMessageCard(title = "", subtitle = "", inline = true,
                        modifier = Modifier.testTag("inline-media")) {
                        Text("预览图位置")
                    }
                    Text("报告结论：下一步验证移动端布局。")
                }
            }
        }
        compose.onNodeWithText("报告正文：已经整理好预览图。").assertIsDisplayed()
        compose.onNodeWithText("预览图位置").assertIsDisplayed()
        compose.onNodeWithText("报告结论：下一步验证移动端布局。").assertIsDisplayed()
        compose.onNodeWithTag("inline-media").assertWidthIsEqualTo(320.dp)
        saveScreenshot("inline-reply-media-light-320dp")
    }

    @Test fun imageGalleryKeepsFourImagesVisibleOnNarrowScreen() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp).background(LocalSyColors.current.canvas)) {
                    ReplyMediaGrid(4) { index ->
                        Column(Modifier.fillMaxWidth().testTag("gallery-image:$index")) {
                            Text("生成图片 ${index + 1}")
                        }
                    }
                }
            }
        }
        (0..3).forEach { index ->
            compose.onNodeWithTag("gallery-image:$index").assertIsDisplayed()
        }
        val first = compose.onNodeWithTag("gallery-image:0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("gallery-image:1").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(second.left >= first.right)
    }

    @Test fun threeImageGalleryHasBalancedLastRowInDarkTheme() {
        compose.setContent {
            DshTheme(preference = ThemePreference.DARK) {
                Column(Modifier.width(320.dp).background(LocalSyColors.current.canvas)) {
                    ReplyMediaGrid(3) { index ->
                        Text("图片 ${index + 1}", modifier = Modifier.fillMaxWidth()
                            .testTag("gallery-dark:$index"))
                    }
                }
            }
        }
        (0..2).forEach { compose.onNodeWithTag("gallery-dark:$it").assertIsDisplayed() }
        val first = compose.onNodeWithTag("gallery-dark:0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("gallery-dark:2").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(first.left, last.left, 1f)
        saveScreenshot("inline-image-gallery-dark-320dp")
    }

    @Test fun inlineFileHasOneMenuAndOwnerControlsOpening() {
        var opened = false
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                InlineReplyArtifactActions(file = null,
                    onOpen = { opened = true }, onError = {})
            }
        }
        compose.onNodeWithTag("inlineArtifactActions").assertIsDisplayed().performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(
            com.labteto.dshmobile.R.string.media_chat_view_entire_file)).performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(opened) }
    }

    @Test fun inlineAudioUsesOneContextualActionEntrance() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                InlineReplyMediaActions(File("/no-such-audio.mp3"), "audio/mpeg") { }
            }
        }
        compose.onNodeWithTag("inlineMediaActions").assertIsDisplayed().performClick()
    }

    @Test fun standaloneMediaPreservesTheOriginalNarrowerContentLayoutInDarkMode() {
        compose.setContent {
            DshTheme(preference = ThemePreference.DARK) {
                Column(Modifier.width(320.dp)) {
                    MediaMessageCard(title = "视频处理中", subtitle = "创作记录",
                        modifier = Modifier.testTag("standalone-media")) {
                        Text("等待提供商处理")
                    }
                }
            }
        }
        compose.onNodeWithText("视频处理中").assertIsDisplayed()
        compose.onNodeWithText("创作记录").assertIsDisplayed()
        compose.onNodeWithTag("standalone-media").assertWidthIsEqualTo(288.dp)
        saveScreenshot("legacy-standalone-media-dark-320dp")
    }
    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "work-timeline-review")
        check(directory.exists() || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

}
