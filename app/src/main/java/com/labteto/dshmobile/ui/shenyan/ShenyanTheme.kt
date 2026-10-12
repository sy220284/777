package com.labteto.dshmobile.ui.shenyan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    val peach: Color = Color(0xFFE8A87C),
    val peachPressed: Color = Color(0xFFD9926B),
    val onPeach: Color = Color(0xFF3A342D),
    val mint: Color = Color(0xFF7FBFA8),
    val lilac: Color = Color(0xFFB9A0D4),
    val clay: Color = Color(0xFFC98B6B),
    val warning: Color = Color(0xFFE0A458),
    val danger: Color = Color(0xFFD9705F),
    val scrim: Color,
)

object SyPalettes {
    val Light = SyPalette(
        canvas=Color(0xFFEDE6DA), topBar=Color(0xFFF6F1E9),
        l1=Color(0x4DFFFFFF),l2=Color(0x75FFFFFF),
        l3=Color(0xA3FFFFFF),l4=Color(0xE0FFFFFF),
        solid=Color(0xFFFAF7F2),rim=Color(0xB8FFFFFF),border=Color(0xCCFFFFFF),
        text=Color(0xFF3A342D),secondaryText=Color(0xFF6A6156),
        tertiaryText=Color(0xFF70665A),scrim=Color(0x335A4837),
    )
    val Dark = SyPalette(
        canvas=Color(0xFF24211E),topBar=Color(0xFF292521),
        l1=Color(0x0EFFF5E4),l2=Color(0x16FFF5E4),l3=Color(0x1FFFF5E4),
        l4=Color(0xE0312B26),solid=Color(0xFF35312D),
        rim=Color(0x29FFF1DE),border=Color(0x21FFFFFF),
        text=Color(0xFFF5F0E8),secondaryText=Color(0xFFD9D0C5),
        tertiaryText=Color(0xFFB8AB9B),scrim=Color(0x5C000000),
    )
    val Matte = SyPalette(
        canvas=Color(0xFF0F0F10),topBar=Color(0xFF18181A),
        l1=Color(0xFF181819),l2=Color(0xFF1E1E20),l3=Color(0xFF232324),
        l4=Color(0xFF29292B),solid=Color(0xFF29292B),
        rim=Color(0x0EFFFFFF),border=Color(0x1AFFFFFF),
        text=Color(0xFFF5F0E8),secondaryText=Color(0xFFD9D0C5),
        tertiaryText=Color(0xFFB8AB9B),scrim=Color(0x99000000),
    )
    fun forMode(mode: SyMode, systemDark: Boolean = false): SyPalette = when(mode) {
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
    val minimumTouch = 48.dp
    val cornerControl = 13.dp
    val cornerCard = 18.dp
    val cornerOverlay = 24.dp
    val navigationExpanded = 258.dp
    val navigationCollapsed = 64.dp
    val inspector = 318.dp
    val toolbarPhone = 56.dp
    val toolbarExpanded = 64.dp
}

object SyDurations {
    const val press = 80
    const val select = 200
    const val dialog = 280
    const val sheet = 380
    const val page = 360
    const val sidebar = 280
    const val sidebarDelay = 60
}

/** Attach new V5 tokens at the app theme root while old business screens migrate. */
@Composable
fun SyThemeProvider(
    mode: SyMode,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = SyPalettes.forMode(mode, isSystemInDarkTheme())
    CompositionLocalProvider(
        LocalSyPalette provides palette,
        LocalSyReducedMotion provides reduceMotion,
        LocalSyReducedTransparency provides reduceTransparency,
        content = content,
    )
}

/** Standalone rendering entry; unlike the temporary provider, this owns Material3 appearance. */
@Composable
fun ShenyanTheme(
    mode: SyMode,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = SyPalettes.forMode(mode, isSystemInDarkTheme())
    val scheme = if (mode == SyMode.LIGHT || (mode == SyMode.SYSTEM && !isSystemInDarkTheme())) {
        lightColorScheme(primary=palette.peach, onPrimary=palette.onPeach,
            background=palette.canvas, onBackground=palette.text,
            surface=palette.solid, onSurface=palette.text,
            surfaceVariant=palette.l3, onSurfaceVariant=palette.secondaryText,
            secondary=palette.mint, tertiary=palette.lilac, error=palette.danger)
    } else {
        darkColorScheme(primary=palette.peach, onPrimary=palette.onPeach,
            background=palette.canvas, onBackground=palette.text,
            surface=palette.solid, onSurface=palette.text,
            surfaceVariant=palette.l3, onSurfaceVariant=palette.secondaryText,
            secondary=palette.mint, tertiary=palette.lilac, error=palette.danger)
    }
    SyThemeProvider(mode, reduceMotion, reduceTransparency) {
        MaterialTheme(
            colorScheme=scheme,
            shapes=Shapes(
                small=RoundedCornerShape(SyDimensions.cornerControl),
                medium=RoundedCornerShape(SyDimensions.cornerCard),
                large=RoundedCornerShape(SyDimensions.cornerOverlay),
            ),
            content=content,
        )
    }
}
