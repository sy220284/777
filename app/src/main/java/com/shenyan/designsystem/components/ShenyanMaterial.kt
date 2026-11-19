package com.shenyan.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.shenyan.designsystem.foundation.*

/**
 * Five material tiers share the same scene. Optical chrome refracts a distinct backing;
 * foreground text sits above it. Flat surfaces keep their readable material fill.
 */
enum class SyMaterialTier { L1_SECTION, L2_BAR, L3_CARD, L4_OVERLAY, L5_SOLID }

/**
 * Flat chrome, reading surfaces and image masks keep a caller's semantic fill
 * and geometry. Interactive panels share focus and press feedback without
 * introducing a shadow or a backdrop effect.
 */
@Composable
fun SyFlatSurface(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        Surface(
            modifier = modifier, shape = shape, color = color, contentColor = contentColor,
            tonalElevation = tonalElevation, shadowElevation = shadowElevation,
            border = border, content = content,
        )
    } else {
        val palette = LocalSyColors.current
        val source = interactionSource ?: remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val focused by source.collectIsFocusedAsState()
        val tone by animateColorAsState(
            targetValue = if (enabled && (pressed || focused)) {
                palette.primary.copy(alpha = if (pressed) .12f else .07f).compositeOver(color)
            } else color,
            animationSpec = tween(SyMotion.duration(
                SyMotion.interactionColorMs, LocalSyReduceMotion.current, LocalSyMotionTempo.current,
            )),
            label = "syFlatSurfaceTone",
        )
        Surface(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            color = tone, contentColor = contentColor, tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = if (focused && enabled) BorderStroke(1.dp, palette.linkInk) else border,
            interactionSource = source, content = content,
        )
    }
}

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

