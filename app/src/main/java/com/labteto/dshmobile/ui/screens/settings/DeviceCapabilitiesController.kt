package com.labteto.dshmobile.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.app.ActivityManager
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.labteto.dshmobile.device.accessibility.HarnessAccessibilityService
import com.labteto.dshmobile.device.notifications.HarnessNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceCapabilitiesState(
    val loading: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
    /** System grant and a bound service are separate states after returning from Settings. */
    val accessibilityGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    /** System permission for this app to POST notifications; independent from listener access. */
    val postingNotifications: Boolean? = null,
    /** Doze exemption is user-controlled; visiting Settings never counts as granted. */
    val batteryOptimizationExempt: Boolean = false,
    /** A manually restricted app may lose foreground-service admission after navigation. */
    val backgroundRestricted: Boolean = false,
    val virtualDisplay: Boolean = true,
    val error: String? = null,
)

/** Owns device-capability probes and Android system-settings navigation. */
internal class DeviceCapabilitiesController(
    private val appContext: Context,
) {
    private val _state = MutableStateFlow(DeviceCapabilitiesState())
    val state: StateFlow<DeviceCapabilitiesState> = _state.asStateFlow()

    fun refresh() {
        val accessibilityComponent = ComponentName(appContext, HarnessAccessibilityService::class.java)
        val enabledAccessibility = Settings.Secure.getString(
            appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':')
        val accessibilityGranted = Settings.Secure.getInt(
            appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
        ) == 1 && enabledAccessibility.any {
            ComponentName.unflattenFromString(it) == accessibilityComponent
        }
        val listenerComponent = ComponentName(appContext, HarnessNotificationListenerService::class.java)
        _state.value = readDeviceCapabilityState(appContext).copy(
            accessibilityGranted = accessibilityGranted,
            notificationAccessGranted = appContext.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(listenerComponent),
            postingNotifications = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            batteryOptimizationExempt = appContext.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(appContext.packageName),
            backgroundRestricted = appContext.getSystemService(ActivityManager::class.java)
                .isBackgroundRestricted,
        )
    }

    /**
     * Explicit user action only. Continuous on-device Work requires live network access
     * while idle; this opens Android's visible consent dialog, never changes settings silently.
     */
    fun requestContinuousWorkBatteryExemption() {
        if (appContext.getSystemService(ActivityManager::class.java).isBackgroundRestricted) {
            openAppDetails() // Restricted state needs the full battery usage screen.
            return
        }
        appContext.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + appContext.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openBatteryOptimizationSettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAppDetails() {
        appContext.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + appContext.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAccessibilitySettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAppNotificationSettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openNotificationAccessSettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Repair the actual listener connection without sending an already-authorized user
     * back to Android Settings. Requesting a bind is asynchronous: readiness is verified
     * independently by refresh() and never assumed from this call.
     */
    fun repairNotificationAccess() {
        refresh()
        when (notificationAccessRepair(_state.value.notificationAccessGranted, _state.value.notifications)) {
            NotificationAccessRepair.OPEN_SYSTEM_SETTINGS -> openNotificationAccessSettings()
            NotificationAccessRepair.REQUEST_REBIND ->
                NotificationListenerService.requestRebind(
                    ComponentName(appContext, HarnessNotificationListenerService::class.java),
                )
            NotificationAccessRepair.READY -> Unit
        }
    }
}

/** Same read-only owner probe for Settings and task-specific handoff hints. */
internal fun readDeviceCapabilityState(context: Context? = null): DeviceCapabilitiesState = DeviceCapabilitiesState(
    loading = false,
    accessibility = HarnessAccessibilityService.active() != null,
    notifications = HarnessNotificationListenerService.active() != null,
    // Feature detection is separate from per-target launch authorization. Never claim
    // support before checking the device's secondary-activity display capability.
    virtualDisplay = context?.packageManager?.hasSystemFeature(
        android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
    ) == true,
)
