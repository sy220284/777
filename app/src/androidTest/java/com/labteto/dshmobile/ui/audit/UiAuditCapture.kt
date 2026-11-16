package com.labteto.dshmobile.ui.audit

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Real rendered pixels kept with device results, including a failing layout assertion. */
fun captureUiAudit(compose: ComposeContentTestRule, name: String, tag: String? = null) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "ui-audit").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { stream ->
        val bitmap = if (tag == null) {
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } else compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } finally {
            if (tag == null) bitmap.recycle()
        }
    }
}

/** Keep the lower portions too: a first-viewport screenshot cannot audit a long settings page. */
fun captureScrollableUiAudit(compose: ComposeContentTestRule, name: String, tag: String? = null) {
    captureUiAudit(compose, name, tag)
    val scrollables = compose.onAllNodes(hasScrollAction())
    val count = scrollables.fetchSemanticsNodes().size
    for (index in 0 until count) {
        val node = scrollables[index]
        var page = 0
        while (true) {
            val before = node.fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: break
            val start = before.value()
            if (start >= before.maxValue()) break
            val viewport = node.fetchSemanticsNode().boundsInRoot.height
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, viewport * 0.8f) }
            compose.waitForIdle()
            val after = node.fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: break
            if (after.value() <= start) break
            captureUiAudit(compose, "$name-scroll$index-${++page}", tag)
        }
    }
}
