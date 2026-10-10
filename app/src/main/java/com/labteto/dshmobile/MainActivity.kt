package com.labteto.dshmobile

import android.app.UiModeManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.mutableStateOf
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.local.presentation.ChatGptSettingsController
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var chatGptAccounts: ChatGptSettingsController
    @Inject lateinit var notifications: DshNotifications

    private val requestedLocalSession = mutableStateOf<String?>(null)
    private var appliedApplicationNightMode: Int? = null
    private var requestedNotificationPermission = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — notifications degrade gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val storedTheme = DshApplication.storedThemePreference(this)
        val uiModeManager = getSystemService(UiModeManager::class.java)
        appliedApplicationNightMode =
            DshApplication.applicationNightModeFor(storedTheme, uiModeManager.nightMode)
        requestedLocalSession.value = savedInstanceState?.getString("pending_local_session")
            ?: notificationLocalSession(intent)
        applyWindowBackground(storedTheme)
        enableEdgeToEdge()
        notifications.ensureChannels()

        // Keep runtime appearance aligned with persisted settings.
        lifecycleScope.launch {
            hostsStore.settings.collect { settings ->
                applyWindowBackground(settings.themePreference)
                applyNightMode(settings.themePreference)
            }
        }

        setContent {
            AppRoot(
                requestedLocalSessionId = requestedLocalSession.value,
                onLocalSessionRequestConsumed = {
                    requestedLocalSession.value = null
                    intent.removeExtra(DshNotifications.EXTRA_LOCAL_SESSION_ID)
                },
            )
        }
        requestNotificationPermissionOnce()
    }

    private fun requestNotificationPermissionOnce() {
        if (requestedNotificationPermission) return
        requestedNotificationPermission = true
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { chatGptAccounts.refresh() }
    }

    private fun notificationLocalSession(intent: Intent): String? =
        intent.getStringExtra(DshNotifications.EXTRA_LOCAL_SESSION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedLocalSession.value = notificationLocalSession(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_local_session", requestedLocalSession.value)
        super.onSaveInstanceState(outState)
    }

    /** Persist the app-local qualifier so the platform splash uses the same day/night scheme. */
    private fun applyNightMode(themePreference: String) {
        val uiModeManager = getSystemService(UiModeManager::class.java)
        val mode = DshApplication.applicationNightModeFor(themePreference, uiModeManager.nightMode)
        if (appliedApplicationNightMode == mode) return
        appliedApplicationNightMode = mode
        uiModeManager.setApplicationNightMode(mode)
    }

    /** Match the post-splash window to the exact Compose canvas, including Matte black. */
    private fun applyWindowBackground(themePreference: String?) {
        val color = when (themePreference) {
            "light" -> getColor(R.color.window_background_light)
            "dark" -> getColor(R.color.window_background_dark)
            "matte_black" -> getColor(R.color.window_background_matte_black)
            else -> getColor(R.color.window_background)
        }
        window.setBackgroundDrawable(ColorDrawable(color))
    }
}
