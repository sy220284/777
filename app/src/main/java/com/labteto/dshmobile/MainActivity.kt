package com.labteto.dshmobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * UI-free Android entrypoint. The app retains services, runtime, storage and Feature Ports,
 * but deliberately installs no Compose tree, V6 layout or replacement visual shell.
 *
 * The next UI installation owns onCreate content and navigation; no legacy fallback exists.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var notifications: DshNotifications

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifications.ensureChannels()
    }
}
