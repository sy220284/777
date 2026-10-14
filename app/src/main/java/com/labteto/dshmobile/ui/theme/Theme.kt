package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.labteto.dshmobile.connection.AppDisplayLimits
import com.labteto.dshmobile.ui.shenyan.SyMode
import com.labteto.dshmobile.ui.shenyan.SyThemeProvider
import com.labteto.dshmobile.ui.shenyan.rememberSyReducedMotion

/** Theme preference exposed by the app Appearance row. */
enum class ThemePreference { LIGHT, DARK, MATTE_BLACK, SYSTEM }

/** Full DeepSeek Harness semantic palette for one scheme. */
data class DsColors(
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgModulePlatform: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val brandPrimary: Color,
    val onBrandPrimary: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val accent: Color,
    val onAccent: Color,
    val accentTertiary: Color,
    val characterAccent: Color,
    val characterAccentTertiary: Color,
    val accentHover: Color,
    val hover: Color,
    val hoverSolid: Color,
    val hoverAccent: Color,
    val active: Color,
    val dangerHover: Color,
    val buttonPrimaryHover: Color,
    val buttonPrimaryDimmed: Color,
    val buttonInfoFill: Color,
    val buttonInfoHover: Color,
    val error: Color,
    val onError: Color,
    val errorSecondary: Color,
    val errorTertiary: Color,
    val success: Color,
    val successSecondary: Color,
    val successTertiary: Color,
    val warnLabel: Color,
    val warn: Color,
    val warnSecondary: Color,
    val warnTertiary: Color,
    val progressFailedStart: Color,
    val progressFailedEnd: Color,
    val toastBg: Color,
    /** WCAG contrast against toastBg; never use onAccent for a dark toast. */
    val onToast: Color,
    val tooltipBg: Color,
    val userBubble: Color,
    val userBubbleHighlight: Color,
    val composerCard: Color,
    val sidebar: Color,
    val sidebarNavActive: Color,
    val sidebarNavAccent: Color,
    val sidebarNavHover: Color,
    val tipSurface: Color,
    val codeBlockBg: Color,
    val codeBlockBanner: Color,
    val inlineCode: Color,
    val citation: Color,
    val markdownTag: Color,
    val overlayMask: Color,
)

object DsThemeTokens {
    val light = DsColors(
        bgBase = DsLight.bgBase, bgLayer1 = DsLight.bgLayer1, bgLayer2 = DsLight.bgLayer2,
        bgLayer3 = DsLight.bgLayer3, bgModulePlatform = DsLight.bgModulePlatform,
        borderL1 = DsLight.borderL1, borderL2 = DsLight.borderL2, borderL3 = DsLight.borderL3,
        brandPrimary = DsLight.brandPrimary, onBrandPrimary = DsLight.onBrandPrimary,
        labelPrimary = DsLight.labelPrimary, labelSecondary = DsLight.labelSecondary,
        labelTertiary = DsLight.labelTertiary, labelCaption = DsLight.labelCaption,
        labelDimmed = DsLight.labelDimmed,
        accent = DsLight.accent, onAccent = DsLight.onAccent,
        accentTertiary = DsLight.accentTertiary,
        characterAccent = Color(0xFF84619D),
        characterAccentTertiary = Color(0x30B9A0D4),
        accentHover = DsLight.accentHover,
        hover = DsLight.hover, hoverSolid = DsLight.hoverSolid, hoverAccent = DsLight.hoverAccent,
        active = DsLight.active, dangerHover = DsLight.dangerHover,
        buttonPrimaryHover = DsLight.buttonPrimaryHover, buttonPrimaryDimmed = DsLight.buttonPrimaryDimmed,
        buttonInfoFill = DsLight.buttonInfoFill, buttonInfoHover = DsLight.buttonInfoHover,
        error = DsLight.error, onError = Color.White, errorSecondary = DsLight.errorSecondary, errorTertiary = DsLight.errorTertiary,
        success = DsLight.success, successSecondary = DsLight.successSecondary,
        successTertiary = DsLight.successTertiary,
        warnLabel = DsLight.warnLabel, warn = DsLight.warn, warnSecondary = DsLight.warnSecondary,
        warnTertiary = DsLight.warnTertiary,
        progressFailedStart = DsLight.progressFailedStart,
        progressFailedEnd = DsLight.progressFailedEnd,
        toastBg = DsLight.toastBg, onToast = Color(0xFFFAF6EC), tooltipBg = DsLight.tooltipBg,
        userBubble = DsLight.userBubble, userBubbleHighlight = DsLight.userBubbleHighlight,
        composerCard = DsLight.composerCard,
        sidebar = DsLight.sidebar, sidebarNavActive = DsLight.sidebarNavActive,
        sidebarNavAccent = DsLight.sidebarNavAccent, sidebarNavHover = DsLight.sidebarNavHover,
        tipSurface = DsLight.tipSurface,
        codeBlockBg = DsLight.codeBlockBg, codeBlockBanner = DsLight.codeBlockBanner,
        inlineCode = DsLight.inlineCode, citation = DsLight.citation,
        markdownTag = DsLight.markdownTag, overlayMask = DsLight.overlayMask,
    )

