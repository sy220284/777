package com.shenyan.designsystem.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Pixel evidence for the API-33+ optical pass, separate from policy-only JVM tests. */
class SyOpticalBackdropRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun alignedBackgroundPixelsShiftNearEdgesWhenOpticalPassEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below API 33, the deliberate no-shader fallback is the behavior under test.
            org.junit.Assert.assertFalse(sySupportsOpticalRefraction(
                sdk = Build.VERSION.SDK_INT, transparent = true, hasScene = true,
                enabled = true, surfaceWidth = 240, surfaceHeight = 110,
            ))
            return
        }
        val enabled = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(240.dp, 110.dp).testTag("optical-fixture")) {
                SyOpticalBackdrop(Modifier.fillMaxSize(), enabled = enabled.value) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stripe = 6.dp.toPx()
                        repeat((size.width / stripe).toInt() + 2) { i ->
                            drawRect(
                                color = if (i % 2 == 0) Color.White else Color.Black,
                                topLeft = Offset(i * stripe, 0f),
                                size = Size(stripe, size.height),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val baseline = compose.onNodeWithTag("optical-fixture").captureToImage().toPixelMap()
        compose.runOnIdle { enabled.value = true }
        compose.waitForIdle()
        val refracted = compose.onNodeWithTag("optical-fixture").captureToImage().toPixelMap()
        assertTrue(baseline.width == refracted.width && baseline.height == refracted.height)
        val y = baseline.height / 2
        var changed = 0
        for (x in 2 until baseline.width / 6) {
            val before = baseline[x, y]
            val after = refracted[x, y]
            if (abs(before.red - after.red) + abs(before.green - after.green) +
                abs(before.blue - after.blue) > 0.15f) changed++
        }
        assertTrue("AGSL optical pass must produce visible edge pixel displacement", changed >= 2)
    }
}
