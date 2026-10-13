package com.labteto.dshmobile.notify

import com.labteto.dshmobile.connection.AppSettings

/** Only notification DELIVERY is silenced: tasks and proactive chat execution never change. */
enum class LocalNotificationKind { RESULT, FAILURE, NEEDS_ACTION, CHAT_MESSAGE }

internal fun inNotificationQuietHours(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean {
    if (startMinute == endMinute) return false
    val now = nowMinute.coerceIn(0, 1439)
    return if (startMinute < endMinute) now in startMinute until endMinute
    else now >= startMinute || now < endMinute
}

internal fun shouldDeliverLocalNotification(
    settings: AppSettings,
    kind: LocalNotificationKind,
    localMinute: Int,
): Boolean {
    if (!settings.notifyLocalJobs) return false
    val allowed = when (kind) {
        LocalNotificationKind.RESULT -> settings.notifyJobResults
        LocalNotificationKind.FAILURE -> settings.notifyJobFailures
        LocalNotificationKind.NEEDS_ACTION -> settings.notifyNeedsAction
        LocalNotificationKind.CHAT_MESSAGE -> settings.notifyChatMessages
    }
    if (!allowed) return false
    // Urgent events bypass optional quiet hours, but their independent switches still apply.
    if (kind == LocalNotificationKind.FAILURE || kind == LocalNotificationKind.NEEDS_ACTION) return true
    if (!settings.notificationQuietHoursEnabled) return true
    return !inNotificationQuietHours(
        localMinute,
        settings.notificationQuietStartMinute,
        settings.notificationQuietEndMinute,
    )
}