    val dark = DsColors(
        bgBase = DsDark.bgBase, bgLayer1 = DsDark.bgLayer1, bgLayer2 = DsDark.bgLayer2,
        bgLayer3 = DsDark.bgLayer3, bgModulePlatform = DsDark.bgModulePlatform,
        borderL1 = DsDark.borderL1, borderL2 = DsDark.borderL2, borderL3 = DsDark.borderL3,
        brandPrimary = DsDark.brandPrimary, onBrandPrimary = DsDark.onBrandPrimary,
        labelPrimary = DsDark.labelPrimary, labelSecondary = DsDark.labelSecondary,
        labelTertiary = DsDark.labelTertiary, labelCaption = DsDark.labelCaption,
        labelDimmed = DsDark.labelDimmed,
        accent = DsDark.accent, onAccent = DsDark.onAccent,
        accentTertiary = DsDark.accentTertiary,
        characterAccent = Color(0xFFB9A0D4),
        characterAccentTertiary = Color(0x30B9A0D4),
        accentHover = DsDark.accentHover,
        hover = DsDark.hover, hoverSolid = DsDark.hoverSolid, hoverAccent = DsDark.hoverAccent,
        active = DsDark.active, dangerHover = DsDark.dangerHover,
        buttonPrimaryHover = DsDark.buttonPrimaryHover, buttonPrimaryDimmed = DsDark.buttonPrimaryDimmed,
        buttonInfoFill = DsDark.buttonInfoFill, buttonInfoHover = DsDark.buttonInfoHover,
        error = DsDark.error, onError = Color(0xFF0C1A19), errorSecondary = DsDark.errorSecondary, errorTertiary = DsDark.errorTertiary,
        success = DsDark.success, successSecondary = DsDark.successSecondary,
        successTertiary = DsDark.successTertiary,
        warnLabel = DsDark.warnLabel, warn = DsDark.warn, warnSecondary = DsDark.warnSecondary,
        warnTertiary = DsDark.warnTertiary,
        progressFailedStart = DsDark.progressFailedStart,
        progressFailedEnd = DsDark.progressFailedEnd,
        toastBg = DsDark.toastBg, onToast = Color(0xFFF5F0E8), tooltipBg = DsDark.tooltipBg,
        userBubble = DsDark.userBubble, userBubbleHighlight = DsDark.userBubbleHighlight,
        composerCard = DsDark.composerCard,
        sidebar = DsDark.sidebar, sidebarNavActive = DsDark.sidebarNavActive,
        sidebarNavAccent = DsDark.sidebarNavAccent, sidebarNavHover = DsDark.sidebarNavHover,
        tipSurface = DsDark.tipSurface,
        codeBlockBg = DsDark.codeBlockBg, codeBlockBanner = DsDark.codeBlockBanner,
        inlineCode = DsDark.inlineCode, citation = DsDark.citation,
        markdownTag = DsDark.markdownTag, overlayMask = DsDark.overlayMask,
    )
    /**
     * A warmer, low-glare dark palette for the optional matte-black appearance.
     *
     * It deliberately keeps the normal dark theme intact. Surfaces move to neutral charcoal while
     * text uses warm off-white values, giving the UI a softer "matte black / matte white" contrast.
     */
    val matteBlack = dark.copy(
        bgBase = Color(0xFF10100F),
        bgLayer1 = Color(0xFF191918),
        bgLayer2 = Color(0xFF222220),
        bgLayer3 = Color(0xFF2B2B28),
        bgModulePlatform = Color(0xFF222220),
        borderL1 = Color(0x0FF3F0E8),
        borderL2 = Color(0x1FF3F0E8),
        borderL3 = Color(0x29F3F0E8),
        brandPrimary = Color(0xFFF3F0E8),
        onBrandPrimary = Color(0xFF10100F),
        onAccent = Color(0xFF3A342D),
        labelPrimary = Color(0xFFF4F3EF),
        labelSecondary = Color(0xFFD5D1C9),
        labelTertiary = Color(0xFFAAA69E),
        labelCaption = Color(0xFF7F7C75),
        labelDimmed = Color(0xFF5B5954),
        hoverSolid = Color(0xFF242421),
        buttonPrimaryHover = Color(0xFFD5D1C9),
        buttonPrimaryDimmed = Color(0xFF4B4944),
        toastBg = Color(0xFF363431),
        tooltipBg = Color(0xFF363431),
        userBubble = Color(0xFF222220),
        userBubbleHighlight = Color(0xFF363431),
        composerCard = Color(0xFF222220),
        sidebar = Color(0xFF151514),
        sidebarNavActive = Color(0xFF363431),
        sidebarNavAccent = Color(0xFF2D2C29),
        sidebarNavHover = Color(0xFF222220),
        tipSurface = Color(0xFF2D2C29),
        codeBlockBg = Color(0xFF181816),
        codeBlockBanner = Color(0xFF1B1B19),
        inlineCode = Color(0xFF222220),
        citation = Color(0xFF2D2C29),
        markdownTag = Color(0xFF222220),
    )

}

