package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** V6.2 unified optical glyphs: 24dp, stroke 1.75, round caps and theme tint applied by caller. */
object SyEntryIcons {
    val Persona: ImageVector by lazy { entryGlyph("Persona",
        "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8",
        "M5 20v-1.2A5.8 5.8 0 0 1 10.8 13h2.4A5.8 5.8 0 0 1 19 18.8V20") }
    val NewSession: ImageVector by lazy { entryGlyph("NewSession",
        "M20 11.5a7.7 7.7 0 0 1-8.5 7.8 8 8 0 0 1-3.8-1.2L4 20l1-4.2A8 8 0 1 1 20 11.5",
        "M12 7.5v7",
        "M8.5 11h7") }
    val Tasks: ImageVector by lazy { entryGlyph("Tasks",
        "M8 4.8h8a3 3 0 0 1 3 3v11.4a1.8 1.8 0 0 1-1.8 1.8H6.8A1.8 1.8 0 0 1 5 19.2V7.8a3 3 0 0 1 3-3Z",
        "M9 4.8V3.7h6v1.1",
        "m8.5 13.3 2.4 2.4 4.7-5") }
    val Settings: ImageVector by lazy { entryGlyph("Settings",
        "M10 3.4h4l.8 2.2 1.9.8 2.1-.8 2.6 2.6-.8 2.1.8 1.9 2.2.8v3.8l-2.2.8-.8 1.9.8 2.1-2.6 2.6-2.1-.8-1.9.8-.8 2.2h-4l-.8-2.2-1.9-.8-2.1.8-2.6-2.6.8-2.1-.8-1.9-2.2-.8v-3.8l2.2-.8.8-1.9-.8-2.1L7.4 5.8l2.1.8 1.9-.8Z",
        "M12 15.1a3.1 3.1 0 1 0 0-6.2 3.1 3.1 0 0 0 0 6.2Z") }
    val Search: ImageVector by lazy { entryGlyph("Search",
        "M10.6 17.3a6.7 6.7 0 1 0 0-13.4 6.7 6.7 0 0 0 0 13.4Z",
        "m15.5 15.5 5 5") }
    val Gallery: ImageVector by lazy { entryGlyph("Gallery",
        "M5 7h13.5A1.5 1.5 0 0 1 20 8.5v10A1.5 1.5 0 0 1 18.5 20h-13A1.5 1.5 0 0 1 4 18.5v-10A1.5 1.5 0 0 1 5.5 7Z",
        "M8 7V5.5A1.5 1.5 0 0 1 9.5 4h10A1.5 1.5 0 0 1 21 5.5v10",
        "M12 13a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z",
        "M8 17a4 4 0 0 1 8 0") }
    val GroupChat: ImageVector by lazy { entryGlyph("GroupChat",
        "M9 11a3.3 3.3 0 1 0 0-6.6 3.3 3.3 0 0 0 0 6.6Z",
        "M3.6 20v-1.5a5.4 5.4 0 0 1 10.8 0V20",
        "M16 5.6a3 3 0 0 1 0 5.6",
        "M16.8 14a4.8 4.8 0 0 1 3.6 4.6V20") }
    val Diary: ImageVector by lazy { entryGlyph("Diary",
        "M7 3.8h10A2 2 0 0 1 19 5.8v13.4A1.8 1.8 0 0 1 17.2 21H7a2 2 0 0 1-2-2V5.8a2 2 0 0 1 2-2Z",
        "M8 3.8v17",
        "M11.5 9h4",
        "M11.5 13h4") }
    val WorkspaceFiles: ImageVector by lazy { entryGlyph("WorkspaceFiles",
        "M4 7.5A2 2 0 0 1 6 5.5h4l2 2H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z") }
    val Tools: ImageVector by lazy { entryGlyph("Tools",
        "M14.5 5.8a4.4 4.4 0 0 0-5.2 5.6L4 16.7a2.1 2.1 0 0 0 3 3l5.3-5.3a4.4 4.4 0 0 0 5.6-5.2l-3 2.1-2.1-2.1Z") }
    val Usage: ImageVector by lazy { entryGlyph("Usage",
        "M5 19V12",
        "M10 19V7",
        "M15 19V10",
        "M20 19V4",
        "M3.5 20.5h18") }
    val Reader: ImageVector by lazy { entryGlyph("Reader",
        "M12 6.5C9 4.8 6.8 4.7 4 5.5V19c3-.9 5.5-.6 8 1 2.5-1.6 5-1.9 8-1V5.5c-2.8-.8-5-.7-8 1Z",
        "M12 6.5V20") }
    val Quota: ImageVector by lazy { entryGlyph("Quota",
        "M4 8h16v11.5H4Z",
        "M4 10.3h16",
        "M7 15.2h4",
        "M16.5 13.2l1.6 1.5-1.6 1.5-1.6-1.5Z") }
    val Appearance: ImageVector by lazy { entryGlyph("Appearance",
        "M12 3.3a8.7 8.7 0 1 0 0 17.4h1a2 2 0 0 0 1.7-2.8c-.7-1.6-.2-2.8 1.8-2.8h1A3.3 3.3 0 0 0 20.7 12 8.7 8.7 0 0 0 12 3.3Z",
        "M7.7 10h.2",
        "M11.5 6.9h.2",
        "M16.6 10.2h.2",
        "M8.5 15h.2") }
    val Memory: ImageVector by lazy { entryGlyph("Memory",
        "M5.5 8c0-2.7 2.2-4.5 4.5-4.2a4.3 4.3 0 0 1 7.4 2.8A4.2 4.2 0 0 1 19 14c-.3 2.3-2.2 3.7-4.5 3.7H8.7A5.2 5.2 0 0 1 5.5 8Z",
        "M8 17.8v2.1",
        "M12 17.8V21",
        "M16 17.8v2.1") }
    val SessionStorage: ImageVector by lazy { entryGlyph("SessionStorage",
        "M4 5h16v6H4Z",
        "M5 13h14v7H5Z",
        "M9 8h6",
        "M9 16.5h6") }
    val Models: ImageVector by lazy { entryGlyph("Models",
        "M12 3 20 7.5v9L12 21l-8-4.5v-9Z",
        "M12 12 4 7.5",
        "m12 12 8-4.5",
        "M12 12v9") }
    val Performance: ImageVector by lazy { entryGlyph("Performance",
        "M3 12h4l2.5-7 4.3 14 2.8-7H21") }
    val Account: ImageVector by lazy { entryGlyph("Account",
        "M12 11.4a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z",
        "M5 20v-1a7 7 0 0 1 14 0v1") }
    val Notifications: ImageVector by lazy { entryGlyph("Notifications",
        "M6 17h12l-1.7-2.2V10a4.3 4.3 0 0 0-8.6 0v4.8Z",
        "M10 19a2 2 0 0 0 4 0") }
    val Permissions: ImageVector by lazy { entryGlyph("Permissions",
        "M12 3 19 6v6c0 4-2.6 6.7-7 9-4.4-2.3-7-5-7-9V6Z",
        "m9 12 2 2 4-4") }
    val Advanced: ImageVector by lazy { entryGlyph("Advanced",
        "M4 6h16",
        "M4 12h16",
        "M4 18h16",
        "M9 4v4",
        "M15 10v4",
        "M10 16v4") }
    val Updates: ImageVector by lazy { entryGlyph("Updates",
        "M19.7 8.5A8 8 0 0 0 5.3 7",
        "M5.3 4.5V8h3.5",
        "M4.3 15.5A8 8 0 0 0 18.7 17",
        "M18.7 20v-3.5h-3.5") }
    val ChatStyle: ImageVector by lazy { entryGlyph("ChatStyle",
        "M5.3 5.3h13.4A1.8 1.8 0 0 1 20.5 7v9.2a1.8 1.8 0 0 1-1.8 1.8h-8L5 21v-3.3A1.8 1.8 0 0 1 3.5 16V7a1.8 1.8 0 0 1 1.8-1.7Z",
        "M8 10h9",
        "M8 13.5h6") }
    val Projects: ImageVector by lazy { entryGlyph("Projects",
        "M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z") }
    val RunCenter: ImageVector by lazy { entryGlyph("RunCenter",
        "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z",
        "m10 8 6 4-6 4Z") }
}
private fun entryGlyph(name: String, vararg data: String): ImageVector =
    ImageVector.Builder(
        name = "SyEntry.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        data.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
