package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/** App-owned preferences, independent of any UI Pack's appearance components. */
data class UiHostPreferences(
    val theme: String = "system",
    val accent: String = "celadon",
    val textScale: Float = 1f,
    val textWeight: Int = 0,
    val transparency: Float = .5f,
    val ambientGlow: Float = .5f,
    val componentEmphasis: Float = .5f,
    val motionScale: Float = 1f,
    val adaptiveContrast: Boolean = true,
    val presetWallpaperEnabled: Boolean = false,
    val notifyJobs: Boolean = true,
    val notifyResults: Boolean = true,
    val notifyFailures: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    val notifyChat: Boolean = true,
    val quietEnabled: Boolean = false,
    val quietStartMinute: Int = 1380,
    val quietEndMinute: Int = 420,
)

sealed interface UiHostPreferencesCommand {
    data class Appearance(val theme: String, val accent: String, val textScale: Float,
        val textWeight: Int, val transparency: Float, val ambientGlow: Float,
        val componentEmphasis: Float, val motionScale: Float, val adaptiveContrast: Boolean,
        val presetWallpaperEnabled: Boolean = false,
    ) : UiHostPreferencesCommand
    data class Notifications(val notifyJobs: Boolean, val notifyResults: Boolean,
        val notifyFailures: Boolean, val notifyNeedsAction: Boolean,
        val notifyChat: Boolean, val quietEnabled: Boolean,
        val quietStartMinute: Int, val quietEndMinute: Int,
    ) : UiHostPreferencesCommand
}

/** Direct media collection permissions; SAF document-picker grants are scoped separately. */
data class UiHostMediaPermissionSnapshot(
    val images: Boolean, val videos: Boolean, val audio: Boolean,
    val selectedVisualOnly: Boolean, val usesScopedMediaPermissions: Boolean,
)

interface HostPreferencesUiPort {
    val snapshots: Flow<UiHostPreferences>
    suspend fun submit(command: UiHostPreferencesCommand): Boolean
    /** The Host opens the OS settings activity, never an arbitrary page or model URL. */
    fun openSystemPermissions(): Boolean
    fun openSystemNotifications(): Boolean
    /** OS-level notification access, not a saved preference and never a permission grant. */
    fun notificationPermissionGranted(): Boolean? = null
    /** OS permission facts only: this query never requests or grants access. */
    fun mediaPermissionSnapshot(): UiHostMediaPermissionSnapshot? = null
}
