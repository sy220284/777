package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * V6.3 unified action glyph family (61 icons): 24dp optical viewport, 1.75 stroke,
 * rounded terminals, and theme tint supplied by Icon.
 *
 * The legacy FeatherIcons object name is retained for binary/source compatibility
 * with existing Compose callers; every path is a project-specific V6.3 redraw,
 * not a copy of the old mixed optical weights. Editable SVG counterparts:
 * docs/design/v6/assets/icons-actions/ (individual editable SVG files).
 */
internal object FeatherIcons {
    val Gauge: ImageVector by lazy { actionGlyph("Gauge",
        "M4 17 A9 9 0 0 1 20 17",
        "M12 16 L17 10",
        "M12 16 L12.1 16") }

    val CreditCard: ImageVector by lazy { actionGlyph("CreditCard",
        "M5 5.5 H19 Q21 5.5 21 7.5 V16.5 Q21 18.5 19 18.5 H5 Q3 18.5 3 16.5 V7.5 Q3 5.5 5 5.5 Z",
        "M3.5 10 H20.5",
        "M7 14.5 H11") }

    val MessageCopy: ImageVector by lazy { actionGlyph("MessageCopy",
        "M9 7 H18 Q20 7 20 9 V19 Q20 21 18 21 H9 Q7 21 7 19 V9 Q7 7 9 7 Z",
        "M16 7 V5 Q16 3 14 3 H5 Q3 3 3 5 V14 Q3 16 5 16 H7") }

    val Retry: ImageVector by lazy { actionGlyph("Retry",
        "M20 8 V4 L16 4",
        "M20 4 C16.8 2.1 11.8 2.2 8.3 5 C6.7 6.3 5.7 7.9 5.2 9.5",
        "M4 16 V20 H8",
        "M4 20 C7.1 21.8 12.2 21.8 15.8 19 C17.5 17.6 18.7 15.6 19 13.5") }

    val RemoteControl: ImageVector by lazy { actionGlyph("RemoteControl",
        "M4.5 5 H17.5 Q19 5 19 6.5 V16 Q19 17.5 17.5 17.5 H4.5 Q3 17.5 3 16 V6.5 Q3 5 4.5 5 Z",
        "M11 17.5 V21 M7.5 21 H14.5",
        "M19 5 C20.6 6 21.5 7.6 21.5 9.5") }

    val SettingsOutline: ImageVector by lazy { actionGlyph("SettingsOutline",
        "M10 3.2 H14 L14.7 5.1 L16.7 6.2 L18.7 5.8 L20.6 9.1 L19.1 10.6 V13.4 L20.6 14.9 L18.7 18.2 L16.7 17.8 L14.7 18.9 L14 20.8 H10 L9.3 18.9 L7.3 17.8 L5.3 18.2 L3.4 14.9 L4.9 13.4 V10.6 L3.4 9.1 L5.3 5.8 L7.3 6.2 L9.3 5.1 Z",
        "M12 8.9 A3.1 3.1 0 1 0 12 15.1 A3.1 3.1 0 1 0 12 8.9") }

    val Terminal: ImageVector by lazy { actionGlyph("Terminal",
        "M4 6 L10 12 L4 18",
        "M12.5 18 H20") }

    val FileText: ImageVector by lazy { actionGlyph("FileText",
        "M7 3 H15 L20 8 V19 Q20 21 18 21 H7 Q5 21 5 19 V5 Q5 3 7 3 Z",
        "M15 3 V8 H20",
        "M8.5 12 H16.5 M8.5 16 H16.5") }

    val Edit3: ImageVector by lazy { actionGlyph("Edit3",
        "M4 20 L8.8 18.8 L20 7.6 Q21.3 6.3 20 5 L19 4 Q17.7 2.7 16.4 4 L5.2 15.2 Z",
        "M14.5 6 L18 9.5",
        "M11.5 20 H20") }

    val Search: ImageVector by lazy { actionGlyph("Search",
        "M10.7 4.4 A6.4 6.4 0 1 0 10.7 17.2 A6.4 6.4 0 1 0 10.7 4.4",
        "M15.5 15.5 L20.5 20.5") }

