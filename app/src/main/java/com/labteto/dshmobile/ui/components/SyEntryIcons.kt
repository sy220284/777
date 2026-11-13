package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 777 暖纸墨线图标。路径与 docs/design/v6/paper-ink-icon-catalog.json 同源。
 * 24dp / 1.75px / 圆端圆角。背景色由承托组件根据主题决定。
 */
internal object SyEntryIcons {
    val Persona: ImageVector by lazy { entryGlyph("Persona",
        "M12 12a3.6 3.6 0 1 0 0-7.2 3.6 3.6 0 0 0 0 7.2Z",
        "M4.8 20v-1.1a7.2 7.2 0 0 1 14.4 0V20") }

    val NewSession: ImageVector by lazy { entryGlyph("NewSession",
        "M12 3.4a8.6 8.6 0 1 0 0 17.2 8.6 8.6 0 0 0 0-17.2Z",
        "M12 7.5v9",
        "M7.5 12h9") }

    val Tasks: ImageVector by lazy { entryGlyph("Tasks",
        "M7 4.5h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-12a2 2 0 0 1 2-2Z",
        "M8.3 9.2l1.5 1.5 2.2-2.5",
        "M13.7 9.5h2.2",
        "M8.3 15.2l1.5 1.5 2.2-2.5",
        "M13.7 15.5h2.2") }

    val Settings: ImageVector by lazy { entryGlyph("Settings",
        "M10 3.5h4l.6 2.1 1.8.8 1.9-.9 2.2 2.2-.9 1.9.8 1.8 2.1.6v3l-2.1.6-.8 1.8.9 1.9-2.2 2.2-1.9-.9-1.8.8-.6 2.1h-4l-.6-2.1-1.8-.8-1.9.9-2.2-2.2.9-1.9-.8-1.8-2.1-.6v-3l2.1-.6.8-1.8-.9-1.9 2.2-2.2 1.9.9 1.8-.8Z",
        "M12 15.1a3.1 3.1 0 1 0 0-6.2 3.1 3.1 0 0 0 0 6.2Z") }

    val Search: ImageVector by lazy { entryGlyph("Search",
        "M10.6 4.3a6.3 6.3 0 1 0 0 12.6 6.3 6.3 0 0 0 0-12.6Z",
        "m15.3 15.3 5.1 5.1") }

    val Gallery: ImageVector by lazy { entryGlyph("Gallery",
        "M5.5 6.5h13a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18V8a1.5 1.5 0 0 1 1.5-1.5Z",
        "M7.5 6.5V5a1.5 1.5 0 0 1 1.5-1.5h10A1.5 1.5 0 0 1 20.5 5v10",
        "M12 13a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z",
        "M8 17.3a4 4 0 0 1 8 0") }

    val GroupChat: ImageVector by lazy { entryGlyph("GroupChat",
        "M9 11.5a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8Z",
        "M3.5 20v-1.2a5.5 5.5 0 0 1 11 0V20",
        "M16 5.5a3 3 0 0 1 0 5.8",
        "M17 14.2a4.6 4.6 0 0 1 3.5 4.5V20") }

    val Diary: ImageVector by lazy { entryGlyph("Diary",
        "M12 6c-2.8-1.8-5.2-2.1-8-1.1v14c3-.8 5.6-.4 8 1.2 2.4-1.6 5-2 8-1.2v-14c-2.8-1-5.2-.7-8 1.1Z",
        "M12 6v14.1",
        "M6.5 9h3",
        "M14.5 9h3") }

    val WorkspaceFiles: ImageVector by lazy { entryGlyph("WorkspaceFiles",
        "M4.3 7h5.4l2.1 2h7.9a1.8 1.8 0 0 1 1.8 1.8v7.4a1.8 1.8 0 0 1-1.8 1.8H4.3a1.8 1.8 0 0 1-1.8-1.8V8.8A1.8 1.8 0 0 1 4.3 7Z") }

    val Tools: ImageVector by lazy { entryGlyph("Tools",
        "M4.6 8.5h14.8a1.8 1.8 0 0 1 1.8 1.8v8a1.8 1.8 0 0 1-1.8 1.8H4.6a1.8 1.8 0 0 1-1.8-1.8v-8a1.8 1.8 0 0 1 1.8-1.8Z",
        "M8.4 8.5V6.3A2.3 2.3 0 0 1 10.7 4h2.6a2.3 2.3 0 0 1 2.3 2.3v2.2",
        "M2.8 13.5h18.4",
        "M10.3 13.5v2.3h3.4v-2.3") }

    val Usage: ImageVector by lazy { entryGlyph("Usage",
        "M4.5 20V14h3v6",
        "M10.5 20V8h3v12",
        "M16.5 20V11h3v9",
        "M3.5 20.5h17") }

    val Reader: ImageVector by lazy { entryGlyph("Reader",
        "M12 6.4C9 4.6 6.6 4.5 3.7 5.2V19c3.2-.8 5.7-.4 8.3 1.1 2.6-1.5 5.1-1.9 8.3-1.1V5.2c-2.9-.7-5.3-.6-8.3 1.2Z",
        "M12 6.4v13.7") }

