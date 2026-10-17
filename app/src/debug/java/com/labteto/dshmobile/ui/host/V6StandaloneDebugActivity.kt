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
import com.labteto.dshmobile.uihost.ProvideUiHostSystemOperationOwner
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
            val privateText = remember { LocalEncryptedUiDraftStore(this) }
            androidx.compose.runtime.CompositionLocalProvider(
                com.labteto.dshmobile.uihost.LocalUiPrivateTextCipher provides privateText,
            ) {
                ProvideUiHostSystemOperationOwner {
                    LocalApplicationUiHost(
                        installedPacks = remember(owner, app) {
                            listOf(owner.pack(
                                updateStatus = app.updateInstallStatus,
                                checkForUpdate = {
                                    app.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                                },
                            ))
                        },
                        defaultPackId = V6_APPLICATION_UI,
                        legacyPackIds = V6_LEGACY_PACK_IDS,
                        // The same pack-owned visual frame as production; only selection differs.
                        availableCapabilities = owner.connectedCapabilities,
                        selectionPreferenceName = "ui_application_pack_v6_only_debug",
                        requestedSceneKey = requestedScene,
                        notificationSessions = owner.sessions,
                        onSceneRequestProcessed = { key, _ -> owner.consumeSceneRequest(key) },
                    )
                }
            }
        }
    }
}
