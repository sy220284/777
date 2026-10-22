package com.labteto.dshmobile.connection

import android.content.Context

/**
 * Only the synchronously readable bootstrap mirror of DataStore-owned Appearance settings.
 * No new authority: call [store] only after the corresponding DataStore commit succeeds.
 */
internal object ThemeBootstrapPreferences {
    private const val UI_PREFS = "ui_prefs"
    private const val KEY_THEME = "theme_preference"

    fun read(context: Context): String? =
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)

    fun store(context: Context, themePreference: String) {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, themePreference)
            .apply()
    }
}