    val Globe: ImageVector by lazy { actionGlyph("Globe",
        "M12 3.2 A8.8 8.8 0 1 0 12 20.8 A8.8 8.8 0 1 0 12 3.2",
        "M3.6 12 H20.4",
        "M12 3.4 C8.3 7 8.3 17 12 20.6 M12 3.4 C15.7 7 15.7 17 12 20.6") }

    val Tool: ImageVector by lazy { actionGlyph("Tool",
        "M19.8 6.5 L17 9.3 L14.7 8.6 L14 6.3 L16.8 3.5 C14.2 2.8 11.2 3.6 9.8 5.7 C8.7 7.4 8.5 9.4 9.3 11 L4 16.3 Q2.9 17.4 4 18.5 L5.5 20 Q6.6 21.1 7.7 20 L13 14.7 C14.6 15.5 16.6 15.3 18.3 14.2 C20.4 12.8 21.2 9.8 20.5 7.2") }

    val Code: ImageVector by lazy { actionGlyph("Code",
        "M9.5 6.5 L4.3 12 L9.5 17.5",
        "M14.5 6.5 L19.7 12 L14.5 17.5",
        "M13 5 L11 19") }

    val GitBranch: ImageVector by lazy { actionGlyph("GitBranch",
        "M7 5 V15 Q7 19 11 19 H17",
        "M7 8 A2.5 2.5 0 1 0 7 3 A2.5 2.5 0 1 0 7 8",
        "M18 9 A2.5 2.5 0 1 0 18 4 A2.5 2.5 0 1 0 18 9",
        "M18 21 A2.5 2.5 0 1 0 18 16 A2.5 2.5 0 1 0 18 21",
        "M7 12 H15 Q18 12 18 9") }

    val CheckSquare: ImageVector by lazy { actionGlyph("CheckSquare",
        "M9 4 H18 Q20 4 20 6 V18 Q20 20 18 20 H6 Q4 20 4 18 V6 Q4 4 6 4 H7",
        "M8 12.2 L10.8 15 L17 8.8") }

    val Archive: ImageVector by lazy { actionGlyph("Archive",
        "M4 4.5 H20 Q21 4.5 21 6 V9 Q21 10 20 10 H4 Q3 10 3 9 V6 Q3 4.5 4 4.5 Z",
        "M5 10 V19 Q5 20.5 6.5 20.5 H17.5 Q19 20.5 19 19 V10",
        "M10 14 H14") }

    val AlertTriangle: ImageVector by lazy { actionGlyph("AlertTriangle",
        "M10.4 4.8 Q12 2.3 13.6 4.8 L21 17.7 Q22.2 20 19.5 20 H4.5 Q1.8 20 3 17.7 Z",
        "M12 9 V13.2 M12 16.8 V16.9") }

    val Info: ImageVector by lazy { actionGlyph("Info",
        "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3",
        "M12 11 V16.5 M12 7.8 V8") }

    val Menu: ImageVector by lazy { actionGlyph("Menu",
        "M4.5 6 H19.5 M4.5 12 H19.5 M4.5 18 H16") }

    val User: ImageVector by lazy { actionGlyph("User",
        "M12 4.4 A3.6 3.6 0 1 0 12 11.6 A3.6 3.6 0 1 0 12 4.4",
        "M5.6 20 V18.8 Q5.6 15.3 9.2 14.8 H14.8 Q18.4 15.3 18.4 18.8 V20") }

    val MessageCircle: ImageVector by lazy { actionGlyph("MessageCircle",
        "M12 3.6 C7.2 3.6 3.8 7 3.8 11.4 Q3.8 14.3 5.8 16.5 L5.2 20.2 L9.2 18.3 Q10.5 18.8 12 18.8 C16.8 18.8 20.2 15.7 20.2 11.4 C20.2 7 16.8 3.6 12 3.6 Z") }

    val Bell: ImageVector by lazy { actionGlyph("Bell",
        "M7.2 9 Q7.2 4.8 12 4.8 Q16.8 4.8 16.8 9 V14.8 L19.3 17.5 H4.7 L7.2 14.8 Z",
        "M10.1 20 H13.9") }

