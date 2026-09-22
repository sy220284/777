package com.labteto.dshmobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var notifications: DshNotifications

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — notifications degrade gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notifications.ensureChannels()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)

        // Apply the persisted in-app language (English or Simplified Chinese).
        //
        // Only set the locale when it actually differs, or the recreate it triggers loops.
        lifecycleScope.launch {
            hostsStore.settings.collect { settings ->
                applyNightMode(settings.themePreference)

                val desiredLocales = settings.localeOverride?.let { tag ->
                    LocaleListCompat.forLanguageTags(tag)
                } ?: LocaleListCompat.getEmptyLocaleList()
                
                val currentLocales = AppCompatDelegate.getApplicationLocales()
                
                // Only update if locales actually changed to prevent recreation loop
                if (desiredLocales.toLanguageTags() != currentLocales.toLanguageTags()) {
                    AppCompatDelegate.setApplicationLocales(desiredLocales)
                }
            }
        }

        setContent {
            AppRoot()
        }
    }

    /**
     * Keep the resource layer's scheme in step when the preference *changes* while running.
     *
     * [DshApplication.applyStoredNightMode] does the same thing at process start, which is where
     * the cost is zero; this only has to catch someone tapping Light or Dark. The guard is what
     * makes that true — without it, every settings emission would re-enter
     * [AppCompatDelegate.setDefaultNightMode] and recreate the activity.
     */
    private fun applyNightMode(themePreference: String) {
        val mode = DshApplication.nightModeFor(themePreference)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
