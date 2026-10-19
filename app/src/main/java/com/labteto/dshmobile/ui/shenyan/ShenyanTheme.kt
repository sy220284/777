package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** V5 is the only new visual contract. Business state stays in the existing 777 owners. */
enum class SyMode { LIGHT, DARK, MATTE, SYSTEM }

@Immutable
data class SyPalette(
    val canvas: Color,
    val topBar: Color,
    val l1: Color,
    val l2: Color,
    val l3: Color,
    val l4: Color,
    val solid: Color,
    val rim: Color,
    val border: Color,
    val text: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val peach: Color,
    val peachPressed: Color,
    val onPeach: Color,
    val mint: Color,
    val lilac: Color,
    val clay: Color,
    val warning: Color,
    val danger: Color,
    val scrim: Color,
)

/** Old semantic names are read-only aliases to the single V5 palette, never new colours. */
object SyPalettes {
    fun fromCanonical(p: com.shenyan.designsystem.foundation.SyColors): SyPalette = SyPalette(
        canvas = p.canvas, topBar = p.topBar,
        l1 = p.materialL1, l2 = p.materialL2, l3 = p.materialL3, l4 = p.materialL4,
        solid = p.materialL5, rim = p.materialSheen, border = p.border,
        text = p.text, secondaryText = p.textSecondary, tertiaryText = p.textTertiary,
        peach = p.primary, peachPressed = p.primaryPressed, onPeach = p.onPrimary,
        mint = p.mint, lilac = p.lilac, clay = p.clay,
        warning = p.warning, danger = p.danger, scrim = p.overlay,
    )
    val Light: SyPalette get() = fromCanonical(com.shenyan.designsystem.foundation.ShenyanPalettes.light)
    val Dark: SyPalette get() = fromCanonical(com.shenyan.designsystem.foundation.ShenyanPalettes.dark)
    val Matte: SyPalette get() = fromCanonical(com.shenyan.designsystem.foundation.ShenyanPalettes.matte)
    fun forMode(mode: SyMode, systemDark: Boolean = false): SyPalette = when (mode) {
        SyMode.LIGHT -> Light
        SyMode.DARK -> Dark
        SyMode.MATTE -> Matte
        SyMode.SYSTEM -> if (systemDark) Dark else Light
    }
}

val LocalSyPalette = staticCompositionLocalOf { SyPalettes.Light }
val LocalSyReducedMotion = staticCompositionLocalOf { false }
val LocalSyReducedTransparency = staticCompositionLocalOf { false }
val LocalSyGlassDepth = staticCompositionLocalOf { 0 }

object SyDimensions {
    val minimumTouch = com.shenyan.designsystem.foundation.SyMetric.touch
    val cornerControl = com.shenyan.designsystem.foundation.SyMetric.radiusControl
    val cornerCard = com.shenyan.designsystem.foundation.SyMetric.radiusCard
    val cornerOverlay = com.shenyan.designsystem.foundation.SyMetric.radiusSheet
    val navigationExpanded = 258.dp
    val navigationCollapsed = 64.dp
    val inspector = 318.dp
    val toolbarPhone = 56.dp
    val toolbarExpanded = 64.dp
}

object SyDurations {
    const val press = com.shenyan.designsystem.foundation.SyMotion.pressMs
    const val select = com.shenyan.designsystem.foundation.SyMotion.selectMs
    const val dialog = com.shenyan.designsystem.foundation.SyMotion.dialogMs
    const val sheet = com.shenyan.designsystem.foundation.SyMotion.sheetMs
    const val page = com.shenyan.designsystem.foundation.SyMotion.morphMs
    const val sidebar = com.shenyan.designsystem.foundation.SyMotion.sidebarMs
    const val sidebarDelay = com.shenyan.designsystem.foundation.SyMotion.sidebarContentDelayMs
}

/**
 * The only runtime palette comes from canonical V5 LocalSyColors.
 * This object adapts old call sites that still expect semantic SyPalette properties.
 * Always install this bridge INSIDE com.shenyan.designsystem.foundation.ShenyanTheme.
 */
@Composable
fun SyThemeProvider(
    mode: SyMode,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val adapted = SyPalettes.fromCanonical(
        com.shenyan.designsystem.foundation.LocalSyColors.current,
    )
    CompositionLocalProvider(
        LocalSyPalette provides adapted,
        LocalSyReducedMotion provides reduceMotion,
        LocalSyReducedTransparency provides reduceTransparency,
        content = content,
    )
}

/** Standalone entry shares exactly the same canonical Material3 V5 theme. */
@Composable
fun ShenyanTheme(
    mode: SyMode,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val canonicalMode = when (mode) {
        SyMode.LIGHT -> com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
        SyMode.DARK -> com.shenyan.designsystem.foundation.ShenyanMode.DARK
        SyMode.MATTE -> com.shenyan.designsystem.foundation.ShenyanMode.MATTE
        SyMode.SYSTEM -> if (systemDark)
            com.shenyan.designsystem.foundation.ShenyanMode.DARK
            else com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
    }
    com.shenyan.designsystem.foundation.ShenyanTheme(
        mode = canonicalMode,
        reduceMotion = reduceMotion,
        reduceTransparency = reduceTransparency,
    ) {
        SyThemeProvider(mode, reduceMotion, reduceTransparency, content)
    }
}
