package com.labteto.dshmobile

import android.app.Application
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.os.LocaleList
import com.labteto.dshmobile.connection.HostsStore
import androidx.work.WorkManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeKernel
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.update.UpdateCache
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltAndroidApp
class DshApplication : Application() {

    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var localRuntimeKernel: LocalRuntimeKernel

    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        clearRetiredApplicationLocale()
        AppLog.configurePersistence(File(filesDir, "diagnostics/app-log.tsv"))
        val uiModeManager = getSystemService(UiModeManager::class.java)
        uiModeManager.setApplicationNightMode(
            applicationNightModeFor(storedThemePreference(this), uiModeManager.nightMode),
        )
        // Cancel relay reconnect work left by older installations.
        WorkManager.getInstance(this).cancelUniqueWork("dsh-keep-alive")
        localRuntimeKernel.start()

        // Legacy update files are removed immediately. A verified APK that has already been handed
        // to Android's installer is kept only until either this installed build reaches the target
        // version or the short installer handoff window expires.
        maintenanceScope.launch {
            hostsStore.clearRetiredLocalePreference()
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

    /**
     * Clear the platform-level per-app locale left by older builds before any Activity exists.
     *
     * This is migration-only behavior. Remove it once the supported upgrade path no longer includes
     * a build that exposed Android per-app language selection.
     */
    private fun clearRetiredApplicationLocale() {
        val localeManager = getSystemService(LocaleManager::class.java)
        if (!localeManager.applicationLocales.isEmpty) {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
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
         * The System option mirrors the device's configured night policy. Matte black shares the platform's night qualifier with Dark; MainActivity applies the
         * exact matte canvas colour during the splash-to-Compose hand-off.
         */
        fun applicationNightModeFor(themePreference: String?, systemNightMode: Int): Int =
            when (themePreference) {
                "light" -> UiModeManager.MODE_NIGHT_NO
                "dark", "matte_black" -> UiModeManager.MODE_NIGHT_YES
                else -> when (systemNightMode) {
                    UiModeManager.MODE_NIGHT_AUTO,
                    UiModeManager.MODE_NIGHT_CUSTOM,
                    UiModeManager.MODE_NIGHT_NO,
                    UiModeManager.MODE_NIGHT_YES -> systemNightMode
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
            }
    }
}
