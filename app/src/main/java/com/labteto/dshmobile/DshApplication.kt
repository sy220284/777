package com.labteto.dshmobile

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import com.labteto.dshmobile.connection.KeepAliveWorker
import com.labteto.dshmobile.notify.NotificationObserver
import com.labteto.dshmobile.update.UpdateCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltAndroidApp
class DshApplication : Application() {

    // Injecting this constructs SessionStore + ConnectionManager and starts
    // their frame collectors; start() then begins notification classification.
    @Inject lateinit var notificationObserver: NotificationObserver

    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        getSystemService(UiModeManager::class.java).setApplicationNightMode(
            applicationNightModeFor(storedThemePreference(this)),
        )
        KeepAliveWorker.schedule(this)
        notificationObserver.start()

        // Legacy update files are removed immediately. A verified APK that has already been handed
        // to Android's installer is kept only until either this installed build reaches the target
        // version or the short installer handoff window expires.
        maintenanceScope.launch {
            val retryAfter = UpdateCache.cleanupStale(
                cacheDir = cacheDir,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                nowMillis = System.currentTimeMillis(),
            )
            if (retryAfter != null) {
                delay(retryAfter)
                UpdateCache.cleanupStale(
                    cacheDir = cacheDir,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    nowMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    companion object {
        private const val UI_PREFS = "ui_prefs"
        private const val KEY_THEME = "theme_preference"

        /**
         * A synchronously-readable copy of the Appearance preference.
         *
         * The preference itself lives in DataStore, which is only readable from a coroutine — and
         * the scheme has to be known before any activity exists (see [applicationNightModeFor]). Reading
         * DataStore with `runBlocking` here deadlocked startup and left the app on its splash
         * screen, so this mirror exists purely to be readable at that moment.
         * [com.labteto.dshmobile.connection.HostsStore] writes it whenever the preference changes;
         * an absent value means the default, which is to follow the system.
         */
        fun storedThemePreference(context: Context): String? =
            context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)

        /** Mirror [themePreference] so the next process start can read it before any activity. */
        fun storeThemePreference(context: Context, themePreference: String) {
            context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, themePreference)
                .apply()
        }

        /**
         * Application-local night mode used by the platform resource layer.
         *
         * This fork starts at API 36, so UiModeManager can persist the app-local day/night mode
         * before MainActivity exists. That lets the system splash resolve the matching -night
         * resources instead of briefly following the phone's opposite scheme.
         *
         * Matte black shares the platform's night qualifier with Dark; MainActivity applies the
         * exact matte canvas colour during the splash-to-Compose hand-off.
         */
        fun applicationNightModeFor(themePreference: String?): Int = when (themePreference) {
            "light" -> UiModeManager.MODE_NIGHT_NO
            "dark", "matte_black" -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
    }
}
