package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 777 神言线刻功能图标。路径与 docs/design/v6/paper-ink-icon-catalog.json 同源。
 * 24dp / 1.75px / 圆端圆角。背景色由承托组件根据主题决定。
 */
internal object SyEntryIcons {
    val Persona: ImageVector by lazy { entryGlyph("Persona",
        "M9 10a3 3 0 1 0 6 0 3 3 0 0 0-6 0Z",
        "M7 20v-1a5 5 0 0 1 10 0v1",
        "M4 9V5h4M16 5h4v4") }

    val NewSession: ImageVector by lazy { entryGlyph("NewSession",
        "M13 5H7a3 3 0 0 0-3 3v7a3 3 0 0 0 3 3h1v3l4-3h5a3 3 0 0 0 3-3v-3",
        "M18 3v6m-3-3h6") }

    val Tasks: ImageVector by lazy { entryGlyph("Tasks",
        "M8 5H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2",
        "M9.5 3h5a1.5 1.5 0 0 1 1.5 1.5V7H8V4.5A1.5 1.5 0 0 1 9.5 3Z",
        "M8.5 13.5l2.1 2.1 4.9-5") }

    val Settings: ImageVector by lazy { entryGlyph("Settings",
        "M5 7h5m4 0h5M5 17h9m4 0h1",
        "M10 7a2 2 0 1 0 4 0 2 2 0 0 0-4 0Zm4 10a2 2 0 1 0 4 0 2 2 0 0 0-4 0Z") }

    val Search: ImageVector by lazy { entryGlyph("Search",
        "M4 9.5a5.5 5.5 0 1 0 11 0 5.5 5.5 0 0 0-11 0Z",
        "m14 14 6 6M7 9.5A2.5 2.5 0 0 1 9.5 7") }

    val Gallery: ImageVector by lazy { entryGlyph("Gallery",
        "M7 6V4h13v13h-2",
        "M4 8h13v12H4Z",
        "m4 17 4-4 3 3 2-2 4 4",
        "M13.5 11H14") }

    val GroupChat: ImageVector by lazy { entryGlyph("GroupChat",
        "M13 4H7a3 3 0 0 0-3 3v5a3 3 0 0 0 3 3v3l4-3h2a3 3 0 0 0 3-3V7a3 3 0 0 0-3-3Z",
        "M19 8v8a2 2 0 0 1-2 2h-4l-3 2") }

    val Diary: ImageVector by lazy { entryGlyph("Diary",
        "M6 4h11a2 2 0 0 1 2 2v14H7a3 3 0 0 1-3-3V6a2 2 0 0 1 2-2Z",
        "M4 17a3 3 0 0 1 3-3h12M8 8h7m-7 3h4") }

    val WorkspaceFiles: ImageVector by lazy { entryGlyph("WorkspaceFiles",
        "M3.5 9V7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v1",
        "M3.5 10h17l-2 8a2 2 0 0 1-2 1.5h-11a2 2 0 0 1-2-2V10Z") }

    val Tools: ImageVector by lazy { entryGlyph("Tools",
        "M4 10h16v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z",
        "M8 10V6h8v4m-12 4h16m-10 0v2h4v-2") }

    val Usage: ImageVector by lazy { entryGlyph("Usage",
        "M4 5v14h16",
        "m7 15 4-5 3 2 5-6m-3 0h3v3") }

    val Reader: ImageVector by lazy { entryGlyph("Reader",
        "M4 6c3-1.5 5.7-1.2 8 .7 2.3-1.9 5-2.2 8-.7v12c-3-1.3-5.7-1-8 1-2.3-2-5-2.3-8-1V6Z",
        "M12 7v12m-5-8c1-.2 1.8 0 2.5.4m5 0c.7-.4 1.5-.6 2.5-.4") }

    val Quota: ImageVector by lazy { entryGlyph("Quota",
        "M4 18a8 8 0 1 1 16 0H4Z",
        "m12 14 4-4m-9 4 .7.3m4.3-4.8v.5") }

    val Appearance: ImageVector by lazy { entryGlyph("Appearance",
        "M5 11a7 7 0 0 1 7-7v16a7 7 0 0 1-7-7m10-8 2 .9m1.5 2.5 1 .9m.2 3.2v.8m-1.7 3 .7-.7M15 19l1-.5") }

    val Memory: ImageVector by lazy { entryGlyph("Memory",
        "M4 8l8-4 8 4-8 4-8-4Zm0 4 8 4 8-4M4 16l8 4 8-4") }

    val SessionStorage: ImageVector by lazy { entryGlyph("SessionStorage",
        "M4 8c0-2 3.6-4 8-4s8 2 8 4-3.6 4-8 4-8-2-8-4Zm0 0v8c0 2 3.6 4 8 4s8-2 8-4V8M4 12c0 2 3.6 4 8 4s8-2 8-4") }

    val Models: ImageVector by lazy { entryGlyph("Models",
        "M5 5h14v14H5Z",
        "M9 2.8V5m6-2.2V5m-6 14v2.2m6-2.2v2.2M2.8 9H5m-2.2 6H5m14-6h2.2M19 15h2.2",
        "M9 12l3-3 3 3-3 3Z") }

    val Performance: ImageVector by lazy { entryGlyph("Performance",
        "M13 3 5 13h6l-1 8 9-11h-6Z") }

    val Account: ImageVector by lazy { entryGlyph("Account",
        "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z",
        "M9.5 10a2.5 2.5 0 1 0 5 0 2.5 2.5 0 0 0-5 0ZM8 17a4 4 0 0 1 8 0") }

    val Notifications: ImageVector by lazy { entryGlyph("Notifications",
        "M6 10a6 6 0 0 1 12 0v4l2 3H4l2-3Zm4 10a2 2 0 0 0 4 0M12 3v1") }

    val Permissions: ImageVector by lazy { entryGlyph("Permissions",
        "M5 7l7-3 7 3v5c0 3.6-3 6.3-7 8-4-1.7-7-4.4-7-8Z",
        "M10 13a2 2 0 1 0 4 0 2 2 0 0 0-4 0Zm2 2v2") }

    val Advanced: ImageVector by lazy { entryGlyph("Advanced",
        "M4 5h16v14H4Z",
        "m8 9 3 3-3 3m6 0h3") }

    val Updates: ImageVector by lazy { entryGlyph("Updates",
        "M19 10a7 7 0 1 0-2 7m2-11v4h-4",
        "M12 9v6m-2-2 2 2 2-2") }

    val ChatStyle: ImageVector by lazy { entryGlyph("ChatStyle",
        "M7 4h10a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3h-6l-5 3v-3a3 3 0 0 1-2-3V7a3 3 0 0 1 3-3Z",
        "M8 9h8m-8 4h5") }

    val Connections: ImageVector by lazy { entryGlyph("Connections",
        "M8 4v5m4-5v5M6 9h8v3a4 4 0 0 1-8 0V9Zm4 7v1a3 3 0 0 0 3 3h2a3 3 0 0 0 3-3v-3") }

    val Skills: ImageVector by lazy { entryGlyph("Skills",
        "M12 3.5 20 8v8l-8 4.5L4 16V8Z",
        "M12 7.5v9M8 12h8") }

    val Plugins: ImageVector by lazy { entryGlyph("Plugins",
        "M4 4h6v6H4Zm10 0h6v6h-6ZM4 14h6v6H4Zm13 0v6m-3-3h6") }

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
