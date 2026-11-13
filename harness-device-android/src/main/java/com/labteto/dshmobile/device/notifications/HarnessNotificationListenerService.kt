package com.labteto.dshmobile.device.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

data class NotificationSnapshot(
    val packageName: String,
    val key: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
)

class HarnessNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        current = WeakReference(this)
    }

    override fun onListenerDisconnected() {
        if (current?.get() === this) current = null
    }

    fun snapshots(): List<NotificationSnapshot> =
        activeNotifications.orEmpty().map { notification -> notification.toSnapshot() }

    private fun StatusBarNotification.toSnapshot(): NotificationSnapshot {
        val extras = notification.extras
        return NotificationSnapshot(
            packageName = packageName,
            key = key,
            title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            postedAt = postTime,
        )
    }

    companion object {
        @Volatile
        private var current: WeakReference<HarnessNotificationListenerService>? = null

        fun active(): HarnessNotificationListenerService? = current?.get()
    }
}
