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

    val CreditCard: ImageVector by lazy {
        feather("CreditCard") {
            rectangle(2f, 4f, 20f, 16f)
            moveTo(2f, 9f); horizontalLineTo(22f)
            moveTo(6f, 15f); horizontalLineTo(10f)
        }
    }

    /** Message actions share rounded outlines without visible button containers. */
    val MessageCopy: ImageVector by lazy {
        feather("MessageCopy") {
            moveTo(10f, 8f); horizontalLineTo(18f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineTo(19f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(10f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(10f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
            moveTo(16f, 8f); verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineTo(14f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineTo(8f)
        }
    }

    val Retry: ImageVector by lazy {
        feather("Retry") {
            moveTo(20f, 4f); verticalLineTo(9f); horizontalLineTo(15f)
            moveTo(20f, 9f)
            arcTo(8f, 8f, 0f, true, false, 20f, 15f)
        }
    }

    /** Remote control depicts a connected screen, distinct from a local phone. */
    val RemoteControl: ImageVector by lazy {
        feather("RemoteControl") {
            moveTo(14f, 4f); horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineTo(16f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineTo(20f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineTo(12f)
            moveTo(12f, 18f); verticalLineTo(22f)
            moveTo(8f, 22f); horizontalLineTo(16f)
            moveTo(18f, 3f); curveTo(20.8f, 3f, 23f, 5.2f, 23f, 8f)
            moveTo(18f, 7f); lineTo(19f, 8f)
        }
    }

    val SettingsOutline: ImageVector by lazy {
        feather("SettingsOutline") {
            circle(12f, 12f, 3f)
            moveTo(20.200f, 12.000f)
            lineTo(21.808f, 13.951f)
            lineTo(21.239f, 15.827f)
            lineTo(18.818f, 16.556f)
            lineTo(17.798f, 17.798f)
            lineTo(17.556f, 20.315f)
            lineTo(15.827f, 21.239f)
            lineTo(13.600f, 20.042f)
            lineTo(12.000f, 20.200f)
            lineTo(10.049f, 21.808f)
            lineTo(8.173f, 21.239f)
            lineTo(7.444f, 18.818f)
            lineTo(6.202f, 17.798f)
            lineTo(3.685f, 17.556f)
            lineTo(2.761f, 15.827f)
            lineTo(3.958f, 13.600f)
            lineTo(3.800f, 12.000f)
            lineTo(2.192f, 10.049f)
            lineTo(2.761f, 8.173f)
            lineTo(5.182f, 7.444f)
            lineTo(6.202f, 6.202f)
            lineTo(6.444f, 3.685f)
            lineTo(8.173f, 2.761f)
            lineTo(10.400f, 3.958f)
            lineTo(12.000f, 3.800f)
            lineTo(13.951f, 2.192f)
            lineTo(15.827f, 2.761f)
            lineTo(16.556f, 5.182f)
            lineTo(17.798f, 6.202f)
            lineTo(20.315f, 6.444f)
            lineTo(21.239f, 8.173f)
            lineTo(20.042f, 10.400f)
            close()
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


    /** `message-circle` — chat/session settings. */
    val MessageCircle: ImageVector by lazy {
        feather("MessageCircle") {
            moveTo(21f, 15f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4f, 4f)
            horizontalLineTo(8f)
            lineTo(3f, 22f)
            verticalLineTo(7f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, -4f)
            horizontalLineToRelative(10f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, 4f)
            close()
        }
    }

    /** `bell` — notification settings. */
    val Bell: ImageVector by lazy {
        feather("Bell") {
            moveTo(18f, 8f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, -12f, 0f)
            curveTo(6f, 15f, 3f, 17f, 3f, 17f)
            horizontalLineToRelative(18f)
            curveTo(21f, 17f, 18f, 15f, 18f, 8f)
            close()
            moveTo(13.73f, 21f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.46f, 0f)
        }
    }

    /** `paperclip` — file / attachment entry. */
    val Paperclip: ImageVector by lazy {
        feather("Paperclip") {
            moveTo(21.44f, 11.05f)
            lineTo(12.25f, 20.24f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, -8.49f, -8.49f)
            lineTo(12.95f, 2.56f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.66f, 5.66f)
            lineTo(9.41f, 17.41f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.83f, -2.83f)
            lineTo(15.07f, 6.1f)
        }
    }

    /** `sparkles` — AI/persona generation affordance. */
    val Sparkles: ImageVector by lazy {
        feather("Sparkles") {
            moveTo(12f, 3f); lineTo(13.6f, 7.4f); lineTo(18f, 9f)
            lineTo(13.6f, 10.6f); lineTo(12f, 15f)
            lineTo(10.4f, 10.6f); lineTo(6f, 9f)
            lineTo(10.4f, 7.4f); close()
            moveTo(19f, 15f); lineTo(19.8f, 17.2f); lineTo(22f, 18f)
            lineTo(19.8f, 18.8f); lineTo(19f, 21f)
            lineTo(18.2f, 18.8f); lineTo(16f, 18f)
            lineTo(18.2f, 17.2f); close()
            moveTo(5f, 2f); lineTo(5.6f, 3.4f); lineTo(7f, 4f)
            lineTo(5.6f, 4.6f); lineTo(5f, 6f)
            lineTo(4.4f, 4.6f); lineTo(3f, 4f)
            lineTo(4.4f, 3.4f); close()
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

    /** settings — compact settings entry. */
    val Gear: ImageVector by lazy {
        feather("Gear") {
            circle(12f, 12f, 3f)
            moveTo(19.4f, 15f); lineTo(21f, 16f); lineTo(19f, 19f); lineTo(17.4f, 18f)
            moveTo(15f, 19.4f); lineTo(16f, 21f); lineTo(12f, 22f); lineTo(11f, 20.2f)
            moveTo(9f, 19.4f); lineTo(8f, 21f); lineTo(4f, 19f); lineTo(5.6f, 17.4f)
            moveTo(4.6f, 15f); lineTo(3f, 16f); lineTo(1f, 12f); lineTo(3f, 11f)
            moveTo(4.6f, 9f); lineTo(3f, 8f); lineTo(5f, 4f); lineTo(6.6f, 5.6f)
            moveTo(9f, 4.6f); lineTo(8f, 3f); lineTo(12f, 1f); lineTo(13f, 3f)
            moveTo(15f, 4.6f); lineTo(16f, 3f); lineTo(20f, 5f); lineTo(18.4f, 6.6f)
            moveTo(19.4f, 9f); lineTo(21f, 8f); lineTo(23f, 12f); lineTo(21f, 13f)
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

    /** `more-vertical` — overflow/action menu. */
    val MoreVertical: ImageVector by lazy {
        feather("MoreVertical") {
            circle(12f, 5f, 1f)
            circle(12f, 12f, 1f)
            circle(12f, 19f, 1f)
        }
    }

    /** `download` — export / save action. */
    val Download: ImageVector by lazy {
        feather("Download") {
            moveTo(21f, 15f); verticalLineTo(19f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
            verticalLineTo(15f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(12f, 15f); verticalLineTo(3f)
        }
    }

    /** `arrow-up-down` — ordering/sort control. */
    val ArrowUpDown: ImageVector by lazy {
        feather("ArrowUpDown") {
            moveTo(7f, 15f); lineTo(7f, 3f)
            moveTo(3f, 7f); lineTo(7f, 3f); lineTo(11f, 7f)
            moveTo(17f, 9f); lineTo(17f, 21f)
            moveTo(13f, 17f); lineTo(17f, 21f); lineTo(21f, 17f)
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

    val ChevronLeft: ImageVector by lazy {
        feather("ChevronLeft") {
            moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f)
        }
    }

    val Camera: ImageVector by lazy {
        feather("Camera") {
            moveTo(3f, 6f); lineTo(7f, 6f); lineTo(9f, 3f); lineTo(15f, 3f)
            lineTo(17f, 6f); lineTo(21f, 6f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            circle(12f, 13f, 4f)
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

    /** `check` — selected row/model state. */
    val Check: ImageVector by lazy {
        feather("Check") {
            moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f)
        }
    }

    /** `arrow-down` — jump to latest / downward navigation. */
    val ArrowDown: ImageVector by lazy {
        feather("ArrowDown") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(12f, 19f); lineTo(19f, 12f)
        }
    }

    /** `copy` — copy text / plan action. */
    val Copy: ImageVector by lazy {
        feather("Copy") {
            rectangle(9f, 9f, 12f, 12f)
            moveTo(15f, 5f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -2f, 2f)
            verticalLineToRelative(10f)
        }
    }

    /** `thumbs-up` — positive message feedback. */
    val ThumbsUp: ImageVector by lazy {
        feather("ThumbsUp") {
            moveTo(7f, 10f); verticalLineTo(22f); horizontalLineTo(3f); verticalLineTo(10f); close()
            moveTo(7f, 10f); lineTo(11f, 2f)
            curveTo(13f, 2f, 14f, 3.5f, 13.5f, 5.5f)
            lineTo(13f, 8f); horizontalLineTo(19f)
            curveTo(21f, 8f, 22f, 9.5f, 21.5f, 11f)
            lineTo(19f, 20f)
            curveTo(18.7f, 21.2f, 17.8f, 22f, 16.5f, 22f)
            horizontalLineTo(7f)
        }
    }

    /** `thumbs-down` — negative message feedback. */
    val ThumbsDown: ImageVector by lazy {
        feather("ThumbsDown") {
            moveTo(17f, 14f); verticalLineTo(2f); horizontalLineTo(21f); verticalLineTo(14f); close()
            moveTo(17f, 14f); lineTo(13f, 22f)
            curveTo(11f, 22f, 10f, 20.5f, 10.5f, 18.5f)
            lineTo(11f, 16f); horizontalLineTo(5f)
            curveTo(3f, 16f, 2f, 14.5f, 2.5f, 13f)
            lineTo(5f, 4f)
            curveTo(5.3f, 2.8f, 6.2f, 2f, 7.5f, 2f)
            horizontalLineTo(17f)
        }
    }

    /** `arrow-up` — send/queue action. */
    val ArrowUp: ImageVector by lazy {
        feather("ArrowUp") {
            moveTo(12f, 19f); lineTo(12f, 5f)
            moveTo(5f, 12f); lineTo(12f, 5f); lineTo(19f, 12f)
        }
    }

    /** `square` — stop action. */
    val Square: ImageVector by lazy {
        feather("Square") {
            rectangle(5f, 5f, 14f, 14f)
        }
    }

    /** `shield` — permission/approval control. */
    val Shield: ImageVector by lazy {
        feather("Shield") {
            moveTo(12f, 22f)
            arcToRelative(10f, 10f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8f, -10f)
            verticalLineTo(5f)
            lineTo(12f, 2f)
            lineTo(4f, 5f)
            verticalLineToRelative(7f)
            arcToRelative(10f, 10f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8f, 10f)
            close()
        }
    }

    /** `list` — plan/task mode. */
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
