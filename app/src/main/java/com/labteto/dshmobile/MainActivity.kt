package com.labteto.dshmobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppViewModel
import com.labteto.dshmobile.ui.host.LocalApplicationFeaturePortsOwner
import com.labteto.dshmobile.ui.host.ZhaoxiApplicationRoot
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** One independent E8.17 Compose entry; all domain owners and data remain in App/Feature Ports. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var notifications: DshNotifications
    @Inject lateinit var hostPreferences: HostPreferencesUiPort
    private val features: LocalApplicationFeaturePortsOwner by viewModels()
    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifications.ensureChannels()
        setContent {
            ZhaoxiApplicationRoot(features, app, hostPreferences, onAtRootBack = { finish() })
        }
    }
}
