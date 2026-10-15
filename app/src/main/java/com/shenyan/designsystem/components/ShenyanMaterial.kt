package com.shenyan.designsystem.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.shenyan.designsystem.foundation.*

/**
 * Five-tier material grammar. Alpha, border, rim highlight and elevation are native and real.
 * Compose cannot blur *already-drawn sibling content* with Modifier.blur: it blurs its own layer.
 * For true backdrop blur, caller may supply a spatially aligned `backdrop` composable. It is
 * drawn behind the fill, and only that backdrop is blurred. Never blur foreground text.
 * On old devices or when no backdrop is supplied use a clean semantic surface fallback.
 */
enum class SyMaterialTier { L1_SECTION, L2_BAR, L3_CARD, L4_OVERLAY, L5_SOLID }

@Composable
fun SyMaterialFrame(
    modifier: Modifier = Modifier,
    tier: SyMaterialTier = SyMaterialTier.L3_CARD,
    onClick: (() -> Unit)? = null,
    backdrop: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(SyMetric.gapMd),
    shape: Shape = RoundedCornerShape(if (tier == SyMaterialTier.L4_OVERLAY) SyMetric.radiusSheet else SyMetric.radiusCard),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SyMaterialSurface(modifier = modifier, tier = tier, onClick = onClick, backdrop = backdrop,
        shape = shape, border = border) {
        Column(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = verticalArrangement, content = content)
    }
}

