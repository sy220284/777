package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R

/**
 * User-facing label for the active Clear Realm appearance.
 *
 * Keep this mapping in the settings presentation layer so the settings root can show the
 * current theme directly instead of an unrelated typography percentage.
 */
@Composable
internal fun appearanceThemeLabel(themePreference: String): String = stringResource(
    when (themePreference) {
        "light" -> R.string.settings_appearance_light
        "dark" -> R.string.settings_appearance_dark
        "matte_black" -> R.string.settings_appearance_matte_black
        else -> R.string.settings_appearance_system
    },
)
