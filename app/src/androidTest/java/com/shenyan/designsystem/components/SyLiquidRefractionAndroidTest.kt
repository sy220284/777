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
        shader.setColorUniform("glassTint", Color(0xC2FFFFFF).toArgb())
        assertTrue(RenderEffect.createRuntimeShaderEffect(shader, SyLiquidShader.INPUT) != null)
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
