package com.shenyan.designsystem.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.ShenyanTheme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs on Android 36 hardware/emulator, where the AGSL program actually compiles. */
class SyLiquidRefractionAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shaderProgramCompilesAndAcceptsEveryRuntimeUniform() {
        val shader = RuntimeShader(SyLiquidShader.PROGRAM)
        shader.setFloatUniform("resolution", 200f, 120f)
        shader.setFloatUniform("corners", 20f, 20f, 20f, 20f)
        shader.setFloatUniform("touchPoint", 100f, 60f)
        shader.setFloatUniform("touchAmount", 0.7f)
        shader.setFloatUniform("bendPx", 14f)
        shader.setFloatUniform("chromaPx", 1f)
        shader.setFloatUniform("edgePx", 22f)
        shader.setFloatUniform("reflection", 0.8f)
        shader.setFloatUniform("blurPx", 2.4f)
        shader.setFloatUniform("clarity", 0.66f)
        shader.setFloatUniform("drag", 3f, -1f)
        shader.setColorUniform("glassTint", Color(0xC2FFFFFF).toArgb())
        assertTrue(RenderEffect.createRuntimeShaderEffect(shader, SyLiquidShader.INPUT) != null)
    }

    @Test fun sharedGpuSceneRefractsRealSiblingPixelsWithoutDuplicatingContent() {
        var underlay by mutableStateOf(Color(0xFFB73A2D))
        compose.setContent {
            ShenyanTheme {
                SyLiveGlassScene(
                    modifier = Modifier.size(width = 220.dp, height = 130.dp),
                    underlay = { Box(Modifier.fillMaxSize().background(underlay)) },
                    glass = {
                        SyMaterialSurface(
                            modifier = Modifier.fillMaxSize().testTag("real-sibling-lens"),
                            tier = SyMaterialTier.L2_BAR,
                        ) {}
                    },
                )
            }
        }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("real-sibling-lens").captureToImage()
        val firstColor = first.toPixelMap()[first.width / 2, first.height / 2]
        compose.runOnIdle { underlay = Color(0xFF317A4B) }
        compose.waitForIdle()
        val second = compose.onNodeWithTag("real-sibling-lens").captureToImage()
        val secondColor = second.toPixelMap()[second.width / 2, second.height / 2]
        assertNotEquals("A separate underlying GPU layer must remain live", firstColor, secondColor)
    }

    @Test fun gpuRefractionTransformsActualEdgePixels() {
        var showLens by mutableStateOf(false)
        val stripes: @androidx.compose.runtime.Composable () -> Unit = {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stripeWidth = 3.dp.toPx()
                var x = 0f
                var index = 0
                while (x < size.width) {
                    drawRect(
                        if (index % 2 == 0) Color.Black else Color.White,
                        topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(stripeWidth, size.height),
                    )
                    x += stripeWidth
                    index++
                }
            }
        }
        compose.setContent {
            Box(Modifier.size(width = 220.dp, height = 126.dp)
                .background(Color.White).testTag("optical-pixel-pattern")) {
                if (showLens) {
                    SyLiquidRefractionLayer(
                        modifier = Modifier.fillMaxSize(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        fill = Color.Transparent,
                        tier = SyMaterialTier.L2_BAR,
                        reflection = 0f,
                        touchPoint = androidx.compose.ui.geometry.Offset.Zero,
                        pressure = 0f,
                        backdrop = stripes,
                    )
                } else stripes()
            }
        }
        compose.waitForIdle()
        val plain = compose.onNodeWithTag("optical-pixel-pattern")
            .captureToImage().toPixelMap()
        compose.runOnIdle { showLens = true }
        compose.waitForIdle()
        val shaped = compose.onNodeWithTag("optical-pixel-pattern")
            .captureToImage().toPixelMap()
        val mid = shaped.height / 2
        // The interior still contains BOTH black and white stripes; a blank
        // effect buffer or white material fill cannot satisfy this assertion.
        val interior = (shaped.width / 3 .. shaped.width * 2 / 3)
            .map { x -> shaped[x, mid].red }
        assertTrue("The refracted source must retain actual high-frequency glyph-like pixels",
            interior.minOrNull()!! < .45f && interior.maxOrNull()!! > .65f)
        val changedAtRim = (3 until minOf(shaped.width / 8, 35))
            .count { x -> plain[x, mid] != shaped[x, mid] }
        assertTrue("The optical rim must truly move source pixels", changedAtRim > 3)
    }

    @Test fun lensSamplesLiveBackdropOnItsOwnGpuLayer() {
        var source by mutableStateOf(Color(0xFFCC3322))
        compose.setContent {
            ShenyanTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    SyMaterialSurface(
                        modifier = Modifier.size(width = 220.dp, height = 126.dp)
                            .testTag("live-liquid-lens"),
                        tier = SyMaterialTier.L2_BAR,
                        backdrop = { Box(Modifier.fillMaxSize().background(source)) },
                    ) {}
                }
            }
        }
        val frameOne = compose.onNodeWithTag("live-liquid-lens").captureToImage()
        val pixelOne = frameOne.toPixelMap()[frameOne.width / 2, frameOne.height / 2]
        compose.runOnIdle { source = Color(0xFF168E51) }
        compose.waitForIdle()
        val frameTwo = compose.onNodeWithTag("live-liquid-lens").captureToImage()
        val pixelTwo = frameTwo.toPixelMap()[frameTwo.width / 2, frameTwo.height / 2]
        assertNotEquals("Live underlay must update through the AGSL compositor", pixelOne, pixelTwo)
    }
}