    val Paperclip: ImageVector by lazy { actionGlyph("Paperclip",
        "M9.1 12.8 L14.9 7 Q16.7 5.2 18.5 7 Q20.3 8.8 18.5 10.6 L10.7 18.4 Q7.4 21.7 4.8 19.1 Q2.2 16.5 5.5 13.2 L13.8 4.9") }

    val Sparkles: ImageVector by lazy { actionGlyph("Sparkles",
        "M11.3 3.5 L13.2 9.2 L18.9 11.1 L13.2 13 L11.3 18.7 L9.4 13 L3.7 11.1 L9.4 9.2 Z",
        "M19.2 17.2 L19.8 19 L21.6 19.6 L19.8 20.2 L19.2 22 L18.6 20.2 L16.8 19.6 L18.6 19 Z") }

    val Plus: ImageVector by lazy { actionGlyph("Plus",
        "M12 4.5 V19.5 M4.5 12 H19.5") }

    val Users: ImageVector by lazy { actionGlyph("Users",
        "M9 5 A3 3 0 1 0 9 11 A3 3 0 1 0 9 5",
        "M3.6 19.3 V18 Q3.6 14 7.8 13.7 H10.2 Q14.4 14 14.4 18 V19.3",
        "M16.3 5.5 C19.2 5.4 20.1 9.2 18.1 10.9",
        "M16.4 13.6 Q20.5 13.9 20.5 18") }

    val Video: ImageVector by lazy { actionGlyph("Video",
        "M4.8 6.5 H15.2 Q17 6.5 17 8.3 V15.7 Q17 17.5 15.2 17.5 H4.8 Q3 17.5 3 15.7 V8.3 Q3 6.5 4.8 6.5 Z",
        "M17 10 L21 7.5 V16.5 L17 14") }

    val Image: ImageVector by lazy { actionGlyph("Image",
        "M5 4.5 H19 Q20.5 4.5 20.5 6 V18 Q20.5 19.5 19 19.5 H5 Q3.5 19.5 3.5 18 V6 Q3.5 4.5 5 4.5 Z",
        "M8 9 A1.3 1.3 0 1 0 8 11.6 A1.3 1.3 0 1 0 8 9",
        "M4.5 17.5 L9.2 12.8 L12.5 15.5 L15.8 11.5 L20 16.3") }

    val BookOpen: ImageVector by lazy { actionGlyph("BookOpen",
        "M12 6.2 Q8.5 4 4 4.8 V18 Q8.5 17.3 12 19.3 Q15.5 17.3 20 18 V4.8 Q15.5 4 12 6.2 Z",
        "M12 6.2 V19.3") }

    val Zap: ImageVector by lazy { actionGlyph("Zap",
        "M13.2 3 L5 13 H11.3 L10.8 21 L19 11 H12.7 Z") }

    val Clock: ImageVector by lazy { actionGlyph("Clock",
        "M12 3.4 A8.6 8.6 0 1 0 12 20.6 A8.6 8.6 0 1 0 12 3.4",
        "M12 7.2 V12.3 L15.7 14.5") }

    val Folder: ImageVector by lazy { actionGlyph("Folder",
        "M5 6 H9 L11.4 8.2 H19 Q21 8.2 21 10.2 V17.8 Q21 19.5 19.3 19.5 H4.7 Q3 19.5 3 17.8 V8 Q3 6 5 6 Z") }

    val Activity: ImageVector by lazy { actionGlyph("Activity",
        "M3 12 H7.4 L10 5.2 L14 18.8 L16.7 12 H21") }

    val Device: ImageVector by lazy { actionGlyph("Device",
        "M7 3.5 H17 Q18.5 3.5 18.5 5 V19 Q18.5 20.5 17 20.5 H7 Q5.5 20.5 5.5 19 V5 Q5.5 3.5 7 3.5 Z",
        "M10 17.8 H14") }

    val Sliders: ImageVector by lazy { actionGlyph("Sliders",
        "M4 6 H8 M13 6 H20",
        "M4 12 H14 M19 12 H20",
        "M4 18 H7 M12 18 H20",
        "M8 4 V8 M13 4 V8",
        "M14 10 V14 M19 10 V14",
        "M7 16 V20 M12 16 V20") }

