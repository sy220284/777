package com.labteto.dshmobile.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val PRESET_WALLPAPER_ENABLED = booleanPreferencesKey("zhaoxi_e818_wallpaper_enabled")
        val SELECTED_WALLPAPER_PRESET = stringPreferencesKey("zhaoxi_e818_wallpaper_preset")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val TEXT_WEIGHT_ADJUSTMENT = intPreferencesKey("text_weight_adjustment")
        val WALLPAPER_SURFACE_TRANSPARENCY = floatPreferencesKey("wallpaper_surface_transparency")
        val AMBIENT_GLOW = floatPreferencesKey("appearance_ambient_glow")
        val COMPONENT_EMPHASIS = floatPreferencesKey("appearance_component_emphasis")
        val MOTION_DURATION_SCALE = floatPreferencesKey("appearance_motion_duration_scale")
        val NOTIFY_LOCAL_JOBS = booleanPreferencesKey("notify_local_jobs")
        val NOTIFY_JOB_RESULTS = booleanPreferencesKey("notify_job_results")
        val NOTIFY_JOB_FAILURES = booleanPreferencesKey("notify_job_failures")
        val NOTIFY_NEEDS_ACTION = booleanPreferencesKey("notify_needs_action")
        val NOTIFY_CHAT_MESSAGES = booleanPreferencesKey("notify_chat_messages")
        val NOTIFICATION_QUIET_ENABLED = booleanPreferencesKey("notification_quiet_enabled")
        val NOTIFICATION_QUIET_START = intPreferencesKey("notification_quiet_start")
        val NOTIFICATION_QUIET_END = intPreferencesKey("notification_quiet_end")
        val THEME = stringPreferencesKey("theme")
        val ACCENT_THEME = stringPreferencesKey("accent_theme")
        val RETIRED_LOCALE = stringPreferencesKey("locale")
    }

    private val retiredStringKeys = listOf(
        "hosts_json", "hosts_json_backup", "desired_host_id",
        "last_sessions_json", "last_sessions_json_backup",
        "harness_sessions_json", "harness_sessions_json_backup",
        "relay_tokens_json", "relay_tokens_json_backup",
        "session_sort",
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
            notifyJobResults = prefs[Keys.NOTIFY_JOB_RESULTS] ?: true,
            notifyJobFailures = prefs[Keys.NOTIFY_JOB_FAILURES] ?: true,
            notifyNeedsAction = prefs[Keys.NOTIFY_NEEDS_ACTION] ?: true,
            notifyChatMessages = prefs[Keys.NOTIFY_CHAT_MESSAGES] ?: true,
            notificationQuietHoursEnabled = prefs[Keys.NOTIFICATION_QUIET_ENABLED] ?: false,
            notificationQuietStartMinute = (prefs[Keys.NOTIFICATION_QUIET_START] ?: 1380).coerceIn(0, 1439),
            notificationQuietEndMinute = (prefs[Keys.NOTIFICATION_QUIET_END] ?: 420).coerceIn(0, 1439),
            themePreference = prefs[Keys.THEME] ?: "system",
            accentTheme = prefs[Keys.ACCENT_THEME] ?: "celadon",
            backgroundImagePath = prefs[Keys.BACKGROUND_IMAGE],
            sidebarAvatarSource = prefs[Keys.SIDEBAR_AVATAR],
            backgroundAdaptiveContrast = prefs[Keys.BACKGROUND_ADAPTIVE_CONTRAST] ?: true,
            presetWallpaperEnabled = prefs[Keys.PRESET_WALLPAPER_ENABLED] ?: false,
            selectedWallpaperPreset = prefs[Keys.SELECTED_WALLPAPER_PRESET] ?: "auto",
            textScale = (prefs[Keys.TEXT_SCALE] ?: 1.0f).coerceIn(AppDisplayLimits.MIN_READING_SCALE, AppDisplayLimits.MAX_READING_SCALE),
            textWeightAdjustment = (prefs[Keys.TEXT_WEIGHT_ADJUSTMENT] ?: 0).coerceIn(0, 2),
            wallpaperSurfaceTransparency =
                (prefs[Keys.WALLPAPER_SURFACE_TRANSPARENCY] ?: 0.5f).coerceIn(0f, 1f),
            ambientGlow = (prefs[Keys.AMBIENT_GLOW] ?: .5f).coerceIn(0f, 1f),
            componentEmphasis = (prefs[Keys.COMPONENT_EMPHASIS] ?: .5f).coerceIn(0f, 1f),
            motionDurationScale = (prefs[Keys.MOTION_DURATION_SCALE] ?: 1f).coerceIn(0f, 1.3f),
        )

    suspend fun setSetting(transform: (AppSettings) -> AppSettings) {
        var committed: AppSettings? = null
        dataStore.edit { prefs ->
            val next = transform(decodeSettings(prefs))
            prefs[Keys.NOTIFY_LOCAL_JOBS] = next.notifyLocalJobs
            prefs[Keys.NOTIFY_JOB_RESULTS] = next.notifyJobResults
            prefs[Keys.NOTIFY_JOB_FAILURES] = next.notifyJobFailures
            prefs[Keys.NOTIFY_NEEDS_ACTION] = next.notifyNeedsAction
            prefs[Keys.NOTIFY_CHAT_MESSAGES] = next.notifyChatMessages
            prefs[Keys.NOTIFICATION_QUIET_ENABLED] = next.notificationQuietHoursEnabled
            prefs[Keys.NOTIFICATION_QUIET_START] = next.notificationQuietStartMinute.coerceIn(0, 1439)
            prefs[Keys.NOTIFICATION_QUIET_END] = next.notificationQuietEndMinute.coerceIn(0, 1439)
            prefs[Keys.THEME] = next.themePreference
            prefs[Keys.ACCENT_THEME] = next.accentTheme
            next.backgroundImagePath?.let { prefs[Keys.BACKGROUND_IMAGE] = it }
                ?: prefs.remove(Keys.BACKGROUND_IMAGE)
            next.sidebarAvatarSource?.takeIf(String::isNotBlank)?.let {
                prefs[Keys.SIDEBAR_AVATAR] = it
            } ?: prefs.remove(Keys.SIDEBAR_AVATAR)
            prefs[Keys.BACKGROUND_ADAPTIVE_CONTRAST] = next.backgroundAdaptiveContrast
            prefs[Keys.PRESET_WALLPAPER_ENABLED] = next.presetWallpaperEnabled
            prefs[Keys.SELECTED_WALLPAPER_PRESET] = next.selectedWallpaperPreset
            prefs[Keys.TEXT_SCALE] = next.textScale.coerceIn(AppDisplayLimits.MIN_READING_SCALE, AppDisplayLimits.MAX_READING_SCALE)
            prefs[Keys.TEXT_WEIGHT_ADJUSTMENT] = next.textWeightAdjustment.coerceIn(0, 2)
            prefs[Keys.WALLPAPER_SURFACE_TRANSPARENCY] =
                next.wallpaperSurfaceTransparency.coerceIn(0f, 1f)
            prefs[Keys.AMBIENT_GLOW] = next.ambientGlow.coerceIn(0f, 1f)
            prefs[Keys.COMPONENT_EMPHASIS] = next.componentEmphasis.coerceIn(0f, 1f)
            prefs[Keys.MOTION_DURATION_SCALE] = next.motionDurationScale.coerceIn(0f, 1.3f)
            committed = next
        }
        // Mirrored out to SharedPreferences as well: the scheme has to be readable before any
        // activity exists, and DataStore cannot be read from there. Mirror only after the
        // transactional write succeeds so a failed DataStore update cannot leave startup theme
        // state ahead of the durable settings.
        ThemeBootstrapPreferences.store(context, checkNotNull(committed).themePreference)
    }
}
