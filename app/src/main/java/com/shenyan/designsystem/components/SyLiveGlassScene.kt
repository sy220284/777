package com.shenyan.designsystem.components

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Captured once in the display list and shared with sibling glass overlays.
 * Only [underlay] is recorded. [glass] stays OUTSIDE the capture pass, so the
 * shader never samples its own previous reflection (no recursive feedback).
 * A moving/animating underlay is redrawn by Compose into the same GPU layer.
 */
internal data class SyLiveGlassBacking(
    val layer: GraphicsLayer,
    val origin: IntOffset,
    val viewport: IntSize,
    val rootView: View,
)

internal val LocalSyLiveGlassBacking =
    staticCompositionLocalOf<SyLiveGlassBacking?> { null }

/** Explicit underlay/overlay separation is required for true sibling-pixel refraction. */
@Composable
fun SyLiveGlassScene(
    modifier: Modifier = Modifier,
    underlay: @Composable BoxScope.() -> Unit,
    glass: @Composable BoxScope.() -> Unit,
) {
    val graphicsLayer = rememberGraphicsLayer()
    val view = LocalView.current
    val rootView = view.rootView
    val windowOnScreen = remember { IntArray(2) }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier) {
        Box(
            Modifier.matchParentSize()
                .onGloballyPositioned { coordinates ->
                    rootView.getLocationOnScreen(windowOnScreen)
                    val inWindow = coordinates.positionInWindow()
                    val current = IntOffset(
                        inWindow.x.roundToInt() + windowOnScreen[0],
                        inWindow.y.roundToInt() + windowOnScreen[1],
                    )
                    if (origin != current) origin = current
                    if (viewport != coordinates.size) viewport = coordinates.size
                }
                .drawWithContent {
                    graphicsLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(graphicsLayer)
                },
        ) { underlay() }
        val backing = remember(graphicsLayer, origin, viewport, rootView) {
            SyLiveGlassBacking(graphicsLayer, origin, viewport, rootView)
        }
        CompositionLocalProvider(LocalSyLiveGlassBacking provides backing) {
            glass()
        }
    }
}

/** Source pixels are positioned in window coordinates, then clipped by the lens. */
@Composable
internal fun SyPaintLiveGlassBacking(
    source: SyLiveGlassBacking,
    surfaceOrigin: IntOffset,
    modifier: Modifier,
) {
    Canvas(modifier) {
        if (source.viewport.width <= 0 || source.viewport.height <= 0) return@Canvas
        val dx = (surfaceOrigin.x - source.origin.x).toFloat()
        val dy = (surfaceOrigin.y - source.origin.y).toFloat()
        withTransform({
            translate(-dx, -dy)
        }) {
            drawLayer(source.layer)
        }
    }
}