/** A single material renderer for columns, rows, images, controls and overlays. */
@Composable
fun SyMaterialSurface(
    modifier: Modifier = Modifier,
    tier: SyMaterialTier = SyMaterialTier.L3_CARD,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    tint: Color = Color.Unspecified,
    backdrop: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(if (tier == SyMaterialTier.L4_OVERLAY) SyMetric.radiusSheet else SyMetric.radiusCard),
    border: BorderStroke? = null,
    contentColor: Color = LocalSyColors.current.text,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp? = null,
    opticalGlass: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = LocalSyColors.current
    val scene = LocalSySceneBackdrop.current
    val view = LocalView.current
    val windowOnScreen = remember { IntArray(2) }
    var materialOrigin by remember { mutableStateOf(IntOffset.Zero) }
    var materialSize by remember { mutableStateOf(IntSize.Zero) }
    val nested = LocalSyGlassDepth.current
    // Matte already has opaque tiers: reduced transparency must not erase their hierarchy.
    val needsSolidFallback = LocalSyReduceTransparency.current && c.materialL3.alpha < 1f
    val resolved = if ((nested >= 3 || needsSolidFallback) && tier != SyMaterialTier.L5_SOLID) SyMaterialTier.L5_SOLID else tier
    val regionY = if (scene == null || scene.viewportSize.height == 0) .5f else
        (materialOrigin.y - scene.viewportOrigin.y + materialSize.height / 2f) / scene.viewportSize.height
    val region = when { regionY < .30f -> 0; regionY < .74f -> 1; else -> 2 }
    val materialFill = remember(c, resolved, scene, region) { syResolveMaterialFill(c, resolved, scene, regionY) }
    val defaultInteraction = remember { MutableInteractionSource() }
    val interaction = interactionSource ?: defaultInteraction
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed && enabled && onClick != null && !LocalSyReduceMotion.current) SyMotion.pressedScale else 1f,
        tween(SyMotion.duration(SyMotion.pressMs, LocalSyReduceMotion.current, LocalSyMotionTempo.current)), label = "materialPress",
    )
    // Without a spatial backing, use the same palette over its canvas rather than letting
    // unrelated content show through. Nested cards inherit their parent's backing.
    val fill = if (scene == null && backdrop == null && nested == 0)
        materialFill.compositeOver(c.canvas) else materialFill
    // Opaque Matte surfaces must never sample or blur the supplied backdrop.
    val blurRadius = if (resolved == SyMaterialTier.L5_SOLID || fill.alpha == 1f) 0.dp else when (resolved) {
        SyMaterialTier.L1_SECTION -> 18.dp
        SyMaterialTier.L2_BAR -> 28.dp
        SyMaterialTier.L3_CARD -> 20.dp
        SyMaterialTier.L4_OVERLAY -> 32.dp
        SyMaterialTier.L5_SOLID -> 0.dp
    }
    val elevation = when (resolved) {
        SyMaterialTier.L1_SECTION, SyMaterialTier.L5_SOLID -> 1.dp
        SyMaterialTier.L2_BAR -> 3.dp
        SyMaterialTier.L3_CARD -> 7.dp
        SyMaterialTier.L4_OVERLAY -> 16.dp
    }
    val rim = if (materialFill.alpha < 1f) BorderStroke(1.dp, Brush.linearGradient(
        listOf(c.materialSheen, c.border.copy(alpha = c.border.alpha * .45f), c.materialSheen.copy(alpha = .12f)),
    )) else BorderStroke(1.dp, c.border)
    @Composable fun Interior() {
        Box(Modifier.clip(shape)) {
            if (blurRadius > 0.dp && nested == 0) {
                // An explicit caller backdrop wins. Otherwise sample the scene's actual wallpaper
                // or global light fields at this material's window coordinates.
                val sampled = backdrop ?: scene?.let { sample ->
                    @Composable { SyPaintAlignedScene(sample, materialOrigin) }
                }
                if (sampled != null) {
                    val refract = sySupportsOpticalRefraction(
                        sdk = Build.VERSION.SDK_INT,
                        transparent = !LocalSyReduceTransparency.current && fill.alpha < 1f,
                        hasScene = true,
                        enabled = opticalGlass &&
                            (resolved == SyMaterialTier.L2_BAR || resolved == SyMaterialTier.L4_OVERLAY),
                        surfaceWidth = materialSize.width,
                        surfaceHeight = materialSize.height,
                    )
                    // Distort only the aligned background layer; text, controls and click targets
                    // stay sharp and independent of the platform AGSL capability.
                    SyOpticalBackdrop(Modifier.matchParentSize(), enabled = refract) {
                        Box(Modifier.matchParentSize().blur(blurRadius, BlurredEdgeTreatment.Unbounded)) {
                            sampled()
                        }
                    }
                }
            }
            Box(Modifier.matchParentSize().background(fill))
            if (tint.isSpecified) Box(Modifier.matchParentSize().background(tint.copy(alpha = tint.alpha.coerceAtMost(.22f))))
            CompositionLocalProvider(LocalSyGlassDepth provides (nested + 1)) {
                content()
            }
            if (materialFill.alpha < 1f) {
                // A decorative highlight must never participate in Box measurement.
                // fillMaxWidth() here made compact surfaces stretch to the entire Row,
                // squeezing adjacent labels into single-character vertical columns.
                Canvas(Modifier.matchParentSize()) {
                    val y = 0.5.dp.toPx()
                    drawLine(
                        color = c.materialSheen,
                        start = Offset(size.width * .035f, y),
                        end = Offset(size.width * .965f, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }
    }
    val outer = modifier.onGloballyPositioned { coordinates ->
        view.rootView.getLocationOnScreen(windowOnScreen)
        val point = coordinates.positionInWindow()
        val next = IntOffset(point.x.roundToInt() + windowOnScreen[0], point.y.roundToInt() + windowOnScreen[1])
        if (materialOrigin != next) materialOrigin = next
        if (materialSize != coordinates.size) materialSize = coordinates.size
    }.graphicsLayer { scaleX = press; scaleY = press }.shadow(shadowElevation ?: elevation, shape).clip(shape)
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = outer, color = Color.Transparent, shape = shape,
            contentColor = contentColor, tonalElevation = tonalElevation,
            border = border ?: rim) { Interior() }
    } else {
        Surface(modifier = outer, color = Color.Transparent, shape = shape,
            contentColor = contentColor, tonalElevation = tonalElevation,
            border = border ?: rim) { Interior() }
    }
}

/** Machine-generated output/paths/IDs use monospaced Latin glyphs; CJK falls back through OS. */
@Composable
fun SyMachineText(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = LocalSyColors.current.textSecondary,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
    )
}

/**
 * Paint the exact root background behind a compact overlay, without a separate glass/white plate.
 * This intentionally samples the same scene and screen coordinates as the app's background host.
 * In previews without a scene, fall back to the active theme canvas (never hardcoded white).
 */
