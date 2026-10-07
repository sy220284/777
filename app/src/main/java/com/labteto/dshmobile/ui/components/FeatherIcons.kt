package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Feather glyphs the transcript and chrome use, traced as Compose vectors.
 *
 * Feather (https://feathericons.com, MIT © Cole Bemis) — see `THIRD_PARTY_NOTICES.md`. The set is
 * inlined rather than pulled in as a dependency for the same reason the harness ships its own
 * `ic_ds_*` icons inline: fifteen glyphs is not worth a library, and the ones that matter here have
 * to sit on the same 24-unit grid with the same 2-unit round-capped stroke as the desktop UI they
 * mirror. Material's own icons are a different drawing language (filled, 20-unit optical sizing) and
 * mixing the two in one row reads as a mistake.
 *
 * Every glyph is stroke-only and drawn in black, so `Icon`'s tint colours it.
 */
internal object FeatherIcons {

    /** `message-circle` — chat/session settings. */
    val Chat: ImageVector by lazy {
        feather("Chat") {
            circle(12f, 11f, 9f)
            moveTo(8f, 19f); lineTo(4f, 22f); lineTo(5.5f, 16.5f)
        }
    }

    /** `cloud` — account/cloud capability. */
    val Cloud: ImageVector by lazy {
        feather("Cloud") {
            moveTo(18f, 18f); horizontalLineTo(6f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.5f, -8f)
            arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13.5f, 2f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1f, 6f)
        }
    }

    /** `cpu` — model/runtime memory and compute. */
    val Cpu: ImageVector by lazy {
        feather("Cpu") {
            rectangle(7f, 7f, 10f, 10f)
            rectangle(10f, 10f, 4f, 4f)
            moveTo(9f, 2f); lineTo(9f, 7f)
            moveTo(15f, 2f); lineTo(15f, 7f)
            moveTo(9f, 17f); lineTo(9f, 22f)
            moveTo(15f, 17f); lineTo(15f, 22f)
            moveTo(2f, 9f); lineTo(7f, 9f)
            moveTo(2f, 15f); lineTo(7f, 15f)
            moveTo(17f, 9f); lineTo(22f, 9f)
            moveTo(17f, 15f); lineTo(22f, 15f)
        }
    }

    /** `bell` — notifications. */
    val Bell: ImageVector by lazy {
        feather("Bell") {
            moveTo(18f, 8f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, -12f, 0f)
            curveTo(6f, 15f, 3f, 17f, 3f, 17f)
            horizontalLineTo(21f)
            curveTo(21f, 17f, 18f, 15f, 18f, 8f)
            moveTo(10f, 21f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 4f, 0f)
        }
    }

    /** `download` — app/update download. */
    val Download: ImageVector by lazy {
        feather("Download") {
            moveTo(12f, 3f); lineTo(12f, 15f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(5f, 21f); lineTo(19f, 21f)
        }
    }

    /** `link` — connection/integration entry. */
    val Link: ImageVector by lazy {
        feather("Link") {
            moveTo(10f, 13f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -7f)
            lineTo(12f, 4f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 7f)
            lineTo(17f, 13f)
            moveTo(14f, 11f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 7f)
            lineTo(12f, 20f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -7f, -7f)
            lineTo(7f, 11f)
        }
    }

    /** AI suggestion / capability sparkle. */
    val Sparkles: ImageVector by lazy {
        feather("Sparkles") {
            moveTo(12f, 3f); lineTo(13.5f, 8.5f); lineTo(19f, 10f)
            lineTo(13.5f, 11.5f); lineTo(12f, 17f)
            lineTo(10.5f, 11.5f); lineTo(5f, 10f)
            lineTo(10.5f, 8.5f); close()
            moveTo(19f, 3f); lineTo(19.6f, 5.4f); lineTo(22f, 6f)
            lineTo(19.6f, 6.6f); lineTo(19f, 9f)
            lineTo(18.4f, 6.6f); lineTo(16f, 6f)
            lineTo(18.4f, 5.4f); close()
        }
    }

    val ArrowUp: ImageVector by lazy {
        feather("ArrowUp") {
            moveTo(12f, 19f); lineTo(12f, 5f)
            moveTo(5f, 12f); lineTo(12f, 5f); lineTo(19f, 12f)
        }
    }

    val ArrowDown: ImageVector by lazy {
        feather("ArrowDown") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(12f, 19f); lineTo(19f, 12f)
        }
    }

    val Square: ImageVector by lazy {
        feather("Square") { rectangle(6f, 6f, 12f, 12f) }
    }

    val Shield: ImageVector by lazy {
        feather("Shield") {
            moveTo(12f, 22f)
            curveTo(12f, 22f, 20f, 18f, 20f, 12f)
            verticalLineTo(5f); lineTo(12f, 2f); lineTo(4f, 5f); verticalLineTo(12f)
            curveTo(4f, 18f, 12f, 22f, 12f, 22f); close()
        }
    }

    val List: ImageVector by lazy {
        feather("List") {
            moveTo(8f, 6f); lineTo(21f, 6f)
            moveTo(8f, 12f); lineTo(21f, 12f)
            moveTo(8f, 18f); lineTo(21f, 18f)
            moveTo(3f, 6f); lineTo(3.01f, 6f)
            moveTo(3f, 12f); lineTo(3.01f, 12f)
            moveTo(3f, 18f); lineTo(3.01f, 18f)
        }
    }

    val Check: ImageVector by lazy {
        feather("Check") { moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f) }
    }

    val Copy: ImageVector by lazy {
        feather("Copy") {
            rectangle(9f, 9f, 12f, 12f)
            moveTo(5f, 15f); horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
            verticalLineTo(4f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
            horizontalLineTo(13f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
            verticalLineTo(5f)
        }
    }

    val Paperclip: ImageVector by lazy {
        feather("Paperclip") {
            moveTo(21.44f, 11.05f); lineTo(12.25f, 20.24f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, -8.49f, -8.49f)
            lineTo(12.95f, 2.56f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.66f, 5.66f)
            lineTo(9.41f, 17.41f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.83f, -2.83f)
            lineTo(15.07f, 6.1f)
        }
    }

    val MoreVertical: ImageVector by lazy {
        feather("MoreVertical") {
            circle(12f, 5f, 1f); circle(12f, 12f, 1f); circle(12f, 19f, 1f)
        }
    }

    val SwapVertical: ImageVector by lazy {
        feather("SwapVertical") {
            moveTo(7f, 4f); lineTo(7f, 20f)
            moveTo(3f, 8f); lineTo(7f, 4f); lineTo(11f, 8f)
            moveTo(17f, 20f); lineTo(17f, 4f)
            moveTo(13f, 16f); lineTo(17f, 20f); lineTo(21f, 16f)
        }
    }

    val History: ImageVector by lazy {
        feather("History") {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, 3f, -6.7f)
            moveTo(3f, 4f); lineTo(3f, 9f); lineTo(8f, 9f)
            moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
        }
    }

    /** `terminal` — the shell tools (bash, pwsh). */
    val Terminal: ImageVector by lazy {
        feather("Terminal") {
            moveTo(4f, 17f); lineTo(10f, 11f); lineTo(4f, 5f)
            moveTo(12f, 19f); lineTo(20f, 19f)
        }
    }

    /** `file-text` — reading a file. */
    val FileText: ImageVector by lazy {
        feather("FileText") {
            documentOutline()
            moveTo(16f, 13f); lineTo(8f, 13f)
            moveTo(16f, 17f); lineTo(8f, 17f)
            moveTo(10f, 9f); lineTo(8f, 9f)
        }
    }

    /** `file-plus` — creating a file. */
    val FilePlus: ImageVector by lazy {
        feather("FilePlus") {
            documentOutline()
            moveTo(12f, 18f); lineTo(12f, 12f)
            moveTo(9f, 15f); lineTo(15f, 15f)
        }
    }

    /** `edit-3` — editing a file. */
    val Edit3: ImageVector by lazy {
        feather("Edit3") {
            moveTo(12f, 20f); horizontalLineToRelative(9f)
            moveTo(16.5f, 3.5f)
            arcToRelative(2.121f, 2.121f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
            lineTo(7f, 19f)
            lineToRelative(-4f, 1f)
            lineToRelative(1f, -4f)
            lineTo(16.5f, 3.5f)
            close()
        }
    }

    /** `search` — grep, glob, web search. */
    val Search: ImageVector by lazy {
        feather("Search") {
            circle(11f, 11f, 8f)
            moveTo(21f, 21f); lineTo(16.65f, 16.65f)
        }
    }

    /** `globe` — web fetch / retrieval cards. */
    val Globe: ImageVector by lazy {
        feather("Globe") {
            circle(12f, 12f, 10f)
            moveTo(2f, 12f); lineTo(22f, 12f)
            moveTo(12f, 2f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, 10f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4f, 10f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4f, -10f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, -10f)
            close()
        }
    }

    /** `tool` — an unclassified tool call. */
    val Tool: ImageVector by lazy {
        feather("Tool") {
            moveTo(14.7f, 6.3f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 1.4f)
            lineToRelative(1.6f, 1.6f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.4f, 0f)
            lineToRelative(3.77f, -3.77f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, -7.94f, 7.94f)
            lineToRelative(-6.91f, 6.91f)
            arcToRelative(2.12f, 2.12f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, -3f)
            lineToRelative(6.91f, -6.91f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.94f, -7.94f)
            lineToRelative(-3.76f, 3.76f)
            close()
        }
    }

    /** `code` — `run_code` and diff cards. */
    val Code: ImageVector by lazy {
        feather("Code") {
            moveTo(16f, 18f); lineTo(22f, 12f); lineTo(16f, 6f)
            moveTo(8f, 6f); lineTo(2f, 12f); lineTo(8f, 18f)
        }
    }

    /** `git-branch` — workflow rows. */
    val GitBranch: ImageVector by lazy {
        feather("GitBranch") {
            moveTo(6f, 3f); lineTo(6f, 15f)
            circle(18f, 6f, 3f)
            circle(6f, 18f, 3f)
            moveTo(18f, 9f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, -9f, 9f)
        }
    }

    /** `check-square` — todo docks. */
    val CheckSquare: ImageVector by lazy {
        feather("CheckSquare") {
            moveTo(9f, 11f); lineTo(12f, 14f); lineTo(22f, 4f)
            moveTo(21f, 12f)
            verticalLineToRelative(7f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
            horizontalLineToRelative(11f)
        }
    }

    /** `archive` — compaction rows. */
    val Archive: ImageVector by lazy {
        feather("Archive") {
            moveTo(21f, 8f); lineTo(21f, 21f); lineTo(3f, 21f); lineTo(3f, 8f)
            rectangle(1f, 3f, 22f, 5f)
            moveTo(10f, 12f); lineTo(14f, 12f)
        }
    }

    /** `alert-triangle` — warnings and connection banners. */
    val AlertTriangle: ImageVector by lazy {
        feather("AlertTriangle") {
            moveTo(10.29f, 3.86f)
            lineTo(1.82f, 18f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.71f, 3f)
            horizontalLineToRelative(16.94f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.71f, -3f)
            lineTo(13.71f, 3.86f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -3.42f, 0f)
            close()
            moveTo(12f, 9f); lineTo(12f, 13f)
            moveTo(12f, 17f); lineTo(12.01f, 17f)
        }
    }

    /** `info` — the details-panel button. Outline, unlike Material's filled disc. */
    val Info: ImageVector by lazy {
        feather("Info") {
            circle(12f, 12f, 10f)
            moveTo(12f, 16f); lineTo(12f, 12f)
            moveTo(12f, 8f); lineTo(12.01f, 8f)
        }
    }

    /** `menu` — the drawer button. */
    val Menu: ImageVector by lazy {
        feather("Menu") {
            moveTo(3f, 6f); lineTo(21f, 6f)
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(3f, 18f); lineTo(21f, 18f)
        }
    }

    /** `user` — simple character/person affordance used by roleplay controls. */
    val User: ImageVector by lazy {
        feather("User") {
            circle(12f, 8f, 4f)
            moveTo(4f, 21f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 16f, 0f)
        }
    }


    /** `plus` — compact create action. */
    val Plus: ImageVector by lazy {
        feather("Plus") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    /** `users` — group conversation. */
    val Users: ImageVector by lazy {
        feather("Users") {
            circle(9f, 8f, 4f)
            moveTo(1f, 21f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 16f, 0f)
            circle(17f, 9f, 3f)
            moveTo(16f, 16f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 5f)
        }
    }

    /** `image` — gallery / visual asset. */
    val Image: ImageVector by lazy {
        feather("Image") {
            rectangle(3f, 3f, 18f, 18f)
            circle(8.5f, 8.5f, 1.5f)
            moveTo(21f, 15f); lineTo(16f, 10f); lineTo(5f, 21f)
        }
    }

    /** `book-open` — diary and long-form memory. */
    val BookOpen: ImageVector by lazy {
        feather("BookOpen") {
            moveTo(3f, 4f); lineTo(9f, 4f); lineTo(12f, 7f); lineTo(12f, 21f)
            lineTo(9f, 19f); lineTo(3f, 19f); close()
            moveTo(21f, 4f); lineTo(15f, 4f); lineTo(12f, 7f); lineTo(12f, 21f)
            lineTo(15f, 19f); lineTo(21f, 19f); close()
        }
    }

    /** `zap` — external trigger / webhook. */
    val Zap: ImageVector by lazy {
        feather("Zap") {
            moveTo(13f, 2f)
            lineTo(3f, 14f)
            lineTo(12f, 14f)
            lineTo(11f, 22f)
            lineTo(21f, 10f)
            lineTo(12f, 10f)
            lineTo(13f, 2f)
            close()
        }
    }

    /** `clock` — scheduled / automation work. */
    val Clock: ImageVector by lazy {
        feather("Clock") {
            circle(12f, 12f, 9f)
            moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
        }
    }

    /** `folder` — workspace browser. */
    val Folder: ImageVector by lazy {
        feather("Folder") {
            moveTo(3f, 5f); lineTo(9f, 5f); lineTo(11f, 8f); lineTo(21f, 8f)
            lineTo(21f, 19f); lineTo(3f, 19f); close()
        }
    }

    /** `activity` — run center / live execution. */
    val Activity: ImageVector by lazy {
        feather("Activity") {
            moveTo(3f, 12f); lineTo(7f, 12f); lineTo(10f, 5f)
            lineTo(14f, 19f); lineTo(17f, 12f); lineTo(21f, 12f)
        }
    }

    /** `smartphone` — remote device entry. */
    val Device: ImageVector by lazy {
        feather("Device") {
            rectangle(6f, 2f, 12f, 20f)
            moveTo(10f, 18f); lineTo(14f, 18f)
        }
    }

    /** `sliders` — settings / tuning without the heavier Material gear. */
    val Sliders: ImageVector by lazy {
        feather("Sliders") {
            moveTo(4f, 6f); lineTo(20f, 6f)
            circle(9f, 6f, 2f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            circle(15f, 12f, 2f)
            moveTo(4f, 18f); lineTo(20f, 18f)
            circle(11f, 18f, 2f)
        }
    }

    /** `map-pin`-like push pin — pinned conversation state. */
    val Pin: ImageVector by lazy {
        feather("Pin") {
            moveTo(8f, 3f); lineTo(16f, 3f)
            moveTo(9f, 3f); lineTo(8f, 9f); lineTo(6f, 14f)
            lineTo(18f, 14f); lineTo(16f, 9f); lineTo(15f, 3f)
            moveTo(12f, 14f); lineTo(12f, 22f)
        }
    }

    /** `trash-2` — destructive session action. */
    val Trash2: ImageVector by lazy {
        feather("Trash2") {
            moveTo(3f, 6f); lineTo(21f, 6f)
            moveTo(8f, 6f); lineTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 6f)
            moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
            moveTo(10f, 10f); lineTo(10f, 17f)
            moveTo(14f, 10f); lineTo(14f, 17f)
        }
    }

    /** `x` — close / cancel action. */
    val X: ImageVector by lazy {
        feather("X") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    /** `refresh-cw` — retry / reload while keeping the same outlined chrome language. */
    val RefreshCw: ImageVector by lazy {
        feather("RefreshCw") {
            moveTo(20f, 3f); lineTo(20f, 8f); lineTo(15f, 8f)
            moveTo(4f, 21f); lineTo(4f, 16f); lineTo(9f, 16f)
            moveTo(20f, 8f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = false, -14f, -2f)
            moveTo(4f, 16f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = false, 14f, 2f)
        }
    }

    /** `arrow-left` — page back navigation. */
    val ArrowLeft: ImageVector by lazy {
        feather("ArrowLeft") {
            moveTo(19f, 12f); lineTo(5f, 12f)
            moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
        }
    }

    /** `chevron-down` — compact selector disclosure. */
    val ChevronDown: ImageVector by lazy {
        feather("ChevronDown") {
            moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
        }
    }

    /** `chevron-right` — disclosure affordance; rotates to 90° when open. */
    val ChevronRight: ImageVector by lazy {
        feather("ChevronRight") {
            moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f)
        }
    }
}

// ---------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------

/** One Feather glyph: 24-unit grid, 2-unit round-capped stroke, no fill. */
private fun feather(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "Feather.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/** SVG's `<circle>`, as the two half-arcs an `M … a … a …` path would draw. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, -2 * r, 0f)
}

/** SVG's `<rect>` without corner radii. */
private fun PathBuilder.rectangle(x: Float, y: Float, width: Float, height: Float) {
    moveTo(x, y)
    horizontalLineToRelative(width)
    verticalLineToRelative(height)
    horizontalLineToRelative(-width)
    close()
}

/** The dog-eared page both `file-text` and `file-plus` are drawn on. */
private fun PathBuilder.documentOutline() {
    moveTo(14f, 2f)
    horizontalLineTo(6f)
    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -2f, 2f)
    verticalLineToRelative(16f)
    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, 2f)
    horizontalLineToRelative(12f)
    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, -2f)
    verticalLineTo(8f)
    close()
    moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
}