    val Eye: ImageVector by lazy { actionGlyph("Eye",
        "M3 12 Q12 2.5 21 12 Q12 21.5 3 12 Z",
        "M12 9 A3 3 0 1 0 12 15 A3 3 0 1 0 12 9") }

    val SunMoon: ImageVector by lazy { actionGlyph("SunMoon",
        "M12.3 3.6 A8.4 8.4 0 0 0 12.3 20.4 A7.2 7.2 0 0 0 20.2 15.2 Q14 16.7 12.3 10.7 Q11.1 6.9 12.3 3.6 Z",
        "M17.5 4 V7 M16 5.5 H19") }

    val Palette: ImageVector by lazy { actionGlyph("Palette",
        "M12 3.7 A8.4 8.4 0 1 0 12 20.5 H14.3 Q16.1 20.5 16.1 18.7 Q16.1 17.3 14.7 16.8 Q13.7 16.4 13.7 15.4 Q13.7 14.2 15.1 14.2 H18.2 Q20.4 14.2 20.4 12 A8.3 8.3 0 0 0 12 3.7 Z",
        "M7.5 10 V10.1 M11 7.2 V7.3 M16 9 V9.1 M8.8 15.5 V15.6") }

    val Gear: ImageVector by lazy { actionGlyph("Gear",
        "M10 3.2 H14 L14.7 5.1 L16.7 6.2 L18.7 5.8 L20.6 9.1 L19.1 10.6 V13.4 L20.6 14.9 L18.7 18.2 L16.7 17.8 L14.7 18.9 L14 20.8 H10 L9.3 18.9 L7.3 17.8 L5.3 18.2 L3.4 14.9 L4.9 13.4 V10.6 L3.4 9.1 L5.3 5.8 L7.3 6.2 L9.3 5.1 Z",
        "M12 8.9 A3.1 3.1 0 1 0 12 15.1 A3.1 3.1 0 1 0 12 8.9") }

    val Pin: ImageVector by lazy { actionGlyph("Pin",
        "M7 3.8 H17 L15 9.4 L17.7 12.8 V14.5 H6.3 V12.8 L9 9.4 Z",
        "M12 14.5 V21") }

    val Trash2: ImageVector by lazy { actionGlyph("Trash2",
        "M4.5 7 H19.5",
        "M7.5 7 V19 Q7.5 20.5 9 20.5 H15 Q16.5 20.5 16.5 19 V7",
        "M9.5 7 V4.5 Q9.5 3.5 10.5 3.5 H13.5 Q14.5 3.5 14.5 4.5 V7",
        "M10.3 10.5 V17 M13.7 10.5 V17") }

    val X: ImageVector by lazy { actionGlyph("X",
        "M5.5 5.5 L18.5 18.5 M18.5 5.5 L5.5 18.5") }

    val MoreVertical: ImageVector by lazy { actionGlyph("MoreVertical",
        "M12 5 V5.1 M12 12 V12.1 M12 19 V19.1") }

    val Download: ImageVector by lazy { actionGlyph("Download",
        "M12 4 V15",
        "M7.5 11 L12 15.5 L16.5 11",
        "M4.5 17.5 V19.5 Q4.5 20.5 5.5 20.5 H18.5 Q19.5 20.5 19.5 19.5 V17.5") }

    val ArrowUpDown: ImageVector by lazy { actionGlyph("ArrowUpDown",
        "M7 19 V5 M3.5 8.5 L7 5 L10.5 8.5",
        "M17 5 V19 M13.5 15.5 L17 19 L20.5 15.5") }

    val RefreshCw: ImageVector by lazy { actionGlyph("RefreshCw",
        "M20 8 V4.5 H16.5",
        "M20 4.5 C16.6 2.8 12.2 3.1 9 5.4",
        "M4 16 V19.5 H7.5",
        "M4 19.5 C7.3 21.2 12 20.9 15 18.5",
        "M4.1 10.1 C4.7 8.2 5.9 6.7 7.5 5.6 M19.9 13.9 C19.3 15.8 18.1 17.3 16.5 18.4") }

    val ArrowLeft: ImageVector by lazy { actionGlyph("ArrowLeft",
        "M19.5 12 H4.5",
        "M10.5 6 L4.5 12 L10.5 18") }