@Composable
fun SySceneBackgroundSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SyMetric.radiusSheet),
    fallbackColor: Color? = null,
    tag: String = "sySceneBackgroundSurface",
    opticalGlass: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scene = LocalSySceneBackdrop.current
    val canvas = fallbackColor ?: scene?.canvas ?: LocalSyColors.current.canvas
    val view = LocalView.current
    val windowOnScreen = remember { IntArray(2) }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val colors = LocalSyColors.current
    val refractScene = sySupportsOpticalRefraction(
        sdk = Build.VERSION.SDK_INT,
        transparent = !LocalSyReduceTransparency.current && colors.materialL3.alpha < 1f,
        hasScene = scene != null,
        enabled = opticalGlass,
        surfaceWidth = surfaceSize.width,
        surfaceHeight = surfaceSize.height,
    )
    Box(
        modifier.clip(shape).testTag(tag).onGloballyPositioned { coordinates ->
            surfaceSize = coordinates.size
            view.rootView.getLocationOnScreen(windowOnScreen)
            val point = coordinates.positionInWindow()
            val next = IntOffset(point.x.roundToInt() + windowOnScreen[0],
                point.y.roundToInt() + windowOnScreen[1])
            if (origin != next) origin = next
        },
    ) {
        // Show the theme canvas immediately while root coordinates are being measured.
        Box(Modifier.matchParentSize().background(canvas))
        if (scene != null) {
            SyOpticalBackdrop(Modifier.matchParentSize(), enabled = refractScene) {
                SyPaintAlignedScene(scene, origin, Modifier.matchParentSize())
            }
        }
        content()
        if (refractScene) {
            // Thin specular rim, without another opaque material plate.
            Canvas(Modifier.matchParentSize()) {
                val inset = 1.dp.toPx()
                drawLine(
                    Brush.horizontalGradient(listOf(colors.materialSheen, Color.Transparent,
                        colors.materialSheen.copy(alpha = .45f))),
                    start = Offset(size.width * .08f, inset),
                    end = Offset(size.width * .92f, inset),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

/** Renders only the wallpaper / designed static light fields, not sibling UI layers.
 * Coordinates reproduce ContentScale.Crop and the three root radial brushes exactly.
 * The parent material alone performs the blur; foreground remains sharp.
 */
@Composable
private fun SyPaintAlignedScene(
    scene: SySceneBackdrop, surfaceOrigin: IntOffset, modifier: Modifier = Modifier.fillMaxSize(),
) {
    val dx = surfaceOrigin.x - scene.viewportOrigin.x
    val dy = surfaceOrigin.y - scene.viewportOrigin.y
    Canvas(modifier) {
        val viewport = scene.viewportSize
        if (viewport.width <= 0 || viewport.height <= 0) return@Canvas
        drawRect(scene.canvas)
        val image = scene.image
        if (image != null && image.width > 0 && image.height > 0) {
            val placement = sySceneImagePlacement(
                IntSize(image.width, image.height), viewport, IntOffset(dx, dy),
            ) ?: return@Canvas
            drawImage(
                image = image, dstOffset = placement.origin, dstSize = placement.size,
                filterQuality = FilterQuality.Medium,
            )
            scene.veil?.let { drawRect(it) }
        } else if (scene.glowColors.size >= 3) {
            val extent = max(viewport.width, viewport.height).toFloat()
            val baselineOpacity = if (scene.dark) 0.10f else 0.38f
            val opacity = baselineOpacity * (scene.ambientGlow.coerceIn(0f, 1f) * 2f)
            val centers = listOf(.06f to .08f, .98f to .36f, .24f to .92f)
            val radii = listOf(.72f, .65f, .55f)
            scene.glowColors.take(3).forEachIndexed { index, color ->
                val (x, y) = centers[index]
                drawRect(Brush.radialGradient(
                    colors = listOf(color.copy(alpha = color.alpha * opacity * if (index == 2) .45f else 1f), Color.Transparent),
                    center = Offset(viewport.width * x - dx, viewport.height * y - dy),
                    radius = extent * radii[index],
                ))
            }
        }
    }
}

/** Draw the whole centre-cropped image at the root scale. Clipping belongs to the surface,
 * so a partly off-screen card never stretches a clamped source crop into its full bounds.
 */
internal data class SySceneImagePlacement(val origin: IntOffset, val size: IntSize)

internal fun sySceneImagePlacement(
    image: IntSize, viewport: IntSize, sampleOffset: IntOffset,
): SySceneImagePlacement? {
    if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return null
    val scale = max(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val size = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
    return SySceneImagePlacement(
        IntOffset((viewport.width - size.width) / 2 - sampleOffset.x,
            (viewport.height - size.height) / 2 - sampleOffset.y), size,
    )
}
