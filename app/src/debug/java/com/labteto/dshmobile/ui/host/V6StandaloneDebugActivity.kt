package com.labteto.dshmobile.ui.host

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.AppViewModel
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiCapabilityId
import dagger.hilt.android.AndroidEntryPoint

/**
 * Real, separately launchable V6-only debug entry. All scenes and actions are
 * backed by actual Hilt Feature Ports and the production Host; no mock owners.
 * Never constructs LocalHarnessViewModel, LocalHarnessScreen or Legacy callbacks.
 *
 * adb shell am start -n com.labteto.dshmobile/.ui.host.V6StandaloneDebugActivity
 */
@AndroidEntryPoint
class V6StandaloneDebugActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG) { "V6 verification entry must not ship in release" }
        getSharedPreferences("ui_application_pack_v6_only_debug", Context.MODE_PRIVATE)
            .edit().putString("selected_pack", "v6-application-migrating").commit()
        setContent {
            val app: AppViewModel = hiltViewModel()
            val owner: LocalV6StandaloneViewModel = hiltViewModel()
            val requestedScene by owner.sceneRequest.collectAsStateWithLifecycle()
            DshTheme {
                LocalApplicationUiHost(
                    installedPacks = remember(owner, app) {
                        listOf(owner.pack(app.updateInstallStatus) {
                            app.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                        })
                    },
                    // This seal exists only in the debug-exclusive launch path.
                    // Neither AppRoot nor a production Owner can advertise it.
                    availableCapabilities = owner.connectedCapabilities +
                        UiCapabilityId("v6.migration.accepted"),
                    selectionPreferenceName = "ui_application_pack_v6_only_debug",
                    requestedSceneKey = requestedScene,
                    notificationSessions = owner.sessions,
                    onSceneRequestProcessed = { key, _ -> owner.consumeSceneRequest(key) },
                    legacyContent = {
                        error("V6-only verification must not fall back to Legacy Shell")
                    },
                )
            }
        }
    }
}
