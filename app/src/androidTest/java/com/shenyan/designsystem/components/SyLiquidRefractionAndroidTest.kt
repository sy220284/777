package com.shenyan.designsystem.components

import org.junit.Assert.assertEquals
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
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
                        // An opaque sibling blocks the ordinary underlay. Only the
                        // REAL sampled lens can reveal a live colour change.
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            SyMaterialSurface(
                                modifier = Modifier.fillMaxSize().testTag("real-sibling-lens"),
                                tier = SyMaterialTier.L2_BAR,
                            ) {}
                        }
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
        var pressure by mutableStateOf(0f)
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
                        touchPoint = androidx.compose.ui.geometry.Offset(6f, with(androidx.compose.ui.platform.LocalDensity.current) { 63.dp.toPx() }),
                        pressure = pressure,
                        drag = androidx.compose.ui.geometry.Offset(11f, 0f),
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
        compose.runOnIdle { pressure = 1f }
        val held = compose.onNodeWithTag("optical-pixel-pattern").captureToImage().toPixelMap()
        assertTrue("A held drag must change the rim while its layout remains still",
            (2 until minOf(held.width / 6, 44)).any { x -> held[x, mid] != shaped[x, mid] })
        compose.runOnIdle { pressure = 0f }
        val released = compose.onNodeWithTag("optical-pixel-pattern").captureToImage().toPixelMap()
        assertEquals(shaped[8, mid], released[8, mid])
    }

    @Test fun clearLensHasNoDarkRimOrOuterShadowOnAUniformBackdrop() {
        val background = Color(0xFFE0EBF6)
        var showLens by mutableStateOf(false)
        compose.setContent {
            ShenyanTheme {
                Box(Modifier.size(220.dp, 150.dp).background(background).testTag("clear-lens-region")) {
                    if (showLens) {
                        SyMaterialSurface(
                            modifier = Modifier.offset(24.dp, 24.dp).size(172.dp, 100.dp).testTag("flat-glass-lens"),
                            tier = SyMaterialTier.L4_OVERLAY,
                            backdrop = { Box(Modifier.fillMaxSize().background(background)) },
                        ) {}
                    }
                }
            }
        }
        val region = compose.onNodeWithTag("clear-lens-region")
        val plain = region.captureToImage().toPixelMap()
        compose.runOnIdle { showLens = true }
        fun assertCleanEdge(lens: PixelMap, state: String) {
            val y = lens.height / 2
            assertEquals(plain[lens.width / 16, y], lens[lens.width / 16, y])
            for (x in lens.width / 12 until lens.width * 11 / 12) {
                assertTrue("$state: A flat source must not darken at column $x",
                    lens[x, y].red >= plain[x, y].red - .012f &&
                        lens[x, y].green >= plain[x, y].green - .012f &&
                        lens[x, y].blue >= plain[x, y].blue - .012f)
            }
        }
        assertCleanEdge(region.captureToImage().toPixelMap(), "Rest")
        val gesture = compose.onNodeWithTag("flat-glass-lens")
        gesture.performTouchInput {
            down(Offset(width * .10f, height * .45f))
            moveBy(Offset(width * .06f, height * .10f))
        }
        try {
            assertCleanEdge(region.captureToImage().toPixelMap(), "Held drag")
        } finally {
            gesture.performTouchInput { up() }
        }
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
