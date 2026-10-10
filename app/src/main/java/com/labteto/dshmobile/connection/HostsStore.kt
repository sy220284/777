package com.labteto.dshmobile.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.DshApplication
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Application preference store. Name retained to avoid disrupting existing Hilt consumers. */
@Singleton
class HostsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val BACKGROUND_IMAGE = stringPreferencesKey("background_image_path")
        val SIDEBAR_AVATAR = stringPreferencesKey("sidebar_avatar_source")
        val BACKGROUND_ADAPTIVE_CONTRAST = booleanPreferencesKey("background_adaptive_contrast")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val TEXT_WEIGHT_ADJUSTMENT = intPreferencesKey("text_weight_adjustment")
        val WALLPAPER_SURFACE_TRANSPARENCY = floatPreferencesKey("wallpaper_surface_transparency")
        val NOTIFY_LOCAL_JOBS = booleanPreferencesKey("notify_local_jobs")
        val THEME = stringPreferencesKey("theme")
        val ACCENT_THEME = stringPreferencesKey("accent_theme")
        val RETIRED_LOCALE = stringPreferencesKey("locale")
        val SESSION_SORT = stringPreferencesKey("session_sort")
    }

    private val retiredStringKeys = listOf(
        "hosts_json", "hosts_json_backup", "desired_host_id",
        "last_sessions_json", "last_sessions_json_backup",
        "harness_sessions_json", "harness_sessions_json_backup",
        "relay_tokens_json", "relay_tokens_json_backup",
    ).map(::stringPreferencesKey)

    private val retiredBooleanKeys = listOf(
        "background", "notify_turn", "notify_goal", "notify_action",
    ).map(::booleanPreferencesKey)

    val settings: Flow<AppSettings> = dataStore.data.map(::decodeSettings)
    suspend fun settingsOnce(): AppSettings = settings.first()

    /** Retire obsolete host and pairing data while preserving all current user preferences. */
    suspend fun clearRetiredRemoteData() {
        dataStore.edit { prefs ->
            retiredStringKeys.forEach { key -> prefs.remove(key) }
            retiredBooleanKeys.forEach { key -> prefs.remove(key) }
        }
        // Token encryption key was only used by legacy remote pairing.
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("dsh_relay_tokens")
        }.onFailure {
            com.labteto.dshmobile.observability.AppLog.warn(
                "HostsStore", "清理旧中继凭据密钥失败", it,
            )
        }
    }

    suspend fun clearRetiredLocalePreference() {
        if (dataStore.data.first()[Keys.RETIRED_LOCALE] == null) return
        dataStore.edit { it.remove(Keys.RETIRED_LOCALE) }
    }

    private fun decodeSettings(prefs: Preferences): AppSettings =
        AppSettings(
            notifyLocalJobs = prefs[Keys.NOTIFY_LOCAL_JOBS] ?: true,
            themePreference = prefs[Keys.THEME] ?: "system",
            accentTheme = prefs[Keys.ACCENT_THEME] ?: "celadon",
            backgroundImagePath = prefs[Keys.BACKGROUND_IMAGE],
            sidebarAvatarSource = prefs[Keys.SIDEBAR_AVATAR],
            backgroundAdaptiveContrast = prefs[Keys.BACKGROUND_ADAPTIVE_CONTRAST] ?: true,
            textScale = (prefs[Keys.TEXT_SCALE] ?: 1.0f).coerceIn(0.9f, 1.3f),
            textWeightAdjustment = (prefs[Keys.TEXT_WEIGHT_ADJUSTMENT] ?: 0).coerceIn(0, 2),
            wallpaperSurfaceTransparency =
                (prefs[Keys.WALLPAPER_SURFACE_TRANSPARENCY] ?: 0.5f).coerceIn(0f, 1f),
        )

    /** Drawer session ordering: `"manual"` follows the workspace order, `"updated"` sorts by recency. */
    val sessionSort: Flow<String> = dataStore.data.map { it[Keys.SESSION_SORT] ?: "manual" }

    suspend fun setSessionSort(value: String) {
        dataStore.edit { it[Keys.SESSION_SORT] = value }
    }

    suspend fun setSetting(transform: (AppSettings) -> AppSettings) {
        var committed: AppSettings? = null
        dataStore.edit { prefs ->
            val next = transform(decodeSettings(prefs))
            prefs[Keys.NOTIFY_LOCAL_JOBS] = next.notifyLocalJobs
            prefs[Keys.THEME] = next.themePreference
            prefs[Keys.ACCENT_THEME] = next.accentTheme
            next.backgroundImagePath?.let { prefs[Keys.BACKGROUND_IMAGE] = it }
                ?: prefs.remove(Keys.BACKGROUND_IMAGE)
            next.sidebarAvatarSource?.takeIf(String::isNotBlank)?.let {
                prefs[Keys.SIDEBAR_AVATAR] = it
            } ?: prefs.remove(Keys.SIDEBAR_AVATAR)
            prefs[Keys.BACKGROUND_ADAPTIVE_CONTRAST] = next.backgroundAdaptiveContrast
            prefs[Keys.TEXT_SCALE] = next.textScale.coerceIn(0.9f, 1.3f)
            prefs[Keys.TEXT_WEIGHT_ADJUSTMENT] = next.textWeightAdjustment.coerceIn(0, 2)
            prefs[Keys.WALLPAPER_SURFACE_TRANSPARENCY] =
                next.wallpaperSurfaceTransparency.coerceIn(0f, 1f)
            committed = next
        }
        // Mirrored out to SharedPreferences as well: the scheme has to be readable before any
        // activity exists, and DataStore cannot be read from there. Mirror only after the
        // transactional write succeeds so a failed DataStore update cannot leave startup theme
        // state ahead of the durable settings.
        DshApplication.storeThemePreference(context, checkNotNull(committed).themePreference)
    }
}