/** Shared material and geometry for the actual Chat/Work and preview composers. */
@Composable
fun SyComposerSurface(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    shadowElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    SyMaterialSurface(
        modifier = modifier,
        tier = SyMaterialTier.L2_BAR,
        shape = RoundedCornerShape(SyMetric.radiusCard),
        tint = tint,
        // Let the canonical renderer draw the directional optical rim.
        shadowElevation = shadowElevation,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            content = content)
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
    content: @Composable () -> Unit,
) {
    val c = LocalSyColors.current
    val scene = LocalSySceneBackdrop.current
    val view = LocalView.current
    // A separated live underlay (when available) takes priority over wallpaper.
    // View identity prevents replaying a GraphicsLayer into a separate dialog window.
    val liveBacking = LocalSyLiveGlassBacking.current?.takeIf {
        it.rootView === view.rootView && it.viewport.width > 0 && it.viewport.height > 0
    }
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
    val fill = if (scene == null && backdrop == null && liveBacking == null && nested == 0)
        materialFill.compositeOver(c.canvas) else materialFill
    val optics = syGlassOptics(
        colors = c, tier = resolved, fill = fill,
        reduceTransparency = LocalSyReduceTransparency.current,
        nestedDepth = nested, hasBackdrop = scene != null || backdrop != null || liveBacking != null,
    )
    // One GPU lens for anchored chrome and overlays; list cards retain their
    // inexpensive rim unless they explicitly own a live underlay.
    val refract = optics.glass && syShouldRefract(resolved, nested, backdrop != null)
    var lensPoint by remember { mutableStateOf(Offset.Zero) }
    var lensDrag by remember { mutableStateOf(Offset.Zero) }
    var lensDown by remember { mutableStateOf(false) }
    val lensPressure by animateFloatAsState(
        if (refract && lensDown && !LocalSyReduceMotion.current) 1f else 0f,
        tween(SyMotion.duration(SyMotion.interactionColorMs,
            LocalSyReduceMotion.current, LocalSyMotionTempo.current)),
        label = "liquidLensPressure",
    )
    val surfaceBorder = when {
        refract -> null
        optics.glass -> BorderStroke(1.dp, c.border.copy(alpha = c.border.alpha * .38f))
        else -> border ?: BorderStroke(1.dp, c.border)
    }
    @Composable fun Interior() {
        Box(Modifier.clip(shape)) {
            val sampled = if (refract) {
                backdrop ?: if (liveBacking != null || scene != null) {
                    @Composable {
                        // The captured transcript has transparent gaps. Include the same
                        // aligned scene below it so those gaps never refract as black.
                        if (scene != null) SyPaintAlignedScene(scene, materialOrigin)
                        else Box(Modifier.fillMaxSize().background(c.canvas))
                        if (liveBacking != null) {
                            SyPaintLiveGlassBacking(liveBacking, materialOrigin, Modifier.fillMaxSize())
                        }
                    }
                } else null
            } else null
            if (sampled != null && refract) {
                SyLiquidRefractionLayer(
                    modifier = Modifier.matchParentSize(),
                    shape = shape,
                    fill = syLiquidTint(fill, tint),
                    tier = resolved,
                    reflection = optics.reflection,
                    touchPoint = lensPoint,
                    pressure = lensPressure,
                    drag = lensDrag * lensPressure,
                    backdrop = sampled,
                )
            } else {
                Box(Modifier.matchParentSize().background(fill))
            }
            if (!refract && tint.isSpecified) {
                Box(Modifier.matchParentSize().background(tint.copy(alpha = tint.alpha.coerceAtMost(.22f))))
            }
            CompositionLocalProvider(LocalSyGlassDepth provides (nested + 1)) {
                content()
            }
        }
    }
    val opticalPointer = if (refract) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            // Observe in the Final pass without consuming the page's scroll/drag events.
            val first = awaitFirstDown(requireUnconsumed = false)
            lensPoint = first.position
            lensDrag = Offset.Zero
            lensDown = true
            val motionHistory = java.util.ArrayDeque<Pair<Long, Offset>>()
            motionHistory.addLast(first.uptimeMillis to first.position)
            do {
                val next = awaitPointerEvent(PointerEventPass.Final)
                    .changes.firstOrNull { it.id == first.id }
                if (next != null && next.pressed) {
                    lensDrag = syWindowedGlassDrag(
                        motionHistory, next.uptimeMillis, next.position,
                    )
                    lensPoint = next.position
                }
            } while (next != null && next.pressed)
            lensDown = false
        }
    } else Modifier
    val outer = modifier.then(opticalPointer).onGloballyPositioned { coordinates ->
        view.rootView.getLocationOnScreen(windowOnScreen)
        val point = coordinates.positionInWindow()
        val next = IntOffset(point.x.roundToInt() + windowOnScreen[0], point.y.roundToInt() + windowOnScreen[1])
        if (materialOrigin != next) materialOrigin = next
        if (materialSize != coordinates.size) materialSize = coordinates.size
    }.graphicsLayer { scaleX = press; scaleY = press }
        .then(if (optics.glass) Modifier else Modifier.shadow(shadowElevation ?: optics.elevation, shape))
        .clip(shape)
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = outer, color = Color.Transparent, shape = shape,
            contentColor = contentColor, tonalElevation = tonalElevation,
            border = surfaceBorder) { Interior() }
    } else {
        Surface(modifier = outer, color = Color.Transparent, shape = shape,
            contentColor = contentColor, tonalElevation = tonalElevation,
            border = surfaceBorder) { Interior() }
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
    content: @Composable () -> Unit,
) {
    val scene = LocalSySceneBackdrop.current
    val canvas = fallbackColor ?: scene?.canvas ?: LocalSyColors.current.canvas
    val view = LocalView.current
    val windowOnScreen = remember { IntArray(2) }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier.clip(shape).testTag(tag).onGloballyPositioned { coordinates ->
            view.rootView.getLocationOnScreen(windowOnScreen)
            val point = coordinates.positionInWindow()
            val next = IntOffset(point.x.roundToInt() + windowOnScreen[0],
                point.y.roundToInt() + windowOnScreen[1])
            if (origin != next) origin = next
        },
    ) {
        // Show the theme canvas immediately while root coordinates are being measured.
        Box(Modifier.matchParentSize().background(canvas))
        if (scene != null) SyPaintAlignedScene(scene, origin, Modifier.matchParentSize())
        content()
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
