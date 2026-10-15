package com.shenyan.designsystem.components

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import com.labteto.dshmobile.observability.AppLog
import com.shenyan.designsystem.foundation.SyMaterialPolicy
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
uniform float cornerRadius;

half4 main(float2 xy) {
    float2 safeBounds = max(bounds, float2(1.0, 1.0));
    // Rounded-rectangle signed distance keeps the optical band tied to the
    // actual clipped contour instead of a rectangular edge under a round mask.
    float2 halfBounds = safeBounds * 0.5;
    float radius = clamp(cornerRadius, 0.0, min(halfBounds.x, halfBounds.y));
    float2 fromCenter = xy - halfBounds;
    float2 q = abs(fromCenter) - (halfBounds - float2(radius, radius));
    float signedDistance = length(max(q, float2(0.0, 0.0))) +
        min(max(q.x, q.y), 0.0) - radius;
    float edgeDistance = max(-signedDistance, 0.0);
    float weight = 1.0 - smoothstep(0.0, max(edgeWidth, 1.0), edgeDistance);
    float2 nearestEdge = min(xy, safeBounds - xy);
    float2 straightNormal = nearestEdge.x < nearestEdge.y
        ? float2(fromCenter.x < 0.0 ? -1.0 : 1.0, 0.0)
        : float2(0.0, fromCenter.y < 0.0 ? -1.0 : 1.0);
    float2 roundNormal = max(q, float2(0.0, 0.0)) *
        float2(fromCenter.x < 0.0 ? -1.0 : 1.0,
               fromCenter.y < 0.0 ? -1.0 : 1.0);
    float normalLength = length(roundNormal);
    float2 direction = normalLength > 0.001 ? roundNormal / normalLength : straightNormal;
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
    surfaceWidth.toLong() * surfaceHeight.toLong() <= SyMaterialPolicy.opticalMaxAreaPx

/** Only an actual behind-the-surface scene enters the shader, never its foreground children. */
@Composable
internal fun SyOpticalBackdrop(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    opticalShape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val accelerated = LocalView.current.isHardwareAccelerated
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val effect = remember(enabled, measured, density.density, accelerated, opticalShape, layoutDirection) {
        if (!enabled || !accelerated ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            measured.width <= 0 || measured.height <= 0) null
        else try {
            val shader = RuntimeShader(SY_OPTICAL_REFRACTION_AGSL)
            shader.setFloatUniform("bounds", measured.width.toFloat(), measured.height.toFloat())
            shader.setFloatUniform("edgeWidth", with(density) { SyMaterialPolicy.opticalEdgeWidthDp.dp.toPx() })
            shader.setFloatUniform("displacement", with(density) { SyMaterialPolicy.opticalDisplacementDp.dp.toPx() })
            val outline = opticalShape.createOutline(
                Size(measured.width.toFloat(), measured.height.toFloat()), layoutDirection, density,
            )
            val radius = when (outline) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                else -> 0f
            }
            shader.setFloatUniform("cornerRadius", radius)
            AndroidRenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
        } catch (failure: RuntimeException) {
            AppLog.warn("SyOpticalBackdrop", "failure=true operation=create_optical_shader fallback=scene cause_type=${failure.javaClass.simpleName}")
            null
        }
    }
    Box(
        modifier.onSizeChanged { measured = it }
            .then(if (effect != null) Modifier.graphicsLayer { renderEffect = effect } else Modifier),
    ) { content() }
}
