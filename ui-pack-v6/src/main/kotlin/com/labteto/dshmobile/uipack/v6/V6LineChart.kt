package com.labteto.dshmobile.uipack.v6

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import com.shenyan.designsystem.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.UiDocumentNode
import kotlin.math.abs

/** Normalize before subtracting to keep finite extreme values drawable. Equal values sit centrally. */
internal fun v6LineChartCoordinates(values: List<Double>): List<Pair<Float, Float>> {
    if (values.isEmpty()) return emptyList()
    val magnitude = values.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
    val scaled = values.map { it / magnitude }
    val low = scaled.min()
    val spread = scaled.max() - low
    return scaled.mapIndexed { index, value ->
        val x = if (values.size == 1) .5f else index.toFloat() / (values.size - 1)
        val y = if (spread == 0.0) .5f else (1.0 - (value - low) / spread).toFloat().coerceIn(0f, 1f)
        x to y
    }
}

@Composable
internal fun V6LineChart(node: UiDocumentNode.LineChart) {
    val coordinates = remember(node.points) { v6LineChartCoordinates(node.points.map { it.value }) }
    val tint = MaterialTheme.colorScheme.primary
    val reduced = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    val reveal = remember(node.id) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(node.id, reduced, tempo) {
        if (reduced) reveal.snapTo(1f)
        else reveal.animateTo(1f, tween(SyMotion.duration(SyMotion.chartRevealMs, false, tempo), easing = SyMotion.ease))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(node.title, style = MaterialTheme.typography.titleSmall)
        if (coordinates.isEmpty()) Text("暂无数据", style = MaterialTheme.typography.bodyMedium)
        else Canvas(Modifier.fillMaxWidth().height(108.dp)
            .semantics { contentDescription = "折线趋势图：${node.title}，共${node.points.size}个数据点" }) {
            val inset = 8.dp.toPx()
            val width = (size.width - inset * 2).coerceAtLeast(0f)
            val height = (size.height - inset * 2).coerceAtLeast(0f)
            val positions = coordinates.map { (x, y) -> Offset(inset + x * width, inset + y * height) }
            clipRect(right = size.width * reveal.value) {
            if (positions.size == 1) drawCircle(tint, radius = 3.dp.toPx(), center = positions.first())
            else {
                val path = Path()
                positions.forEachIndexed { i, point ->
                    if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(path, tint, style = Stroke(width = 2.dp.toPx()))
            }
            }
        }
        node.points.forEach { Text("${it.label}：${it.value} ${node.unit}") }
    }
}
