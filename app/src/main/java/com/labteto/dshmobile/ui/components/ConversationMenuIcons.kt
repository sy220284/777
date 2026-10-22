package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Conversation overflow actions in the V6 paper-ink language:
 * 24dp grid, 1.75 stroke, rounded terminals. The menu owns colors and touch targets.
 */
internal object ConversationMenuIcons {
    val WorkEvidence: ImageVector get() = SyEntryIcons.Tasks

    val InteractiveDocument: ImageVector by lazy { menuGlyph("InteractiveDocument",
        "M6 3.5 H15 L19.5 8 V19 Q19.5 20.5 18 20.5 H6 Q4.5 20.5 4.5 19 V5 Q4.5 3.5 6 3.5 Z",
        "M15 3.5 V8 H19.5",
        "M8 11.5 H11.5 V15.5 H16",
        "M8 15.5 H9.3",
        "M16 14 V17") }

    val Rename: ImageVector by lazy { menuGlyph("Rename",
        "M5 5 H14 L18 9 V12",
        "M14 5 V9 H18",
        "M5 5 V18.5 Q5 20 6.5 20 H10",
        "M11.8 18 L17.7 12.1 Q18.5 11.3 19.3 12.1 L20.5 13.3 Q21.3 14.1 20.5 14.9 L14.6 20.8 L11 21.5 Z",
        "M16.5 13.3 L19.3 16.1") }

    val Pin: ImageVector by lazy { menuGlyph("Pin",
        "M9 4.5 H15 L15.5 9.3 L18 12 V13.5 H6 V12 L8.5 9.3 Z",
        "M12 13.5 V20.5",
        "M10 4.5 V3.5 H14 V4.5") }

    val Delete: ImageVector by lazy { menuGlyph("Delete",
        "M5 7 H19",
        "M8 7 L8.7 19 Q8.8 20.3 10.2 20.3 H13.8 Q15.2 20.3 15.3 19 L16 7",
        "M9.3 7 V5.2 Q9.3 4 10.5 4 H13.5 Q14.7 4 14.7 5.2 V7",
        "M10.5 10.5 V17 M13.5 10.5 V17") }
}

private fun menuGlyph(name: String, vararg strokes: String): ImageVector =
    ImageVector.Builder(
        name = "ConversationMenu.$name",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = strokes.flatMap { PathParser().parsePathString(it).toNodes() },
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
