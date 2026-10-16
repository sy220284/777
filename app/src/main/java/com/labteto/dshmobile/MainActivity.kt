package com.labteto.dshmobile

import android.app.UiModeManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.mutableStateOf
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.local.presentation.ChatGptSettingsController
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppRoot
import com.labteto.dshmobile.ui.host.LocalApplicationSceneDeepLinks
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var chatGptAccounts: ChatGptSettingsController
    @Inject lateinit var notifications: DshNotifications

    private val requestedLocalSession = mutableStateOf<String?>(null)
    private val requestedExternalScene = mutableStateOf<String?>(null)
    private var appliedApplicationNightMode: Int? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val storedTheme = DshApplication.storedThemePreference(this)
        val uiModeManager = getSystemService(UiModeManager::class.java)
        appliedApplicationNightMode =
            DshApplication.applicationNightModeFor(storedTheme, uiModeManager.nightMode)
        requestedLocalSession.value = savedInstanceState?.getString("pending_local_session")
            ?: notificationLocalSession(intent)
        // A saved null means the previous VIEW request was consumed; do not replay it.
        requestedExternalScene.value = if (savedInstanceState?.containsKey("pending_ui_scene") == true)
            savedInstanceState.getString("pending_ui_scene") else externalSceneFromIntent(intent)
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
                requestedExternalSceneKey = requestedExternalScene.value,
                onExternalSceneRequestConsumed = {
                    requestedExternalScene.value = null
                    intent.data = null
                },
                onLocalSessionRequestConsumed = {
                    requestedLocalSession.value = null
                    intent.removeExtra(DshNotifications.EXTRA_LOCAL_SESSION_ID)
                },
            )
        }
        // Permission is requested by an explicit user action in Settings,
        // after an explanation, rather than interrupting first launch.
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { chatGptAccounts.refresh() }
    }

    private fun notificationLocalSession(intent: Intent): String? =
        intent.getStringExtra(DshNotifications.EXTRA_LOCAL_SESSION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }

    private fun externalSceneFromIntent(intent: Intent): String? =
        if (intent.action == Intent.ACTION_VIEW)
            LocalApplicationSceneDeepLinks.fromExternalUri(intent.dataString)
        else null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedLocalSession.value = notificationLocalSession(intent)
        requestedExternalScene.value = externalSceneFromIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_local_session", requestedLocalSession.value)
        outState.putString("pending_ui_scene", requestedExternalScene.value)
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
