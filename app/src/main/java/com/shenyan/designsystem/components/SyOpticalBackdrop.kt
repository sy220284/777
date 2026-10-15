package com.shenyan.designsystem.components

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * V6.4 optical refraction pass. IMPORTANT: the input is the *aligned scene backdrop* only.
 * This cannot sample arbitrary already-drawn siblings; text and controls are never distorted.
 * API < 33 and shader errors fall back to the same readable blurred backdrop.
 */
internal const val SY_OPTICAL_REFRACTION_AGSL = """
uniform shader scene;
uniform float2 bounds;
uniform float edgeWidth;
uniform float displacement;

half4 main(float2 xy) {
    float2 safeBounds = max(bounds, float2(1.0, 1.0));
    float2 margin = max(float2(0.0, 0.0), min(xy, safeBounds - xy));
    float edgeDistance = min(margin.x, margin.y);
    float weight = 1.0 - smoothstep(0.0, max(edgeWidth, 1.0), edgeDistance);
    float2 direction = xy - safeBounds * 0.5;
    direction /= max(length(direction), 1.0);
    float2 sampleAt = clamp(
        xy + direction * displacement * weight,
        float2(0.5, 0.5),
        safeBounds - float2(0.5, 0.5)
    );
    return scene.eval(sampleAt);
}
"""

internal fun sySupportsOpticalRefraction(
    sdk: Int,
    transparent: Boolean,
    hasScene: Boolean,
    enabled: Boolean,
    surfaceWidth: Int,
    surfaceHeight: Int,
): Boolean = enabled && sdk >= Build.VERSION_CODES.TIRAMISU &&
    transparent && hasScene && surfaceWidth > 0 && surfaceHeight > 0 &&
    // Limit the extra GPU pass to compact floating surfaces, not full-screen drawers.
    surfaceWidth.toLong() * surfaceHeight.toLong() <= 1_300_000L

/** Only an actual behind-the-surface scene enters the shader, never its foreground children. */
@Composable
internal fun SyOpticalBackdrop(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val effect = remember(enabled, measured, density.density) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            measured.width <= 0 || measured.height <= 0) null
        else try {
            val shader = RuntimeShader(SY_OPTICAL_REFRACTION_AGSL)
            shader.setFloatUniform("bounds", measured.width.toFloat(), measured.height.toFloat())
            shader.setFloatUniform("edgeWidth", with(density) { 16.dp.toPx() })
            shader.setFloatUniform("displacement", with(density) { 2.4.dp.toPx() })
            AndroidRenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
        } catch (failure: RuntimeException) {
            Log.w("SyOpticalBackdrop", "AGSL unsupported; using the scene fallback", failure)
            null
        }
    }
    Box(
        modifier.onSizeChanged { measured = it }
            .then(if (effect != null) Modifier.graphicsLayer { renderEffect = effect } else Modifier),
    ) { content() }
}
