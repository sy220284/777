package com.labteto.dshmobile

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
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

    private val requestedSession = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — notifications degrade gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        requestedSession.value = savedInstanceState?.getString("pending_session")
            ?: notificationSession(intent)
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
            AppRoot(
                requestedSessionId = requestedSession.value,
                onSessionRequestConsumed = {
                    requestedSession.value = null
                    intent.removeExtra(DshNotifications.EXTRA_SESSION_ID)
                    intent.data = null
                },
            )
        }
    }

    private fun notificationSession(intent: Intent): String? {
        val uri = intent.data
        val fromUri = uri?.takeIf { it.scheme == "dshmobile" && it.host == "host" }
            ?.pathSegments?.takeIf { it.size == 3 && it[0] == "current" && it[1] == "session" }?.get(2)
        return (intent.getStringExtra(DshNotifications.EXTRA_SESSION_ID) ?: fromUri)
            ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSession.value = notificationSession(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_session", requestedSession.value)
        super.onSaveInstanceState(outState)
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
