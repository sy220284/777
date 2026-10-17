package com.labteto.dshmobile.ui.host

import android.content.Context
import android.content.Intent
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
    /** System permission for this app to POST notifications; independent from listener access. */
    val postingNotifications: Boolean? = null,
    val virtualDisplay: Boolean = true,
    val error: String? = null,
)

/** Host owns platform permission probes and all external Settings intents; UI can only request them. */
internal class DeviceCapabilitiesController(
    private val appContext: Context,
) {
    private val _state = MutableStateFlow(DeviceCapabilitiesState())
    val state: StateFlow<DeviceCapabilitiesState> = _state.asStateFlow()

    fun refresh() {
        _state.value = readDeviceCapabilityState().copy(
            postingNotifications = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        )
    }

    fun openAccessibilitySettings() = openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun openAppNotificationSettings() =
        openSystemSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, includePackage = true)

    fun openNotificationAccessSettings() =
        openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    private fun openSystemSettings(action: String, includePackage: Boolean = false) {
        val request = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (includePackage) request.putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        try {
            appContext.startActivity(request)
        } catch (_: android.content.ActivityNotFoundException) {
            _state.value = _state.value.copy(error = "HOST_SETTINGS_HANDLER_UNAVAILABLE")
        } catch (_: SecurityException) {
            _state.value = _state.value.copy(error = "HOST_SETTINGS_PERMISSION_DENIED")
        }
    }
}

/** Same read-only owner probe for Settings and task-specific handoff hints. */
internal fun readDeviceCapabilityState(): DeviceCapabilitiesState = DeviceCapabilitiesState(
    loading = false,
    accessibility = HarnessAccessibilityService.active() != null,
    notifications = HarnessNotificationListenerService.active() != null,
    // Availability of the API does not imply a captured screen or user MediaProjection grant.
    virtualDisplay = true,
)