/** The resolved 777 UI palette as a CompositionLocal. */
val LocalDsColors = staticCompositionLocalOf { DsThemeTokens.light }

data class DsReadingPreferences(
    val textScale: Float = 1f,
    val textWeightAdjustment: Int = 0,
)

val LocalDsReadingPreferences = staticCompositionLocalOf { DsReadingPreferences() }

object DsTheme {
    val colors: DsColors
        @Composable get() = LocalDsColors.current
}

private fun materialLightScheme(c: DsColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    secondary = c.labelSecondary,
    onSecondary = c.bgBase,
    tertiary = c.warn,
    background = c.bgBase,
    onBackground = c.labelPrimary,
    surface = c.bgLayer1,
    onSurface = c.labelPrimary,
    surfaceVariant = c.bgModulePlatform,
    onSurfaceVariant = c.labelSecondary,
    outline = c.borderL2,
    outlineVariant = c.borderL1,
    error = c.error,
    onError = c.onError,
)

private fun materialDarkScheme(c: DsColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    secondary = c.labelSecondary,
    onSecondary = c.bgBase,
    tertiary = c.warn,
    background = c.bgBase,
    onBackground = c.labelPrimary,
    surface = c.bgLayer1,
    onSurface = c.labelPrimary,
    surfaceVariant = c.bgModulePlatform,
    onSurfaceVariant = c.labelSecondary,
    outline = c.borderL2,
    outlineVariant = c.borderL1,
    error = c.error,
    onError = c.onError,
)

/**
 * Single palette resolution path shared by the app theme and JVM regression tests.
 * DsColors remains a host preference projection; production surfaces use [controls].
 */
internal data class V6ThemeProjection(
    val dark: Boolean,
    val page: DsColors,
    val controls: com.shenyan.designsystem.foundation.SyColors,
    val isDefaultAccent: Boolean,
)

internal fun resolveV6ThemePalette(
    preference: ThemePreference,
    accentKey: String?,
    systemDark: Boolean,
): V6ThemeProjection {
    val dark = when (preference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK, ThemePreference.MATTE_BLACK -> true
        ThemePreference.SYSTEM -> systemDark
    }
    val base = when (preference) {
        ThemePreference.LIGHT -> DsThemeTokens.light
        ThemePreference.DARK -> DsThemeTokens.dark
        ThemePreference.MATTE_BLACK -> DsThemeTokens.matteBlack
        ThemePreference.SYSTEM -> if (systemDark) DsThemeTokens.dark else DsThemeTokens.light
    }
    val palette = AccentPalettes.of(accentKey)
    val canonical = com.shenyan.designsystem.foundation.ShenyanPalettes.forMode(
        when (preference) {
            ThemePreference.LIGHT -> com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
            ThemePreference.DARK -> com.shenyan.designsystem.foundation.ShenyanMode.DARK
            ThemePreference.MATTE_BLACK -> com.shenyan.designsystem.foundation.ShenyanMode.MATTE
            ThemePreference.SYSTEM -> if (systemDark)
                com.shenyan.designsystem.foundation.ShenyanMode.DARK
                else com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
        }
    )
    val defaultAccent = palette.key == AccentPalettes.DEFAULT.key
    val page = (if (defaultAccent) base else base.withAccent(palette, dark))
        .harmonizeV6(canonical, preserveAccent = !defaultAccent)
    val controls = if (defaultAccent) canonical else canonical.withV6Accent(page)
    return V6ThemeProjection(dark, page, controls, defaultAccent)
}

/**
 * The 777 UI theme. It keeps platform light/dark behavior while applying the current interaction
 * palette and the user's optional appearance overrides.
 */
