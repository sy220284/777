package com.labteto.dshmobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppViewModel
import com.labteto.dshmobile.ui.host.LocalApplicationFeaturePortsOwner
import com.labteto.dshmobile.ui.host.ZhaoxiApplicationRoot
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.UiHostPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** One independent E8.17 Compose entry; all domain owners and data remain in App/Feature Ports. */
// FEATURE_TREE: {"id":"ui.zhaoxi.e817","path":"第一层｜产品功能与界面 / 应用外壳与统一交互 / 首页和导航 / 朝夕 E8.17 独立界面接入（PR #740，未合并）","entry":"MainActivity","owner":"SHELL","test":"app/src/androidTest/java/com/labteto/dshmobile/ui/ZhaoxiHomeSmokeAndroidTest.kt#homeShowsInteractiveSessionAndMainRoutes"}
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var notifications: DshNotifications
    @Inject lateinit var hostPreferences: HostPreferencesUiPort
    private val features: LocalApplicationFeaturePortsOwner by viewModels()
    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw the E8.18 wallpaper behind both system bars; Compose content handles safe insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notifications.ensureChannels()
        setContent {
            val appearance by hostPreferences.snapshots.collectAsState(initial = UiHostPreferences())
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance.theme) {
                "dark", "matte_black" -> true
                "light" -> false
                else -> systemDark
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            ZhaoxiApplicationRoot(features, app, hostPreferences, onAtRootBack = { finish() })
        }
    }
}
