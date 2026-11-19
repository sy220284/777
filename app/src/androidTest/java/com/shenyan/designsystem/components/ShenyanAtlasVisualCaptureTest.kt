package com.shenyan.designsystem.components

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.labteto.dshmobile.ui.theme.AccentPalettes
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Reproducible native frames: 10 functional atlases x 2 modes, and
 * all nine palette choices x 2 modes. Pixel artifacts are checked for visual
 * correspondence to the ten source sheets, never treated as automatic approval.
 */
class ShenyanAtlasVisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ShenyanAtlasActivity>()

    @Test fun captureTenAtlasesAndNineAccentFamiliesInBothModes() {
        val dir = File(requireNotNull(compose.activity.getExternalFilesDir(null)),
            "ui-audit/atlas").apply { mkdirs() }
        dir.listFiles()?.filter { it.extension == "png" }?.forEach(File::delete)
        val sections = listOf(
            "01 基础组件", "02 弹窗", "03 底部面板", "04 手势",
            "05 反馈", "06 加载", "07 引导", "08 表单", "09 媒体", "10 对话",
        )
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val target = File(dir, name + ".png")
            val saved = FileOutputStream(target).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("Native pixel artifact must exist: " + name,
                saved && target.length() > 5000)
        }
        for (modeId in listOf("light", "matte")) {
            compose.onNodeWithTag("sy-atlas-mode-" + modeId).performClick()
            for ((index, name) in sections.withIndex()) {
                compose.onNodeWithText(name).performScrollTo().performClick()
                compose.onNodeWithTag("sy-atlas-scroll").assertExists()
                capture(modeId + "-" + (index + 1).toString().padStart(2, '0'))
            }
            compose.onNodeWithText(sections.first()).performScrollTo().performClick()
            for (palette in AccentPalettes.ALL) {
                compose.onNodeWithTag("sy-atlas-palette-" + palette.key)
                    .performScrollTo().performClick()
                capture("palette-" + modeId + "-" + palette.key)
            }
        }
        assertEquals("All 20 references plus 18 native palette samples must exist",
            20 + AccentPalettes.ALL.size * 2,
            dir.listFiles()?.count { it.extension == "png" })
    }
}