@Composable
fun DshTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    accentKey: String? = null,
    backgroundPath: String? = null,
    backgroundAdaptiveContrast: Boolean = true,
    textScale: Float = 1f,
    textWeightAdjustment: Int = 0,
    wallpaperSurfaceTransparency: Float = 0.5f,
    ambientGlow: Float = 0.5f,
    componentEmphasis: Float = 0.5f,
    motionDurationScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val reducedMotion = rememberSyReducedMotion()
    val projected = resolveV6ThemePalette(preference, accentKey, systemDark)
    val dark = projected.dark
    val themed = projected.page
    val standardColors = projected.controls
    val weight = componentEmphasis.coerceIn(0f, 1f)
    val emphasisFill = if (weight <= .5f) {
        androidx.compose.ui.graphics.lerp(standardColors.materialL5, standardColors.selected, weight * 2f)
    } else {
        androidx.compose.ui.graphics.lerp(standardColors.selected, standardColors.primary, (weight - .5f) * .4f)
    }
    val controlPalette = standardColors.copy(selected = emphasisFill)
    val scheme = if (dark) materialDarkScheme(themed) else materialLightScheme(themed)
    val currentDensity = LocalDensity.current
    val clampedTextScale = textScale.coerceIn(AppDisplayLimits.MIN_READING_SCALE, AppDisplayLimits.MAX_READING_SCALE)
    val effectiveFontScale = (currentDensity.fontScale * clampedTextScale)
        .coerceAtMost(AppDisplayLimits.MAX_EFFECTIVE_FONT_SCALE)
    val readingPreferences = DsReadingPreferences(
        textScale = clampedTextScale,
        textWeightAdjustment = textWeightAdjustment.coerceIn(0, 2),
    )
    val userMotionScale = motionDurationScale.coerceIn(.7f, 1.3f)
    CompositionLocalProvider(
        com.shenyan.designsystem.foundation.LocalSyMotionTempo provides userMotionScale,
        LocalDsColors provides themed,
        LocalDsReadingPreferences provides readingPreferences,
        LocalDensity provides Density(
            density = currentDensity.density,
            fontScale = effectiveFontScale,
        ),
    ) {
        // Inside the theme, outside MaterialTheme: the image has to sit under every screen, and
        // screens draw their own Material surfaces on top of whatever is beneath them.
        AppBackgroundHost(
            path = backgroundPath,
            adaptiveEnabled = backgroundAdaptiveContrast,
            darkTheme = dark,
            surfaceBase = themed.bgBase,
            surfaceTransparency = wallpaperSurfaceTransparency.coerceIn(0f, 1f),
            fluidGlass = preference != ThemePreference.MATTE_BLACK,
            ambientGlow = ambientGlow.coerceIn(0f, 1f),
            ambientPalette = controlPalette,
        ) {
            // One canonical visual theme owns all Material3 colour and shape tokens.
            // Canonical V6 palette owns production controls; DsColors now only projects retained preferences.
            val syMode = when (preference) {
                ThemePreference.LIGHT -> SyMode.LIGHT
                ThemePreference.DARK -> SyMode.DARK
                ThemePreference.MATTE_BLACK -> SyMode.MATTE
                ThemePreference.SYSTEM -> SyMode.SYSTEM
            }
            val canonicalMode = when (preference) {
                ThemePreference.LIGHT -> com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
                ThemePreference.DARK -> com.shenyan.designsystem.foundation.ShenyanMode.DARK
                ThemePreference.MATTE_BLACK -> com.shenyan.designsystem.foundation.ShenyanMode.MATTE
                ThemePreference.SYSTEM -> if (systemDark)
                    com.shenyan.designsystem.foundation.ShenyanMode.DARK
                    else com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
            }
            com.shenyan.designsystem.foundation.ShenyanTheme(
                mode = canonicalMode,
                reduceMotion = reducedMotion,
                reduceTransparency = preference == ThemePreference.MATTE_BLACK,
            ) {
                CompositionLocalProvider(
                    com.shenyan.designsystem.foundation.LocalSyColors provides controlPalette,
                ) {
                    SyThemeProvider(
                        mode = syMode,
                        reduceMotion = reducedMotion,
                        reduceTransparency = preference == ThemePreference.MATTE_BLACK,
                    ) {
                        // Preserve user-defined accent and reading accessibility controls.
                        val resolvedScheme = if (projected.isDefaultAccent)
                            MaterialTheme.colorScheme else scheme
                        MaterialTheme(
                            colorScheme = resolvedScheme,
                            typography = dsTypography(readingPreferences.textWeightAdjustment),
                            shapes = MaterialTheme.shapes,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}