    val Quota: ImageVector by lazy { entryGlyph("Quota",
        "M4 7h15a1.7 1.7 0 0 1 1.7 1.7v9.6a1.7 1.7 0 0 1-1.7 1.7H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h12",
        "M15.6 12h5v4.5h-5a2.2 2.2 0 0 1-2.2-2.2v-.1a2.2 2.2 0 0 1 2.2-2.2Z") }

    val Appearance: ImageVector by lazy { entryGlyph("Appearance",
        "M12 3.5a8.5 8.5 0 1 0 0 17h1.2a2 2 0 0 0 1.6-3.1 1.9 1.9 0 0 1 1.5-3h2A2.7 2.7 0 0 0 21 11.6 8.5 8.5 0 0 0 12 3.5Z",
        "M8 10h.1",
        "M11.5 7.1h.1",
        "M16 9.5h.1",
        "M8.5 15h.1") }

    val Memory: ImageVector by lazy { entryGlyph("Memory",
        "M6.5 8a3.7 3.7 0 0 1 6-3 3.6 3.6 0 0 1 6 3.1 3.8 3.8 0 0 1 1.3 6.4A3.8 3.8 0 0 1 17 19h-4.3a3 3 0 0 1-3-3v-1.5",
        "M12.5 5v14",
        "M7.2 12a3 3 0 0 0 3.1 2.3",
        "M16.9 11.5a3.5 3.5 0 0 1-4.4 1.6") }

    val SessionStorage: ImageVector by lazy { entryGlyph("SessionStorage",
        "M4 4.5h16v6H4Z",
        "M4.5 13.5h15v6h-15Z",
        "M10 7.5h4",
        "M10 16.5h4") }

    val Models: ImageVector by lazy { entryGlyph("Models",
        "M12 3 20 7.5v9L12 21l-8-4.5v-9Z",
        "M4 7.5l8 4.5 8-4.5",
        "M12 12v9") }

    val Performance: ImageVector by lazy { entryGlyph("Performance",
        "M3 12h4l2.5-6.5 4 13 2.8-6.5H21") }

    val Account: ImageVector by lazy { entryGlyph("Account",
        "M12 11.7a3.6 3.6 0 1 0 0-7.2 3.6 3.6 0 0 0 0 7.2Z",
        "M5 20v-1a7 7 0 0 1 14 0v1") }

    val Notifications: ImageVector by lazy { entryGlyph("Notifications",
        "M5.5 17.5h13l-2.1-2.8V10a4.4 4.4 0 0 0-8.8 0v4.7Z",
        "M10.5 20a1.5 1.5 0 0 0 3 0") }

    val Permissions: ImageVector by lazy { entryGlyph("Permissions",
        "M12 3.2l7.3 3v5.5c0 4.5-2.6 7.4-7.3 9.6-4.7-2.2-7.3-5.1-7.3-9.6V6.2Z",
        "m9 12 2 2 4.5-4.5") }

    val Advanced: ImageVector by lazy { entryGlyph("Advanced",
        "M4 6h16",
        "M4 12h16",
        "M4 18h16",
        "M9 3.8v4.4",
        "M15 9.8v4.4",
        "M10 15.8v4.4") }

    val Updates: ImageVector by lazy { entryGlyph("Updates",
        "M20 8V4h-4",
        "M20 4a8 8 0 0 0-13.8 2.9",
        "M4 16v4h4",
        "M4 20a8 8 0 0 0 13.8-2.9") }

    val ChatStyle: ImageVector by lazy { entryGlyph("ChatStyle",
        "M5.5 4.5h13a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-7L6 21v-3.5h-.5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2Z",
        "M8.2 11h.1",
        "M12 11h.1",
        "M15.8 11h.1") }

    val Connections: ImageVector by lazy { entryGlyph("Connections",
        "M10.2 13.8l3.6-3.6",
        "M8.6 15.4 6.7 17.3a3.2 3.2 0 0 1-4.5-4.5l4.1-4.1a3.2 3.2 0 0 1 4.5 0",
        "m15.4 8.6 1.9-1.9a3.2 3.2 0 0 1 4.5 4.5l-4.1 4.1a3.2 3.2 0 0 1-4.5 0") }

    val Skills: ImageVector by lazy { entryGlyph("Skills",
        "M12 3 20.5 7.5 12 12 3.5 7.5Z",
        "M5.7 9.1v6.4a7.3 7.3 0 0 0 12.6 0V9.1",
        "M20.5 7.5v8.2") }

    val Plugins: ImageVector by lazy { entryGlyph("Plugins",
        "M8.8 4.5h3.1a2.8 2.8 0 1 1 5.6 0H20v5.2a2.8 2.8 0 1 1 0 5.6V20h-5.2a2.8 2.8 0 1 1-5.6 0H4v-5.2a2.8 2.8 0 1 0 0-5.6V4.5Z") }

    // 退役入口保留兼容定义，不再加入当前入口导航。
    val Projects: ImageVector by lazy { WorkspaceFiles }
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
