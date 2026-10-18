package com.labteto.dshmobile.uipack.zhaoxi.components

import android.graphics.Paint
import android.graphics.Path
import android.util.Xml
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import org.xmlpull.v1.XmlPullParser

/** Reuses the actual E8.17 SVG artwork; design files are packaged as read-only assets. */
@Composable
fun ZxIcon(name: String, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val tint = ZhaoxiTheme.colors.ink2
    val path = remember(name, context) {
        require(name.matches(Regex("[a-z][a-z0-9-]{0,40}"))) { "Invalid icon name" }
        val parser = Xml.newPullParser()
        // A reader cannot be closed before XML traversal, so load text and parse in memory.
        val xml = context.assets.open("icons/$name.svg").bufferedReader().use { it.readText() }
        parser.setInput(xml.reader())
        val drawing = Path()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                fun a(key: String): Float =
                    parser.getAttributeValue(null, key)?.toFloatOrNull() ?: 0f
                when (parser.name) {
                    "path" -> parser.getAttributeValue(null, "d")
                        ?.takeIf(String::isNotBlank)
                        ?.let { PathParser.createPathFromPathData(it) }
                        ?.let(drawing::addPath)
                    "circle" -> drawing.addCircle(a("cx"), a("cy"), a("r"), Path.Direction.CW)
                    "rect" -> {
                        val x = a("x")
                        val y = a("y")
                        val radius = a("rx")
                        drawing.addRoundRect(x, y, x + a("width"), y + a("height"),
                            radius, radius, Path.Direction.CW)
                    }
                }
            }
            parser.next()
        }
        drawing
    }
    val semantic = if (description == null) modifier else modifier.semantics {
        contentDescription = description
    }
    Canvas(semantic.size(22.dp)) {
        val factor = size.minDimension / 24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = tint.toArgb()
            strokeWidth = 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        drawContext.canvas.nativeCanvas.apply {
            save()
            translate((size.width - factor * 24f) / 2f, (size.height - factor * 24f) / 2f)
            scale(factor, factor)
            drawPath(path, paint)
            restore()
        }
    }
}
