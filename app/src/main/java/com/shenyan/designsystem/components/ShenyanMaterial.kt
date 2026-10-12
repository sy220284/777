package com.shenyan.designsystem.components

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalSyColors.current
    val scene = LocalSySceneBackdrop.current
    var materialOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val nested = LocalSyGlassDepth.current
    val resolved = if ((nested >= 3 || LocalSyReduceTransparency.current) && tier != SyMaterialTier.L5_SOLID) SyMaterialTier.L5_SOLID else tier
    val fill = when (resolved) {
        SyMaterialTier.L1_SECTION -> c.materialL1
        SyMaterialTier.L2_BAR -> c.materialL2
        SyMaterialTier.L3_CARD -> c.materialL3
        SyMaterialTier.L4_OVERLAY -> c.materialL4
        SyMaterialTier.L5_SOLID -> c.materialL5
    }
    // Opaque Matte surfaces must never sample or blur the supplied backdrop.
    val blurRadius = if (resolved == SyMaterialTier.L5_SOLID || fill.alpha == 1f) 0.dp else when (resolved) {
        SyMaterialTier.L1_SECTION -> 18.dp
        SyMaterialTier.L2_BAR -> 28.dp
        SyMaterialTier.L3_CARD -> 20.dp
        SyMaterialTier.L4_OVERLAY -> 32.dp
        SyMaterialTier.L5_SOLID -> 0.dp
    }
    val shape = RoundedCornerShape(if (resolved == SyMaterialTier.L4_OVERLAY) SyMetric.radiusSheet else SyMetric.radiusCard)
    val elevation = when (resolved) {
        SyMaterialTier.L1_SECTION, SyMaterialTier.L5_SOLID -> 1.dp
        SyMaterialTier.L2_BAR -> 3.dp
        SyMaterialTier.L3_CARD -> 7.dp
        SyMaterialTier.L4_OVERLAY -> 16.dp
    }
    @Composable fun Interior() {
        Box(Modifier.clip(shape)) {
            if (blurRadius > 0.dp) {
                // An explicit caller backdrop wins. Otherwise sample the scene's actual wallpaper
                // or global light fields at this material's window coordinates.
                val sampled = backdrop ?: scene?.let { sample ->
                    @Composable { SyPaintAlignedScene(sample, materialOrigin) }
                }
                if (sampled != null) {
                    Box(Modifier.matchParentSize().blur(blurRadius, BlurredEdgeTreatment.Unbounded)) {
                        sampled()
                    }
                }
            }
            Box(Modifier.matchParentSize().background(fill))
            CompositionLocalProvider(LocalSyGlassDepth provides (if (fill.alpha == 1f) nested else nested + 1)) {
                Column(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    verticalArrangement = verticalArrangement, content = content)
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(.93f).height(1.dp).background(c.materialSheen))
        }
    }
    val outer = modifier.onGloballyPositioned { coordinates ->
        val point = coordinates.positionInWindow()
        val next = IntOffset(point.x.roundToInt(), point.y.roundToInt())
        if (materialOrigin != next) materialOrigin = next
    }.shadow(elevation, shape).clip(shape)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = outer, color = Color.Transparent, shape = shape,
            border = BorderStroke(1.dp, c.border)) { Interior() }
    } else {
        Surface(modifier = outer, color = Color.Transparent, shape = shape,
            border = BorderStroke(1.dp, c.border)) { Interior() }
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

/** Renders only the wallpaper / designed static light fields, not sibling UI layers.
 * Coordinates reproduce ContentScale.Crop and the three root radial brushes exactly.
 * The parent material alone performs the blur; foreground remains sharp.
 */
@Composable
private fun SyPaintAlignedScene(scene: SySceneBackdrop, surfaceOrigin: IntOffset) {
    val dx = surfaceOrigin.x - scene.viewportOrigin.x
    val dy = surfaceOrigin.y - scene.viewportOrigin.y
    Canvas(Modifier.fillMaxSize()) {
        val viewport = scene.viewportSize
        if (viewport.width <= 0 || viewport.height <= 0) return@Canvas
        drawRect(scene.canvas)
        val image = scene.image
        if (image != null && image.width > 0 && image.height > 0) {
            val crop = sySceneCrop(
                IntSize(image.width, image.height), viewport,
                IntOffset(dx, dy), IntSize(size.width.roundToInt(), size.height.roundToInt()),
            ) ?: return@Canvas
            drawImage(
                image = image, srcOffset = crop.origin, srcSize = crop.size,
                dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium,
            )
            scene.veil?.let { drawRect(it) }
        } else if (scene.glowColors.size >= 3) {
            val extent = max(viewport.width, viewport.height).toFloat()
            val opacity = if (scene.dark) 0.10f else 0.38f
            val centers = listOf(.06f to .08f, .98f to .36f, .24f to .92f)
            val radii = listOf(.72f, .65f, .55f)
            scene.glowColors.take(3).forEachIndexed { index, color ->
                val (x, y) = centers[index]
                drawRect(Brush.radialGradient(
                    colors = listOf(color.copy(alpha = opacity * if (index == 2) .45f else 1f), Color.Transparent),
                    center = Offset(viewport.width * x - dx, viewport.height * y - dy),
                    radius = extent * radii[index],
                ))
            }
        }
    }
}

/** Unit-testable centre-crop mapping; every surface samples the same global pixels. */
internal data class SySceneCrop(val origin: IntOffset, val size: IntSize)

internal fun sySceneCrop(
    image: IntSize, viewport: IntSize, sampleOffset: IntOffset, sample: IntSize,
): SySceneCrop? {
    if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0 ||
        sample.width <= 0 || sample.height <= 0) return null
    val scale = max(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val left = ((image.width - viewport.width / scale) / 2f + sampleOffset.x / scale)
        .roundToInt().coerceIn(0, image.width - 1)
    val top = ((image.height - viewport.height / scale) / 2f + sampleOffset.y / scale)
        .roundToInt().coerceIn(0, image.height - 1)
    return SySceneCrop(
        origin = IntOffset(left, top),
        size = IntSize(
            (sample.width / scale).roundToInt().coerceIn(1, image.width - left),
            (sample.height / scale).roundToInt().coerceIn(1, image.height - top),
        ),
    )
}
