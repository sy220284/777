package com.labteto.dshmobile.ui.audit

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Real rendered pixels kept with device results, including a failing layout assertion. */
fun captureUiAudit(compose: ComposeContentTestRule, name: String, tag: String? = null) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { stream ->
        val node = if (tag == null) compose.onRoot(useUnmergedTree = true) else compose.onNodeWithTag(tag, useUnmergedTree = true)
        check(node.captureToImage()
            .asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
    }
}
