package com.shenyan.designsystem.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.min

/**
 * GPU-only optical lens. RenderEffect passes the live pixels of [backdrop] as
 * the 'source' shader. No bitmap readback, PixelCopy or CPU pixel loop.
 *
 * The caller must provide the actual underlay it wants to refract. The canonical
 * scene painter supplies wallpaper/light fields by default; explicit backdrops
 * can include animated images and text drawn beneath an overlay.
 *
 * This renders the copied underlay, never foreground labels or buttons.
 * Supported minSdk = 36, AGSL RuntimeShader has been available since API 33.
 */
internal object SyLiquidShader {
    const val INPUT = "source"
    const val PROGRAM = """
        uniform shader source;
        uniform float2 resolution;
        uniform float4 corners;
        uniform float2 touchPoint;
        uniform float touchAmount;
        uniform float bendPx;
        uniform float chromaPx;
        uniform float edgePx;
        uniform float reflection;
        layout(color) uniform half4 glassTint;

        float cornerAt(float2 p) {
            bool right = p.x >= resolution.x * 0.5;
            bool lower = p.y >= resolution.y * 0.5;
            if (right) return lower ? corners.z : corners.y;
            return lower ? corners.w : corners.x;
        }

        float signedDistance(float2 p) {
            float r = min(cornerAt(p), min(resolution.x, resolution.y) * 0.5);
            float2 halfExtent = resolution * 0.5;
            float2 q = abs(p - halfExtent) - (halfExtent - float2(r));
            return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 p) {
            float d = signedDistance(p);
            float2 gradient = float2(
                signedDistance(p + float2(1.25, 0.0)) - signedDistance(p - float2(1.25, 0.0)),
                signedDistance(p + float2(0.0, 1.25)) - signedDistance(p - float2(0.0, 1.25))
            );
            float2 normal = gradient / max(length(gradient), 0.001);

            float touchDistance = length(p - touchPoint);
            float touchRadius = max(min(resolution.x, resolution.y) * 0.60, 32.0);
            float pulse = touchAmount * exp(-touchDistance * touchDistance /
                (touchRadius * touchRadius));
            // The contact slightly changes the apparent local thickness of the lens.
            float deformedDistance = d - pulse * 4.0;
            float rim = smoothstep(-edgePx, -0.3, deformedDistance);
            float lensBend = bendPx * rim * rim;
            float wave = pulse * 2.2 * sin(touchDistance * 0.055);
            float2 displacement = -normal * (lensBend + wave);
            float2 samplePoint = clamp(
                p + displacement,
                float2(0.75),
                resolution - float2(0.75)
            );
            float2 spread = normal * chromaPx * rim;

            half4 redSample = source.eval(clamp(samplePoint + spread,
                float2(0.75), resolution - float2(0.75)));
            half4 greenSample = source.eval(samplePoint);
            half4 blueSample = source.eval(clamp(samplePoint - spread,
                float2(0.75), resolution - float2(0.75)));
            half3 refracted = half3(redSample.r, greenSample.g, blueSample.b);

            // Retain the selected palette/contrast tint on top of real displaced pixels.
            half3 finalColor = mix(refracted, glassTint.rgb, glassTint.a);
            // Fresnel-style edge highlight and underside shadow reveal curved thickness.
            float edgeHighlight = pow(rim, 2.6) * reflection;
            float lightDirection = 0.52 - 0.48 * normal.x - 0.42 * normal.y;
            half3 light = half3(1.0, 0.99, 0.97);
            finalColor = mix(finalColor, light,
                half(clamp(edgeHighlight * max(lightDirection, 0.0) * 0.45, 0.0, 0.58)));
            finalColor *= half(1.0 - edgeHighlight *
                max(normal.x * 0.46 + normal.y * 0.32, 0.0) * 0.13);

            // A live optical edge is a shaped surface. Transparent pixels outside
            // the deformed lens show the unchanged original underlay beneath.
            float coverage = 1.0 - smoothstep(-1.2, 1.2, deformedDistance);
            return half4(finalColor * half(coverage), half(coverage));
        }
    """
}

internal fun syShouldRefract(
    tier: SyMaterialTier,
    nestedDepth: Int,
    explicitBackdrop: Boolean,
): Boolean =
    (nestedDepth == 0 || explicitBackdrop) &&
        (tier == SyMaterialTier.L2_BAR || tier == SyMaterialTier.L4_OVERLAY || explicitBackdrop)

/** The input is rendered live every draw; shader and RenderEffect are cached per surface. */
@Composable
internal fun SyLiquidRefractionLayer(
    modifier: Modifier,
    shape: Shape,
    fill: Color,
    tier: SyMaterialTier,
    reflection: Float,
    touchPoint: Offset,
    pressure: Float,
    backdrop: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val shader = remember { RuntimeShader(SyLiquidShader.PROGRAM) }
    val effect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, SyLiquidShader.INPUT)
            .asComposeRenderEffect()
    }
    Box(modifier.graphicsLayer {
        val width = size.width.coerceAtLeast(2f)
        val height = size.height.coerceAtLeast(2f)
        val layoutSize = Size(width, height)
        val r = if (shape is RoundedCornerShape) {
            floatArrayOf(
                shape.topStart.toPx(layoutSize, density),
                shape.topEnd.toPx(layoutSize, density),
                shape.bottomEnd.toPx(layoutSize, density),
                shape.bottomStart.toPx(layoutSize, density),
            )
        } else floatArrayOf(0f, 0f, 0f, 0f)
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("corners", r[0], r[1], r[2], r[3])
        shader.setFloatUniform(
            "touchPoint",
            touchPoint.x.coerceIn(0f, width), touchPoint.y.coerceIn(0f, height),
        )
        shader.setFloatUniform("touchAmount", pressure.coerceIn(0f, 1f))
        shader.setFloatUniform("bendPx", min(width, height) * when (tier) {
            SyMaterialTier.L4_OVERLAY -> .09f
            SyMaterialTier.L2_BAR -> .12f
            else -> .075f
        })
        shader.setFloatUniform("chromaPx", min(width, height) * .005f)
        shader.setFloatUniform("edgePx", min(width, height) * .20f)
        shader.setFloatUniform("reflection", reflection.coerceIn(0f, 1f))
        shader.setColorUniform("glassTint", fill.toArgb())
        renderEffect = effect
    }) {
        backdrop()
    }
}
