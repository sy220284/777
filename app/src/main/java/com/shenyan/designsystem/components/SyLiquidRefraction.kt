package com.shenyan.designsystem.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
        uniform float blurPx;
        uniform float clarity;
        uniform float2 drag;
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
            // Directional compression moves the INSIDE edge of the glass with
            // the finger while the public shape stays securely hit-testable.
            float2 motion = clamp(drag, float2(-14.0), float2(14.0));
            float directional = dot(normal, motion) / max(length(motion), 1.0);
            float deformedDistance = d + pulse * (1.1 + directional * 0.7);
            float rim = smoothstep(-edgePx, -0.3, deformedDistance);
            float lensBend = bendPx * rim * rim;
            float wave = pulse * 1.15 * sin(touchDistance * 0.055);
            float2 displacement = -normal * (lensBend + wave) +
                motion * pulse * rim * 0.28;
            float2 samplePoint = clamp(
                p + displacement,
                float2(0.75),
                resolution - float2(0.75)
            );
            float2 spread = normal * chromaPx * rim;

            half4 centerSample = source.eval(samplePoint);
            half3 refracted;
            if (chromaPx > 0.01) {
                half4 redSample = source.eval(clamp(samplePoint + spread,
                    float2(0.75), resolution - float2(0.75)));
                half4 blueSample = source.eval(clamp(samplePoint - spread,
                    float2(0.75), resolution - float2(0.75)));
                refracted = half3(redSample.r, centerSample.g, blueSample.b);
            } else {
                refracted = centerSample.rgb;
            }
            if (blurPx > 0.05) {
                float2 horizontal = float2(blurPx, 0.0);
                float2 vertical = float2(0.0, blurPx);
                half3 soft = (
                    source.eval(clamp(samplePoint + horizontal,
                        float2(0.75), resolution - float2(0.75))).rgb +
                    source.eval(clamp(samplePoint - horizontal,
                        float2(0.75), resolution - float2(0.75))).rgb +
                    source.eval(clamp(samplePoint + vertical,
                        float2(0.75), resolution - float2(0.75))).rgb +
                    source.eval(clamp(samplePoint - vertical,
                        float2(0.75), resolution - float2(0.75))).rgb
                ) * half(0.25);
                refracted = mix(refracted, soft, half(0.08 + 0.10 * rim));
            }

            float tintAlpha = glassTint.a * (1.0 - clarity * rim);
            half3 colour = mix(refracted, glassTint.rgb, half(tintAlpha));
            half alpha = mix(centerSample.a, half(1.0), half(tintAlpha));
            // A single, directional highlight hugs the inner edge. There is
            // no simulated underside shadow, stroke or full-surface glare.
            float glint = pow(rim, 4.0) * reflection *
                max(dot(normal, float2(-0.71, -0.71)), 0.0) * 0.15;
            colour = mix(colour, half3(alpha), half(glint));

            float coverage = 1.0 - smoothstep(-1.0, 0.7, deformedDistance);
            return half4(min(colour, half3(alpha)), alpha) * half(coverage);
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

/** The input is rendered live each draw. Uniforms are updated with the layer properties. */
@Composable
internal fun SyLiquidRefractionLayer(
    modifier: Modifier,
    shape: Shape,
    fill: Color,
    tier: SyMaterialTier,
    reflection: Float,
    touchPoint: Offset,
    pressure: Float,
    drag: Offset = Offset.Zero,
    backdrop: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val shader = remember { RuntimeShader(SyLiquidShader.PROGRAM) }
    // The RenderEffect input must be the pixels of THIS graphicsLayer.
    // Previous code recorded the backdrop into a separate GPU layer and
    // tried to draw that layer through an unrelated Canvas: the explicit
    // backdrop test stayed white even when its content changed.
    // A single offscreen graphicsLayer owns the real live child rendering.
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
        // Optical curvature belongs to a slim physical rim, not 20% of
        // the item height. Keep edge highlights delicate on small list rows.
        val shortSide = min(width, height)
        val physicalBend = with(density) { 3.dp.toPx() }
        val physicalEdge = with(density) { 3.5.dp.toPx() }
        shader.setFloatUniform("bendPx", min(shortSide * .07f, physicalBend))
        shader.setFloatUniform("edgePx", min(shortSide * .065f, physicalEdge))
        shader.setFloatUniform("reflection", reflection.coerceIn(0f, 1f))
        shader.setFloatUniform("drag", drag.x, drag.y)
        // Scale down multi-tap blur/dispersion for large full-width overlays:
        // keep optical displacement while avoiding a 7-sample fullscreen pass.
        val highCost = width * height > 430_000f
        shader.setFloatUniform("blurPx",
            if (highCost) 0f else min(shortSide * .026f,
                with(density) { if (tier == SyMaterialTier.L4_OVERLAY) 1.4.dp.toPx() else 1.dp.toPx() }))
        shader.setFloatUniform("chromaPx",
            if (highCost) 0f else min(shortSide * .003f,
                with(density) { .8.dp.toPx() }))
        shader.setFloatUniform("clarity", when (tier) {
            SyMaterialTier.L2_BAR -> .66f
            SyMaterialTier.L4_OVERLAY -> .46f
            else -> .40f
        })
        shader.setColorUniform("glassTint", fill.toArgb())
        // Apply the effect after setting its complete set of uniforms.
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, SyLiquidShader.INPUT)
            .asComposeRenderEffect()
    }.clearAndSetSemantics {}) {
        // This is a visual-only sampling copy; accessible content and controls
        // remain in the actual underlay or foreground, not the optical texture.
        backdrop()
    }
}