    val ChevronLeft: ImageVector by lazy { actionGlyph("ChevronLeft",
        "M15.5 5.5 L9 12 L15.5 18.5") }

    val Camera: ImageVector by lazy { actionGlyph("Camera",
        "M5 7 H8 L9.5 4.5 H14.5 L16 7 H19 Q20.5 7 20.5 8.5 V18 Q20.5 19.5 19 19.5 H5 Q3.5 19.5 3.5 18 V8.5 Q3.5 7 5 7 Z",
        "M12 10 A3 3 0 1 0 12 16 A3 3 0 1 0 12 10") }

    val ChevronDown: ImageVector by lazy { actionGlyph("ChevronDown",
        "M5.5 9 L12 15.5 L18.5 9") }

    val ChevronRight: ImageVector by lazy { actionGlyph("ChevronRight",
        "M8.5 5.5 L15 12 L8.5 18.5") }

    val CheckCircle: ImageVector by lazy { actionGlyph("CheckCircle",
        "M12 3.5 A8.5 8.5 0 1 0 12 20.5 A8.5 8.5 0 1 0 12 3.5",
        "M8 12.3 L10.8 15 L16.5 9.3") }

    val Check: ImageVector by lazy { actionGlyph("Check",
        "M4.5 12.5 L9.5 17.5 L19.5 6.5") }

    val ArrowDown: ImageVector by lazy { actionGlyph("ArrowDown",
        "M12 4.5 V19.5",
        "M6 13.5 L12 19.5 L18 13.5") }

    val Copy: ImageVector by lazy { actionGlyph("Copy",
        "M9 7 H18 Q20 7 20 9 V19 Q20 21 18 21 H9 Q7 21 7 19 V9 Q7 7 9 7 Z",
        "M16 7 V5 Q16 3 14 3 H5 Q3 3 3 5 V14 Q3 16 5 16 H7") }

    val ThumbsUp: ImageVector by lazy { actionGlyph("ThumbsUp",
        "M9 10 L12 4 Q12.6 2.8 14 3.5 Q15 4 15 5.3 L14.4 9 H19 Q21.2 9 20.5 11.1 L18.5 18.6 Q18.2 20 16.5 20 H9 Z",
        "M4.5 10 H9 V20 H4.5 Q3.5 20 3.5 19 V11 Q3.5 10 4.5 10 Z") }

    val ThumbsDown: ImageVector by lazy { actionGlyph("ThumbsDown",
        "M9 14 L12 20 Q12.6 21.2 14 20.5 Q15 20 15 18.7 L14.4 15 H19 Q21.2 15 20.5 12.9 L18.5 5.4 Q18.2 4 16.5 4 H9 Z",
        "M4.5 4 H9 V14 H4.5 Q3.5 14 3.5 13 V5 Q3.5 4 4.5 4 Z") }

    val ArrowUp: ImageVector by lazy { actionGlyph("ArrowUp",
        "M12 19.5 V4.5",
        "M6 10.5 L12 4.5 L18 10.5") }

    val Square: ImageVector by lazy { actionGlyph("Square",
        "M5 4.5 H19 Q20 4.5 20 5.5 V18.5 Q20 19.5 19 19.5 H5 Q4 19.5 4 18.5 V5.5 Q4 4.5 5 4.5 Z") }

    val Shield: ImageVector by lazy { actionGlyph("Shield",
        "M12 3.2 L19.5 6 V11.4 C19.5 15.4 17 18.2 12 20.9 C7 18.2 4.5 15.4 4.5 11.4 V6 Z",
        "M8.8 12 L11 14.2 L15.5 9.7") }

    val List: ImageVector by lazy { actionGlyph("List",
        "M8.5 6 H20 M8.5 12 H20 M8.5 18 H20",
        "M4.2 6 H4.4 M4.2 12 H4.4 M4.2 18 H4.4") }

}

private fun actionGlyph(name: String, vararg strokes: String): ImageVector =
    ImageVector.Builder(
        name = "Feather.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        strokes.forEach { pathString ->
            addPath(
                pathData = PathParser().parsePathString(pathString).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
