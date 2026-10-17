package com.labteto.dshmobile.uipack.v6

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.uicontract.UiHostPreferences
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/** V6 alone owns its full visual frame. The neutral Host renders no V6 tokens. */
@Composable
internal fun V6VisualFrame(
    preferences: UiHostPreferences,
    content: @Composable () -> Unit,
) {
    val theme = ThemePreference.entries.firstOrNull {
        it.name.equals(preferences.theme, ignoreCase = true)
    } ?: ThemePreference.SYSTEM
    DshTheme(
        preference = theme,
        accentKey = preferences.accent,
        backgroundPath = preferences.backgroundPath,
        backgroundAdaptiveContrast = preferences.adaptiveContrast,
        textScale = preferences.textScale,
        textWeightAdjustment = preferences.textWeight,
        wallpaperSurfaceTransparency = preferences.transparency,
        ambientGlow = preferences.ambientGlow,
        componentEmphasis = preferences.componentEmphasis,
        motionDurationScale = preferences.motionScale,
        content = content,
    )
}
