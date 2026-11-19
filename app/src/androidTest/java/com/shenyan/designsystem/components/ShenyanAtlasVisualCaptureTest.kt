package com.shenyan.designsystem.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Genuine Android Compose frames for all ten atlases in both supported modes. */
class ShenyanAtlasVisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ShenyanAtlasActivity>()

    @Test fun captureTenAtlasesAcrossElegantWhiteAndMatteBlack() {
        val dir = File(requireNotNull(compose.activity.getExternalFilesDir(null)),
            "ui-audit/atlas").apply { mkdirs() }
        val sections = listOf(
            "01 基础组件", "02 弹窗", "03 底部面板", "04 手势",
            "05 反馈", "06 加载", "07 引导", "08 表单", "09 媒体", "10 对话",
        )
        for ((modeLabel, modeId) in listOf("典雅白" to "light", "磨砂黑" to "matte")) {
            compose.onNodeWithText(modeLabel).performClick()
            for ((index, name) in sections.withIndex()) {
                val tab = compose.onNodeWithText(name)
                tab.performScrollTo()
                tab.performClick()
                compose.onNodeWithTag("sy-atlas-scroll").assertExists()
                compose.waitForIdle()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val target = File(dir,
                    "${modeId}-${(index + 1).toString().padStart(2, '0')}.png")
                val saved = FileOutputStream(target).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                assertTrue("PNG must be captured: ${target.name}",
                    saved && target.length() > 5000)
            }
        }
        assertEquals("All 20 native reference panels must exist",
            20, dir.listFiles()?.count { it.extension == "png" })
    }
}
