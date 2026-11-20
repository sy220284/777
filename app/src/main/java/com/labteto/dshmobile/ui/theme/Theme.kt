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
enum class ThemePreference { LIGHT, MATTE_BLACK, SYSTEM }

/** Shenyan host semantic palette for screen-level preferences and system integration. */
data class SyHostColors(
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

object SyHostThemeTokens {
    val light = SyHostColors(
        bgBase = SyHostLight.bgBase, bgLayer1 = SyHostLight.bgLayer1, bgLayer2 = SyHostLight.bgLayer2,
        bgLayer3 = SyHostLight.bgLayer3, bgModulePlatform = SyHostLight.bgModulePlatform,
        borderL1 = SyHostLight.borderL1, borderL2 = SyHostLight.borderL2, borderL3 = SyHostLight.borderL3,
        brandPrimary = SyHostLight.brandPrimary, onBrandPrimary = SyHostLight.onBrandPrimary,
        labelPrimary = SyHostLight.labelPrimary, labelSecondary = SyHostLight.labelSecondary,
        labelTertiary = SyHostLight.labelTertiary, labelCaption = SyHostLight.labelCaption,
        labelDimmed = SyHostLight.labelDimmed,
        accent = SyHostLight.accent, onAccent = SyHostLight.onAccent,
        accentTertiary = SyHostLight.accentTertiary,
        characterAccent = Color(0xFF9A684D),
        characterAccentTertiary = Color(0x30C98B6B),
        accentHover = SyHostLight.accentHover,
        hover = SyHostLight.hover, hoverSolid = SyHostLight.hoverSolid, hoverAccent = SyHostLight.hoverAccent,
        active = SyHostLight.active, dangerHover = SyHostLight.dangerHover,
        buttonPrimaryHover = SyHostLight.buttonPrimaryHover, buttonPrimaryDimmed = SyHostLight.buttonPrimaryDimmed,
        buttonInfoFill = SyHostLight.buttonInfoFill, buttonInfoHover = SyHostLight.buttonInfoHover,
        error = SyHostLight.error, onError = Color.White, errorSecondary = SyHostLight.errorSecondary, errorTertiary = SyHostLight.errorTertiary,
        success = SyHostLight.success, successSecondary = SyHostLight.successSecondary,
        successTertiary = SyHostLight.successTertiary,
        warnLabel = SyHostLight.warnLabel, warn = SyHostLight.warn, warnSecondary = SyHostLight.warnSecondary,
        warnTertiary = SyHostLight.warnTertiary,
        progressFailedStart = SyHostLight.progressFailedStart,
        progressFailedEnd = SyHostLight.progressFailedEnd,
        toastBg = SyHostLight.toastBg, onToast = Color.White, tooltipBg = SyHostLight.tooltipBg,
        userBubble = SyHostLight.userBubble, userBubbleHighlight = SyHostLight.userBubbleHighlight,
        composerCard = SyHostLight.composerCard,
        sidebar = SyHostLight.sidebar, sidebarNavActive = SyHostLight.sidebarNavActive,
        sidebarNavAccent = SyHostLight.sidebarNavAccent, sidebarNavHover = SyHostLight.sidebarNavHover,
        tipSurface = SyHostLight.tipSurface,
        codeBlockBg = SyHostLight.codeBlockBg, codeBlockBanner = SyHostLight.codeBlockBanner,
        inlineCode = SyHostLight.inlineCode, citation = SyHostLight.citation,
        markdownTag = SyHostLight.markdownTag, overlayMask = SyHostLight.overlayMask,
    )

    val matteBlack = SyHostColors(
        bgBase = SyHostMatte.bgBase, bgLayer1 = SyHostMatte.bgLayer1, bgLayer2 = SyHostMatte.bgLayer2,
        bgLayer3 = SyHostMatte.bgLayer3, bgModulePlatform = SyHostMatte.bgModulePlatform,
        borderL1 = SyHostMatte.borderL1, borderL2 = SyHostMatte.borderL2, borderL3 = SyHostMatte.borderL3,
        brandPrimary = SyHostMatte.brandPrimary, onBrandPrimary = SyHostMatte.onBrandPrimary,
        labelPrimary = SyHostMatte.labelPrimary, labelSecondary = SyHostMatte.labelSecondary,
        labelTertiary = SyHostMatte.labelTertiary, labelCaption = SyHostMatte.labelCaption,
        labelDimmed = SyHostMatte.labelDimmed,
        accent = SyHostMatte.accent, onAccent = SyHostMatte.onAccent,
        accentTertiary = SyHostMatte.accentTertiary,
        characterAccent = Color(0xFFD7A477),
        characterAccentTertiary = Color(0x30C98B6B),
        accentHover = SyHostMatte.accentHover,
        hover = SyHostMatte.hover, hoverSolid = SyHostMatte.hoverSolid, hoverAccent = SyHostMatte.hoverAccent,
        active = SyHostMatte.active, dangerHover = SyHostMatte.dangerHover,
        buttonPrimaryHover = SyHostMatte.buttonPrimaryHover, buttonPrimaryDimmed = SyHostMatte.buttonPrimaryDimmed,
        buttonInfoFill = SyHostMatte.buttonInfoFill, buttonInfoHover = SyHostMatte.buttonInfoHover,
        error = SyHostMatte.error, onError = Color(0xFF0C1A19), errorSecondary = SyHostMatte.errorSecondary, errorTertiary = SyHostMatte.errorTertiary,
        success = SyHostMatte.success, successSecondary = SyHostMatte.successSecondary,
        successTertiary = SyHostMatte.successTertiary,
        warnLabel = SyHostMatte.warnLabel, warn = SyHostMatte.warn, warnSecondary = SyHostMatte.warnSecondary,
        warnTertiary = SyHostMatte.warnTertiary,
        progressFailedStart = SyHostMatte.progressFailedStart,
        progressFailedEnd = SyHostMatte.progressFailedEnd,
        toastBg = SyHostMatte.toastBg, onToast = Color(0xFFF5F0E8), tooltipBg = SyHostMatte.tooltipBg,
        userBubble = SyHostMatte.userBubble, userBubbleHighlight = SyHostMatte.userBubbleHighlight,
        composerCard = SyHostMatte.composerCard,
        sidebar = SyHostMatte.sidebar, sidebarNavActive = SyHostMatte.sidebarNavActive,
        sidebarNavAccent = SyHostMatte.sidebarNavAccent, sidebarNavHover = SyHostMatte.sidebarNavHover,
        tipSurface = SyHostMatte.tipSurface,
        codeBlockBg = SyHostMatte.codeBlockBg, codeBlockBanner = SyHostMatte.codeBlockBanner,
        inlineCode = SyHostMatte.inlineCode, citation = SyHostMatte.citation,
        markdownTag = SyHostMatte.markdownTag, overlayMask = SyHostMatte.overlayMask,
    )


}

/** The resolved 777 UI palette as a CompositionLocal. */
val LocalSyHostColors = staticCompositionLocalOf { SyHostThemeTokens.light }

data class SyReadingPreferences(
    val textScale: Float = 1f,
    val textWeightAdjustment: Int = 0,
)

val LocalSyReadingPreferences = staticCompositionLocalOf { SyReadingPreferences() }

object SyHostTheme {
    val colors: SyHostColors
        @Composable get() = LocalSyHostColors.current
}

private fun materialLightScheme(c: SyHostColors) = lightColorScheme(
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

private fun materialDarkScheme(c: SyHostColors) = darkColorScheme(
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
 * SyHostColors supplies screen preferences while canonical SyColors owns components; production surfaces use [controls].
 */
internal data class V6ThemeProjection(
    val dark: Boolean,
    val page: SyHostColors,
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
        ThemePreference.MATTE_BLACK -> true
        ThemePreference.SYSTEM -> systemDark
    }
    // Both explicit night and system-night use the same matte-black material.
    val base = if (dark) SyHostThemeTokens.matteBlack else SyHostThemeTokens.light
    val canonical = if (dark)
        com.shenyan.designsystem.foundation.ShenyanPalettes.matte
    else com.shenyan.designsystem.foundation.ShenyanPalettes.light
    val palette = AccentPalettes.of(accentKey)
    val defaultAccent = palette.key == AccentPalettes.DEFAULT.key
    val rawPage = (if (defaultAccent) base else base.withAccent(palette, dark))
        .harmonizeV6(canonical, preserveAccent = !defaultAccent)
    val controls = if (defaultAccent) canonical else canonical.withV6Accent(rawPage)
    val page = if (defaultAccent) rawPage else rawPage.copy(
        borderL1 = controls.border.copy(alpha = controls.border.alpha * .55f),
        borderL2 = controls.border.copy(alpha = controls.border.alpha * .75f),
        borderL3 = controls.border,
    )
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
    val readingPreferences = SyReadingPreferences(
        textScale = clampedTextScale,
        textWeightAdjustment = textWeightAdjustment.coerceIn(0, 2),
    )
    val userMotionScale = motionDurationScale.coerceIn(.7f, 1.3f)
    CompositionLocalProvider(
        com.shenyan.designsystem.foundation.LocalSyMotionTempo provides userMotionScale,
        LocalSyHostColors provides themed,
        LocalSyReadingPreferences provides readingPreferences,
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
            fluidGlass = true,
            ambientGlow = ambientGlow.coerceIn(0f, 1f),
            ambientPalette = controlPalette,
        ) {
            // One canonical visual theme owns all Material3 colour and shape tokens.
            // Canonical V6 palette owns production controls; SyHostColors now only projects retained preferences.
            val syMode = if (dark) SyMode.MATTE else SyMode.LIGHT
            val canonicalMode = if (dark)
                com.shenyan.designsystem.foundation.ShenyanMode.MATTE
            else com.shenyan.designsystem.foundation.ShenyanMode.LIGHT
            com.shenyan.designsystem.foundation.ShenyanTheme(
                mode = canonicalMode,
                reduceMotion = reducedMotion,
                reduceTransparency = false,
            ) {
                CompositionLocalProvider(
                    com.shenyan.designsystem.foundation.LocalSyColors provides controlPalette,
                ) {
                    SyThemeProvider(
                        mode = syMode,
                        reduceMotion = reducedMotion,
                        reduceTransparency = false,
                    ) {
                        // Preserve user-defined accent and reading accessibility controls.
                        val resolvedScheme = if (projected.isDefaultAccent)
                            MaterialTheme.colorScheme else scheme
                        MaterialTheme(
                            colorScheme = resolvedScheme,
                            typography = syTypography(readingPreferences.textWeightAdjustment),
                            shapes = MaterialTheme.shapes,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}
