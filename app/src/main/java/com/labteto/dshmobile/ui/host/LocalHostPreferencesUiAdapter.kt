package com.labteto.dshmobile.ui.host

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.uicontract.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/**
 * Android 13+ requires both the OS runtime grant and app-level notification allowance.
 * Reading one flag alone can falsely claim that notifications are available.
 */
internal fun hostNotificationPermissionEffective(
    apiLevel: Int, runtimePermissionGranted: Boolean, notificationsEnabled: Boolean,
): Boolean = notificationsEnabled && (apiLevel < 33 || runtimePermissionGranted)

/** Existing DataStore remains the only durable application preferences writer. */
@Singleton
internal class LocalHostPreferencesUiAdapter @Inject constructor(
    private val hosts: HostsStore,
    @ApplicationContext private val context: Context,
) : HostPreferencesUiPort {
    override val snapshots = hosts.settings.map { prefs ->
        UiHostPreferences(
            theme = prefs.themePreference, accent = prefs.accentTheme,
            textScale = prefs.textScale, textWeight = prefs.textWeightAdjustment,
            transparency = prefs.wallpaperSurfaceTransparency, ambientGlow = prefs.ambientGlow,
            componentEmphasis = prefs.componentEmphasis, motionScale = prefs.motionDurationScale,
            adaptiveContrast = prefs.backgroundAdaptiveContrast,
            notifyJobs = prefs.notifyLocalJobs, notifyResults = prefs.notifyJobResults,
            notifyFailures = prefs.notifyJobFailures, notifyNeedsAction = prefs.notifyNeedsAction,
            notifyChat = prefs.notifyChatMessages,
            quietEnabled = prefs.notificationQuietHoursEnabled,
            quietStartMinute = prefs.notificationQuietStartMinute,
            quietEndMinute = prefs.notificationQuietEndMinute,
        )
    }

    override suspend fun submit(command: UiHostPreferencesCommand): Boolean {
        hosts.setSetting { current -> when (command) {
            is UiHostPreferencesCommand.Appearance -> {
                require(command.theme in setOf("light", "dark", "matte_black", "system"))
                require(command.accent.matches(Regex("[a-z0-9_-]{2,50}")))
                require(command.textScale in .9f..1.5f)
                require(command.textWeight in 0..2)
                require(command.transparency in 0f..1f && command.ambientGlow in 0f..1f &&
                    command.componentEmphasis in 0f..1f && command.motionScale in .7f..1.3f)
                current.copy(themePreference = command.theme, accentTheme = command.accent,
                    textScale = command.textScale, textWeightAdjustment = command.textWeight,
                    wallpaperSurfaceTransparency = command.transparency,
                    ambientGlow = command.ambientGlow, componentEmphasis = command.componentEmphasis,
                    motionDurationScale = command.motionScale,
                    backgroundAdaptiveContrast = command.adaptiveContrast)
            }
            is UiHostPreferencesCommand.Notifications -> {
                require(command.quietStartMinute in 0..1439 && command.quietEndMinute in 0..1439)
                current.copy(notifyLocalJobs = command.notifyJobs,
                    notifyJobResults = command.notifyResults, notifyJobFailures = command.notifyFailures,
                    notifyNeedsAction = command.notifyNeedsAction, notifyChatMessages = command.notifyChat,
                    notificationQuietHoursEnabled = command.quietEnabled,
                    notificationQuietStartMinute = command.quietStartMinute,
                    notificationQuietEndMinute = command.quietEndMinute)
            }
        } }
        return true
    }

    override fun notificationPermissionGranted(): Boolean? =
        runCatching {
            val notifications = context.getSystemService(NotificationManager::class.java)
                ?: return@runCatching null
            val granted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            hostNotificationPermissionEffective(
                Build.VERSION.SDK_INT, granted, notifications.areNotificationsEnabled(),
            )
        }.getOrNull()

    override fun openSystemPermissions(): Boolean = open(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
    )
    override fun openSystemNotifications(): Boolean = open(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
    private fun open(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) { false }
    catch (_: SecurityException) { false }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalHostPreferencesUiBindings {
    @dagger.Binds @Singleton
    abstract fun bind(implementation: LocalHostPreferencesUiAdapter): HostPreferencesUiPort
}
